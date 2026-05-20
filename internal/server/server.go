package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/callumalpass/pickle/internal/model"
	"github.com/callumalpass/pickle/internal/store"
	"github.com/gorilla/websocket"
)

type Server struct {
	store  *store.Store
	token  string
	broker *Broker
	log    *log.Logger
}

func New(s *store.Store, token string, logger *log.Logger) (*Server, error) {
	lastID, err := s.LastEventID(context.Background())
	if err != nil {
		return nil, err
	}
	if logger == nil {
		logger = log.Default()
	}
	return &Server{
		store:  s,
		token:  token,
		broker: NewBroker(lastID),
		log:    logger,
	}, nil
}

func (s *Server) Handler(ctx context.Context) http.Handler {
	go s.broker.Run(ctx, s.store)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.handleHealth)
	mux.Handle("/api/v1/", s.auth(http.HandlerFunc(s.handleAPI)))
	return mux
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) handleAPI(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/v1")
	switch {
	case r.Method == http.MethodGet && path == "/inbox":
		s.handleInbox(w, r)
	case r.Method == http.MethodPost && path == "/requests":
		s.handleCreateRequest(w, r)
	case r.Method == http.MethodGet && isAttachmentPath(path):
		requestID, attachmentID, _ := parseAttachmentPath(path)
		s.handleGetAttachment(w, r, requestID, attachmentID)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/requests/"):
		s.handleGetRequest(w, r, strings.TrimPrefix(path, "/requests/"))
	case r.Method == http.MethodPost && strings.HasPrefix(path, "/requests/") && strings.HasSuffix(path, "/responses"):
		id := strings.TrimSuffix(strings.TrimPrefix(path, "/requests/"), "/responses")
		s.handleRespond(w, r, id)
	case r.Method == http.MethodGet && path == "/events":
		s.handleEvents(w, r)
	case r.Method == http.MethodGet && path == "/stream":
		s.handleStream(w, r)
	default:
		writeError(w, http.StatusNotFound, "not found")
	}
}

func (s *Server) handleInbox(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	if status == "" {
		status = model.StatusPending
	}
	limit := parseLimit(r.URL.Query().Get("limit"))
	requests, err := s.store.ListRequests(r.Context(), status, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"requests": requests})
}

func (s *Server) handleCreateRequest(w http.ResponseWriter, r *http.Request) {
	var input model.CreateRequest
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request JSON")
		return
	}
	req, err := s.store.CreateRequest(r.Context(), input)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, req)
}

func (s *Server) handleGetRequest(w http.ResponseWriter, r *http.Request, id string) {
	if strings.Contains(id, "/") || id == "" {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	req, err := s.store.GetRequest(r.Context(), id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "request not found")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, req)
}

func (s *Server) handleGetAttachment(w http.ResponseWriter, r *http.Request, requestID, attachmentID string) {
	if requestID == "" || attachmentID == "" || strings.Contains(requestID, "/") || strings.Contains(attachmentID, "/") {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	attachment, path, err := s.store.GetAttachment(r.Context(), requestID, attachmentID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "attachment not found")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", attachment.ContentType)
	w.Header().Set("Content-Length", strconv.FormatInt(attachment.SizeBytes, 10))
	w.Header().Set("Content-Disposition", mime.FormatMediaType("inline", map[string]string{"filename": attachment.Filename}))
	http.ServeFile(w, r, path)
}

func (s *Server) handleRespond(w http.ResponseWriter, r *http.Request, id string) {
	if strings.Contains(id, "/") || id == "" {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	var input model.CreateResponse
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid response JSON")
		return
	}
	req, err := s.store.Respond(r.Context(), id, input)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "request not found")
			return
		}
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, req)
}

func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	events, err := s.store.ListEventsAfter(r.Context(), after, parseLimit(r.URL.Query().Get("limit")))
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"events": events})
}

func (s *Server) handleStream(w http.ResponseWriter, r *http.Request) {
	upgrader := websocket.Upgrader{
		HandshakeTimeout: 5 * time.Second,
		CheckOrigin: func(r *http.Request) bool {
			return true
		},
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()

	ch, unsubscribe := s.broker.Subscribe()
	defer unsubscribe()

	_ = conn.WriteJSON(map[string]any{
		"type": "stream.ready",
		"ts":   time.Now().UTC(),
	})
	for {
		select {
		case <-r.Context().Done():
			return
		case event := <-ch:
			if err := conn.WriteJSON(event); err != nil {
				return
			}
		}
	}
}

func (s *Server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.token == "" {
			next.ServeHTTP(w, r)
			return
		}
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if token == "" {
			token = r.Header.Get("X-Pickle-Token")
		}
		if token == "" {
			token = r.URL.Query().Get("token")
		}
		if token != s.token {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func parseLimit(raw string) int {
	limit, _ := strconv.Atoi(raw)
	if limit <= 0 {
		return 100
	}
	return limit
}

func isAttachmentPath(path string) bool {
	_, _, ok := parseAttachmentPath(path)
	return ok
}

func parseAttachmentPath(path string) (string, string, bool) {
	trimmed := strings.TrimPrefix(path, "/requests/")
	parts := strings.Split(trimmed, "/")
	if len(parts) != 3 || parts[1] != "attachments" {
		return "", "", false
	}
	if parts[0] == "" || parts[2] == "" {
		return "", "", false
	}
	return parts[0], parts[2], true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func ListenAndServe(ctx context.Context, listen string, s *Server) error {
	httpServer := &http.Server{
		Addr:              listen,
		Handler:           s.Handler(ctx),
		ReadHeaderTimeout: 10 * time.Second,
	}
	errCh := make(chan error, 1)
	go func() {
		err := httpServer.ListenAndServe()
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
			return
		}
		errCh <- nil
	}()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return httpServer.Shutdown(shutdownCtx)
	case err := <-errCh:
		return err
	}
}

func URLToWebSocket(raw string) string {
	raw = strings.TrimRight(raw, "/")
	raw = strings.TrimPrefix(raw, "http://")
	raw = strings.TrimPrefix(raw, "https://")
	return fmt.Sprintf("ws://%s/api/v1/stream", raw)
}
