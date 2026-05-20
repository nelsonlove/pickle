package model

import (
	"encoding/json"
	"time"
)

const (
	StatusPending   = "pending"
	StatusAnswered  = "answered"
	StatusCancelled = "cancelled"

	EventRequestCreated  = "request.created"
	EventRequestAnswered = "request.answered"
)

const (
	KindApproval = "approval"
	KindMessage  = "message"
)

type Link struct {
	Label string `json:"label"`
	URL   string `json:"url,omitempty"`
	Path  string `json:"path,omitempty"`
}

type Attachment struct {
	ID          string    `json:"id"`
	RequestID   string    `json:"request_id,omitempty"`
	Filename    string    `json:"filename"`
	ContentType string    `json:"content_type"`
	SizeBytes   int64     `json:"size_bytes"`
	SHA256      string    `json:"sha256"`
	CreatedAt   time.Time `json:"created_at"`
}

type Request struct {
	ID          string          `json:"id"`
	Source      string          `json:"source"`
	Kind        string          `json:"kind"`
	Title       string          `json:"title"`
	Body        string          `json:"body"`
	Schema      json.RawMessage `json:"schema"`
	Status      string          `json:"status"`
	Priority    string          `json:"priority"`
	Tags        []string        `json:"tags"`
	Links       []Link          `json:"links"`
	Attachments []Attachment    `json:"attachments"`
	Metadata    json.RawMessage `json:"metadata"`
	DedupeKey   string          `json:"dedupe_key,omitempty"`
	CreatedAt   time.Time       `json:"created_at"`
	UpdatedAt   time.Time       `json:"updated_at"`
	AnsweredAt  *time.Time      `json:"answered_at,omitempty"`
	Response    *Response       `json:"response,omitempty"`
}

type CreateRequest struct {
	Source      string             `json:"source"`
	Kind        string             `json:"kind"`
	Title       string             `json:"title"`
	Body        string             `json:"body"`
	Schema      json.RawMessage    `json:"schema"`
	Priority    string             `json:"priority"`
	Tags        []string           `json:"tags"`
	Links       []Link             `json:"links"`
	Attachments []CreateAttachment `json:"attachments,omitempty"`
	Metadata    json.RawMessage    `json:"metadata"`
	DedupeKey   string             `json:"dedupe_key,omitempty"`
}

type CreateAttachment struct {
	Filename    string `json:"filename"`
	ContentType string `json:"content_type,omitempty"`
	DataBase64  string `json:"data_base64,omitempty"`
	Data        []byte `json:"-"`
}

type Response struct {
	RequestID string          `json:"request_id"`
	Responder string          `json:"responder"`
	Payload   json.RawMessage `json:"payload"`
	CreatedAt time.Time       `json:"created_at"`
}

type CreateResponse struct {
	Responder string          `json:"responder"`
	Payload   json.RawMessage `json:"payload"`
}

type Event struct {
	ID        int64           `json:"id"`
	Type      string          `json:"type"`
	RequestID string          `json:"request_id,omitempty"`
	Payload   json.RawMessage `json:"payload"`
	CreatedAt time.Time       `json:"created_at"`
}

func DefaultSchema() json.RawMessage {
	return json.RawMessage(`{"type":"object","properties":{},"additionalProperties":true}`)
}

func DefaultMetadata() json.RawMessage {
	return json.RawMessage(`{}`)
}
