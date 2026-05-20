package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/callumalpass/pickle/internal/model"
)

const MaxAttachmentBytes = 10 * 1024 * 1024

var allowedAttachmentTypes = map[string]bool{
	"text/plain":    true,
	"text/markdown": true,
	"image/png":     true,
	"image/jpeg":    true,
	"image/webp":    true,
}

type preparedAttachment struct {
	id          string
	filename    string
	contentType string
	data        []byte
	sizeBytes   int64
	sha256      string
}

func defaultAttachmentDir(dbPath string) (string, error) {
	dir := filepath.Join(filepath.Dir(dbPath), "attachments")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return dir, nil
}

func (s *Store) prepareAttachments(inputs []model.CreateAttachment) ([]preparedAttachment, error) {
	if len(inputs) == 0 {
		return nil, nil
	}
	out := make([]preparedAttachment, 0, len(inputs))
	for _, input := range inputs {
		filename := filepath.Base(strings.TrimSpace(input.Filename))
		if filename == "" || filename == "." || filename == string(filepath.Separator) {
			return nil, fmt.Errorf("attachment filename is required")
		}
		data := input.Data
		if len(data) == 0 && input.DataBase64 != "" {
			decoded, err := base64.StdEncoding.DecodeString(input.DataBase64)
			if err != nil {
				return nil, fmt.Errorf("decode attachment %s: %w", filename, err)
			}
			data = decoded
		}
		if len(data) == 0 {
			return nil, fmt.Errorf("attachment %s is empty", filename)
		}
		if len(data) > MaxAttachmentBytes {
			return nil, fmt.Errorf("attachment %s exceeds %d bytes", filename, MaxAttachmentBytes)
		}
		contentType := normalizeAttachmentType(input.ContentType, filename, data)
		if !allowedAttachmentTypes[contentType] {
			return nil, fmt.Errorf("attachment %s has unsupported content type %q", filename, contentType)
		}
		id, err := model.NewAttachmentID()
		if err != nil {
			return nil, err
		}
		sum := sha256.Sum256(data)
		out = append(out, preparedAttachment{
			id:          id,
			filename:    filename,
			contentType: contentType,
			data:        data,
			sizeBytes:   int64(len(data)),
			sha256:      hex.EncodeToString(sum[:]),
		})
	}
	return out, nil
}

func normalizeAttachmentType(raw, filename string, data []byte) string {
	contentType := strings.TrimSpace(strings.ToLower(strings.Split(raw, ";")[0]))
	ext := strings.ToLower(filepath.Ext(filename))
	if contentType == "" {
		switch ext {
		case ".md", ".markdown":
			contentType = "text/markdown"
		case ".txt", ".log":
			contentType = "text/plain"
		case ".webp":
			contentType = "image/webp"
		}
	}
	if contentType == "" {
		contentType = strings.ToLower(strings.Split(http.DetectContentType(data), ";")[0])
	}
	if contentType == "text/plain" && (ext == ".md" || ext == ".markdown") {
		return "text/markdown"
	}
	return contentType
}

func attachmentModels(attachments []preparedAttachment, requestID string, createdAt time.Time) []model.Attachment {
	out := make([]model.Attachment, 0, len(attachments))
	for _, attachment := range attachments {
		out = append(out, model.Attachment{
			ID:          attachment.id,
			RequestID:   requestID,
			Filename:    attachment.filename,
			ContentType: attachment.contentType,
			SizeBytes:   attachment.sizeBytes,
			SHA256:      attachment.sha256,
			CreatedAt:   createdAt,
		})
	}
	return out
}

func attachmentEventPayload(attachments []model.Attachment) []map[string]any {
	out := make([]map[string]any, 0, len(attachments))
	for _, attachment := range attachments {
		out = append(out, map[string]any{
			"id":           attachment.ID,
			"filename":     attachment.Filename,
			"content_type": attachment.ContentType,
			"size_bytes":   attachment.SizeBytes,
		})
	}
	return out
}

func (s *Store) storeAttachmentsTx(ctx context.Context, tx *sql.Tx, requestID string, attachments []preparedAttachment, now time.Time) ([]string, error) {
	if len(attachments) == 0 {
		return nil, nil
	}
	requestDir := filepath.Join(s.attachmentDir, requestID)
	if err := os.MkdirAll(requestDir, 0o700); err != nil {
		return nil, err
	}
	var written []string
	for _, attachment := range attachments {
		relPath := filepath.Join(requestID, attachment.id)
		absPath, err := s.attachmentPath(relPath)
		if err != nil {
			return written, err
		}
		if err := os.WriteFile(absPath, attachment.data, 0o600); err != nil {
			return written, fmt.Errorf("write attachment %s: %w", attachment.filename, err)
		}
		written = append(written, absPath)
		_, err = tx.ExecContext(ctx, `
INSERT INTO attachments(id, request_id, filename, content_type, size_bytes, sha256, storage_path, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
			attachment.id, requestID, attachment.filename, attachment.contentType,
			attachment.sizeBytes, attachment.sha256, relPath, formatTime(now),
		)
		if err != nil {
			return written, err
		}
	}
	return written, nil
}

func cleanupFiles(paths []string) {
	for _, path := range paths {
		_ = os.Remove(path)
	}
}

func (s *Store) ListAttachments(ctx context.Context, requestID string) ([]model.Attachment, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT id, request_id, filename, content_type, size_bytes, sha256, created_at
FROM attachments
WHERE request_id = ?
ORDER BY created_at, id`, requestID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.Attachment
	for rows.Next() {
		attachment, err := scanAttachment(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, attachment)
	}
	if out == nil {
		out = []model.Attachment{}
	}
	return out, rows.Err()
}

func (s *Store) GetAttachment(ctx context.Context, requestID, attachmentID string) (model.Attachment, string, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT id, request_id, filename, content_type, size_bytes, sha256, storage_path, created_at
FROM attachments
WHERE request_id = ? AND id = ?
LIMIT 1`, requestID, attachmentID)
	if err != nil {
		return model.Attachment{}, "", err
	}
	defer rows.Close()
	if !rows.Next() {
		return model.Attachment{}, "", ErrNotFound
	}
	var attachment model.Attachment
	var storagePath string
	var createdAt string
	if err := rows.Scan(
		&attachment.ID, &attachment.RequestID, &attachment.Filename, &attachment.ContentType,
		&attachment.SizeBytes, &attachment.SHA256, &storagePath, &createdAt,
	); err != nil {
		return model.Attachment{}, "", err
	}
	t, err := parseTime(createdAt)
	if err != nil {
		return model.Attachment{}, "", err
	}
	attachment.CreatedAt = t
	path, err := s.attachmentPath(storagePath)
	if err != nil {
		return model.Attachment{}, "", err
	}
	return attachment, path, nil
}

func (s *Store) loadAttachmentsForRequests(ctx context.Context, requests []model.Request) ([]model.Request, error) {
	for i := range requests {
		attachments, err := s.ListAttachments(ctx, requests[i].ID)
		if err != nil {
			return nil, err
		}
		requests[i].Attachments = attachments
	}
	return requests, nil
}

func scanAttachment(rows requestScanner) (model.Attachment, error) {
	var attachment model.Attachment
	var createdAt string
	if err := rows.Scan(
		&attachment.ID, &attachment.RequestID, &attachment.Filename, &attachment.ContentType,
		&attachment.SizeBytes, &attachment.SHA256, &createdAt,
	); err != nil {
		return model.Attachment{}, err
	}
	t, err := parseTime(createdAt)
	if err != nil {
		return model.Attachment{}, err
	}
	attachment.CreatedAt = t
	return attachment, nil
}

func (s *Store) attachmentPath(relPath string) (string, error) {
	clean := filepath.Clean(relPath)
	if clean == "." || filepath.IsAbs(clean) || strings.HasPrefix(clean, ".."+string(filepath.Separator)) || clean == ".." {
		return "", fmt.Errorf("invalid attachment storage path")
	}
	return filepath.Join(s.attachmentDir, clean), nil
}
