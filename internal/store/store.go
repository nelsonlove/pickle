package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/callumalpass/pickle/internal/model"
	"github.com/callumalpass/pickle/internal/schema"
	_ "modernc.org/sqlite"
)

var ErrNotFound = errors.New("not found")

type Store struct {
	db            *sql.DB
	attachmentDir string
}

func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	attachmentDir, err := defaultAttachmentDir(path)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	s := &Store{db: db, attachmentDir: attachmentDir}
	if _, err := db.Exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;`); err != nil {
		_ = db.Close()
		return nil, err
	}
	if err := s.Migrate(context.Background()); err != nil {
		_ = db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error {
	return s.db.Close()
}

func (s *Store) Migrate(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `
CREATE TABLE IF NOT EXISTS requests (
  id TEXT PRIMARY KEY,
  source TEXT NOT NULL,
  kind TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  schema_json TEXT NOT NULL,
  status TEXT NOT NULL,
  priority TEXT NOT NULL,
  tags_json TEXT NOT NULL DEFAULT '[]',
  links_json TEXT NOT NULL,
  metadata_json TEXT NOT NULL,
  dedupe_key TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  answered_at TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS requests_dedupe_key
ON requests(dedupe_key)
WHERE dedupe_key IS NOT NULL AND dedupe_key != '';

CREATE INDEX IF NOT EXISTS requests_status_updated
ON requests(status, updated_at DESC);

CREATE TABLE IF NOT EXISTS responses (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  request_id TEXT NOT NULL UNIQUE REFERENCES requests(id) ON DELETE CASCADE,
  responder TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS attachments (
  id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
  filename TEXT NOT NULL,
  content_type TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  storage_path TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS attachments_request_created
ON attachments(request_id, created_at, id);

CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL,
  request_id TEXT,
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS events_id_created
ON events(id, created_at);
`)
	if err != nil {
		return err
	}
	return s.ensureColumn(ctx, "requests", "tags_json", "TEXT NOT NULL DEFAULT '[]'")
}

func (s *Store) ensureColumn(ctx context.Context, table, column, definition string) error {
	rows, err := s.db.QueryContext(ctx, `PRAGMA table_info(`+table+`)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var cid int
		var name, typ string
		var notNull int
		var defaultValue sql.NullString
		var pk int
		if err := rows.Scan(&cid, &name, &typ, &notNull, &defaultValue, &pk); err != nil {
			return err
		}
		if name == column {
			return nil
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `ALTER TABLE `+table+` ADD COLUMN `+column+` `+definition)
	return err
}

func (s *Store) CreateRequest(ctx context.Context, input model.CreateRequest) (model.Request, error) {
	if strings.TrimSpace(input.Title) == "" {
		return model.Request{}, fmt.Errorf("title is required")
	}
	if strings.TrimSpace(input.Source) == "" {
		input.Source = "agent"
	}
	if strings.TrimSpace(input.Kind) == "" {
		input.Kind = model.KindApproval
	}
	if strings.TrimSpace(input.Priority) == "" {
		input.Priority = "normal"
	}
	if len(input.Schema) == 0 {
		input.Schema = model.DefaultSchema()
	}
	if len(input.Metadata) == 0 {
		input.Metadata = model.DefaultMetadata()
	}
	if input.Links == nil {
		input.Links = []model.Link{}
	}
	tags := normalizeTags(input.Tags)
	tagsJSON, err := json.Marshal(tags)
	if err != nil {
		return model.Request{}, fmt.Errorf("encode tags: %w", err)
	}
	linksJSON, err := json.Marshal(input.Links)
	if err != nil {
		return model.Request{}, fmt.Errorf("encode links: %w", err)
	}
	attachments, err := s.prepareAttachments(input.Attachments)
	if err != nil {
		return model.Request{}, err
	}
	id, err := model.NewRequestID()
	if err != nil {
		return model.Request{}, err
	}
	now := time.Now().UTC()
	req := model.Request{
		ID:          id,
		Source:      input.Source,
		Kind:        input.Kind,
		Title:       input.Title,
		Body:        input.Body,
		Schema:      input.Schema,
		Status:      model.StatusPending,
		Priority:    input.Priority,
		Tags:        tags,
		Links:       input.Links,
		Attachments: attachmentModels(attachments, id, now),
		Metadata:    input.Metadata,
		DedupeKey:   input.DedupeKey,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return model.Request{}, err
	}
	defer tx.Rollback()

	_, err = tx.ExecContext(ctx, `
INSERT INTO requests (
  id, source, kind, title, body, schema_json, status, priority, tags_json, links_json,
  metadata_json, dedupe_key, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		req.ID, req.Source, req.Kind, req.Title, req.Body, string(req.Schema),
		req.Status, req.Priority, string(tagsJSON), string(linksJSON), string(req.Metadata),
		nullable(input.DedupeKey), formatTime(req.CreatedAt), formatTime(req.UpdatedAt),
	)
	if err != nil {
		if strings.Contains(err.Error(), "constraint failed") && input.DedupeKey != "" {
			existing, getErr := s.getRequestTx(ctx, tx, "dedupe_key = ?", input.DedupeKey)
			if getErr != nil {
				return model.Request{}, err
			}
			if err := tx.Commit(); err != nil {
				return model.Request{}, err
			}
			return s.GetRequest(ctx, existing.ID)
		}
		return model.Request{}, err
	}
	written, err := s.storeAttachmentsTx(ctx, tx, req.ID, attachments, now)
	if err != nil {
		cleanupFiles(written)
		return model.Request{}, err
	}
	committed := false
	defer func() {
		if !committed {
			cleanupFiles(written)
		}
	}()
	payload, _ := json.Marshal(map[string]any{
		"id":          req.ID,
		"title":       req.Title,
		"source":      req.Source,
		"kind":        req.Kind,
		"priority":    req.Priority,
		"tags":        req.Tags,
		"attachments": attachmentEventPayload(req.Attachments),
	})
	if _, err := insertEvent(ctx, tx, model.EventRequestCreated, req.ID, payload, now); err != nil {
		return model.Request{}, err
	}
	if err := tx.Commit(); err != nil {
		return model.Request{}, err
	}
	committed = true
	return req, nil
}

func (s *Store) ListRequests(ctx context.Context, status string, limit int) ([]model.Request, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	where := "1 = 1"
	args := []any{}
	if status != "" && status != "all" {
		where = "status = ?"
		args = append(args, status)
	}
	args = append(args, limit)
	rows, err := s.db.QueryContext(ctx, `
SELECT r.id, r.source, r.kind, r.title, r.body, r.schema_json, r.status,
       r.priority, r.tags_json, r.links_json, r.metadata_json, r.dedupe_key, r.created_at,
       r.updated_at, r.answered_at, rp.responder, rp.payload_json, rp.created_at
FROM requests r
LEFT JOIN responses rp ON rp.request_id = r.id
WHERE `+where+`
ORDER BY r.updated_at DESC
LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []model.Request{}
	for rows.Next() {
		req, err := scanRequest(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, req)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return s.loadAttachmentsForRequests(ctx, out)
}

func (s *Store) GetRequest(ctx context.Context, id string) (model.Request, error) {
	req, err := s.getRequest(ctx, "r.id = ?", id)
	if err != nil {
		return model.Request{}, err
	}
	req.Attachments, err = s.ListAttachments(ctx, req.ID)
	if err != nil {
		return model.Request{}, err
	}
	return req, nil
}

func (s *Store) Respond(ctx context.Context, requestID string, input model.CreateResponse) (model.Request, error) {
	if strings.TrimSpace(input.Responder) == "" {
		input.Responder = "callum"
	}
	if len(input.Payload) == 0 {
		return model.Request{}, fmt.Errorf("response payload is required")
	}
	if !json.Valid(input.Payload) {
		return model.Request{}, fmt.Errorf("response payload is not valid JSON")
	}
	now := time.Now().UTC()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return model.Request{}, err
	}
	defer tx.Rollback()
	req, err := s.getRequestTx(ctx, tx, "id = ?", requestID)
	if err != nil {
		return model.Request{}, err
	}
	if req.Status == model.StatusAnswered {
		return model.Request{}, fmt.Errorf("request %s is already answered", requestID)
	}
	if err := schema.ValidateResponse(req.Schema, input.Payload); err != nil {
		return model.Request{}, err
	}
	_, err = tx.ExecContext(ctx, `
INSERT INTO responses(request_id, responder, payload_json, created_at)
VALUES (?, ?, ?, ?)`,
		requestID, input.Responder, string(input.Payload), formatTime(now),
	)
	if err != nil {
		return model.Request{}, err
	}
	_, err = tx.ExecContext(ctx, `
UPDATE requests
SET status = ?, updated_at = ?, answered_at = ?
WHERE id = ?`,
		model.StatusAnswered, formatTime(now), formatTime(now), requestID,
	)
	if err != nil {
		return model.Request{}, err
	}
	payload, _ := json.Marshal(map[string]any{
		"id":        requestID,
		"responder": input.Responder,
		"response":  json.RawMessage(input.Payload),
	})
	if _, err := insertEvent(ctx, tx, model.EventRequestAnswered, requestID, payload, now); err != nil {
		return model.Request{}, err
	}
	if err := tx.Commit(); err != nil {
		return model.Request{}, err
	}
	return s.GetRequest(ctx, requestID)
}

func (s *Store) ListEventsAfter(ctx context.Context, afterID int64, limit int) ([]model.Event, error) {
	if limit <= 0 || limit > 1000 {
		limit = 100
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT id, type, request_id, payload_json, created_at
FROM events
WHERE id > ?
ORDER BY id ASC
LIMIT ?`, afterID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.Event
	for rows.Next() {
		event, err := scanEvent(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, event)
	}
	return out, rows.Err()
}

func (s *Store) LastEventID(ctx context.Context) (int64, error) {
	var id sql.NullInt64
	if err := s.db.QueryRowContext(ctx, `SELECT MAX(id) FROM events`).Scan(&id); err != nil {
		return 0, err
	}
	if !id.Valid {
		return 0, nil
	}
	return id.Int64, nil
}

func (s *Store) getRequest(ctx context.Context, where string, args ...any) (model.Request, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT r.id, r.source, r.kind, r.title, r.body, r.schema_json, r.status,
       r.priority, r.tags_json, r.links_json, r.metadata_json, r.dedupe_key, r.created_at,
       r.updated_at, r.answered_at, rp.responder, rp.payload_json, rp.created_at
FROM requests r
LEFT JOIN responses rp ON rp.request_id = r.id
WHERE `+where+`
LIMIT 1`, args...)
	if err != nil {
		return model.Request{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return model.Request{}, ErrNotFound
	}
	return scanRequest(rows)
}

func (s *Store) getRequestTx(ctx context.Context, tx *sql.Tx, where string, args ...any) (model.Request, error) {
	rows, err := tx.QueryContext(ctx, `
SELECT id, source, kind, title, body, schema_json, status, priority, tags_json, links_json,
       metadata_json, dedupe_key, created_at, updated_at, answered_at
FROM requests
WHERE `+where+`
LIMIT 1`, args...)
	if err != nil {
		return model.Request{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return model.Request{}, ErrNotFound
	}
	var req model.Request
	var tagsJSON string
	var linksJSON string
	var schemaJSON string
	var metadataJSON string
	var dedupe sql.NullString
	var answeredAt sql.NullString
	var createdAt, updatedAt string
	if err := rows.Scan(
		&req.ID, &req.Source, &req.Kind, &req.Title, &req.Body, &schemaJSON,
		&req.Status, &req.Priority, &tagsJSON, &linksJSON, &metadataJSON, &dedupe,
		&createdAt, &updatedAt, &answeredAt,
	); err != nil {
		return model.Request{}, err
	}
	req.Schema = json.RawMessage(schemaJSON)
	req.Metadata = json.RawMessage(metadataJSON)
	req.DedupeKey = dedupe.String
	req.CreatedAt, _ = parseTime(createdAt)
	req.UpdatedAt, _ = parseTime(updatedAt)
	if answeredAt.Valid {
		t, _ := parseTime(answeredAt.String)
		req.AnsweredAt = &t
	}
	if err := decodeStringList(tagsJSON, &req.Tags); err != nil {
		return model.Request{}, err
	}
	if linksJSON == "null" || linksJSON == "" {
		req.Links = []model.Link{}
	} else if err := json.Unmarshal([]byte(linksJSON), &req.Links); err != nil {
		return model.Request{}, err
	}
	return req, nil
}

func insertEvent(ctx context.Context, tx *sql.Tx, typ, requestID string, payload []byte, now time.Time) (int64, error) {
	res, err := tx.ExecContext(ctx, `
INSERT INTO events(type, request_id, payload_json, created_at)
VALUES (?, ?, ?, ?)`, typ, nullable(requestID), string(payload), formatTime(now))
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

type requestScanner interface {
	Scan(dest ...any) error
}

func scanRequest(rows requestScanner) (model.Request, error) {
	var req model.Request
	var tagsJSON string
	var linksJSON string
	var schemaJSON string
	var metadataJSON string
	var dedupe sql.NullString
	var answeredAt sql.NullString
	var createdAt, updatedAt string
	var responder, responsePayload, responseCreated sql.NullString
	if err := rows.Scan(
		&req.ID, &req.Source, &req.Kind, &req.Title, &req.Body, &schemaJSON,
		&req.Status, &req.Priority, &tagsJSON, &linksJSON, &metadataJSON, &dedupe,
		&createdAt, &updatedAt, &answeredAt, &responder, &responsePayload, &responseCreated,
	); err != nil {
		return model.Request{}, err
	}
	req.Schema = json.RawMessage(schemaJSON)
	req.Metadata = json.RawMessage(metadataJSON)
	req.DedupeKey = dedupe.String
	var err error
	req.CreatedAt, err = parseTime(createdAt)
	if err != nil {
		return model.Request{}, err
	}
	req.UpdatedAt, err = parseTime(updatedAt)
	if err != nil {
		return model.Request{}, err
	}
	if answeredAt.Valid {
		t, err := parseTime(answeredAt.String)
		if err != nil {
			return model.Request{}, err
		}
		req.AnsweredAt = &t
	}
	if err := decodeStringList(tagsJSON, &req.Tags); err != nil {
		return model.Request{}, err
	}
	if linksJSON == "null" || linksJSON == "" {
		req.Links = []model.Link{}
	} else if err := json.Unmarshal([]byte(linksJSON), &req.Links); err != nil {
		return model.Request{}, err
	}
	if responsePayload.Valid {
		t, err := parseTime(responseCreated.String)
		if err != nil {
			return model.Request{}, err
		}
		req.Response = &model.Response{
			RequestID: req.ID,
			Responder: responder.String,
			Payload:   json.RawMessage(responsePayload.String),
			CreatedAt: t,
		}
	}
	return req, nil
}

func scanEvent(rows requestScanner) (model.Event, error) {
	var event model.Event
	var requestID sql.NullString
	var payloadJSON string
	var createdAt string
	if err := rows.Scan(&event.ID, &event.Type, &requestID, &payloadJSON, &createdAt); err != nil {
		return model.Event{}, err
	}
	event.RequestID = requestID.String
	event.Payload = json.RawMessage(payloadJSON)
	t, err := parseTime(createdAt)
	if err != nil {
		return model.Event{}, err
	}
	event.CreatedAt = t
	return event, nil
}

func nullable(v string) any {
	if v == "" {
		return nil
	}
	return v
}

func normalizeTags(tags []string) []string {
	out := make([]string, 0, len(tags))
	seen := map[string]bool{}
	for _, tag := range tags {
		tag = strings.TrimSpace(strings.TrimPrefix(tag, "#"))
		if tag == "" || seen[tag] {
			continue
		}
		seen[tag] = true
		out = append(out, tag)
	}
	return out
}

func decodeStringList(raw string, dest *[]string) error {
	if raw == "" || raw == "null" {
		*dest = []string{}
		return nil
	}
	if err := json.Unmarshal([]byte(raw), dest); err != nil {
		return fmt.Errorf("decode string list: %w", err)
	}
	if *dest == nil {
		*dest = []string{}
	}
	return nil
}

func formatTime(t time.Time) string {
	return t.UTC().Format(time.RFC3339Nano)
}

func parseTime(v string) (time.Time, error) {
	return time.Parse(time.RFC3339Nano, v)
}
