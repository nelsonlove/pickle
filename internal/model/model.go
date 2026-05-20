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

type Link struct {
	Label string `json:"label"`
	URL   string `json:"url,omitempty"`
	Path  string `json:"path,omitempty"`
}

type Request struct {
	ID         string          `json:"id"`
	Source     string          `json:"source"`
	Kind       string          `json:"kind"`
	Title      string          `json:"title"`
	Body       string          `json:"body"`
	Schema     json.RawMessage `json:"schema"`
	Status     string          `json:"status"`
	Priority   string          `json:"priority"`
	Links      []Link          `json:"links"`
	Metadata   json.RawMessage `json:"metadata"`
	DedupeKey  string          `json:"dedupe_key,omitempty"`
	CreatedAt  time.Time       `json:"created_at"`
	UpdatedAt  time.Time       `json:"updated_at"`
	AnsweredAt *time.Time      `json:"answered_at,omitempty"`
	Response   *Response       `json:"response,omitempty"`
}

type CreateRequest struct {
	Source    string          `json:"source"`
	Kind      string          `json:"kind"`
	Title     string          `json:"title"`
	Body      string          `json:"body"`
	Schema    json.RawMessage `json:"schema"`
	Priority  string          `json:"priority"`
	Links     []Link          `json:"links"`
	Metadata  json.RawMessage `json:"metadata"`
	DedupeKey string          `json:"dedupe_key,omitempty"`
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
