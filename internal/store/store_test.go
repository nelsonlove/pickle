package store

import (
	"context"
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/callumalpass/wickle/internal/model"
)

func TestCreateRespondAndEvents(t *testing.T) {
	ctx := context.Background()
	s, err := Open(filepath.Join(t.TempDir(), "wickle.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	req, err := s.CreateRequest(ctx, model.CreateRequest{
		Source: "tasknotes-ops",
		Kind:   "approval",
		Title:  "Close issue?",
		Schema: json.RawMessage(`{"type":"object"}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	if req.Status != model.StatusPending {
		t.Fatalf("status = %q", req.Status)
	}
	events, err := s.ListEventsAfter(ctx, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 1 || events[0].Type != model.EventRequestCreated {
		t.Fatalf("unexpected events: %#v", events)
	}

	answered, err := s.Respond(ctx, req.ID, model.CreateResponse{
		Responder: "callum",
		Payload:   json.RawMessage(`{"decision":"approve"}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	if answered.Status != model.StatusAnswered {
		t.Fatalf("status = %q", answered.Status)
	}
	if answered.Response == nil || string(answered.Response.Payload) != `{"decision":"approve"}` {
		t.Fatalf("missing response: %#v", answered.Response)
	}
	events, err = s.ListEventsAfter(ctx, events[0].ID, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 1 || events[0].Type != model.EventRequestAnswered {
		t.Fatalf("unexpected response events: %#v", events)
	}
}

func TestDedupeKeyReturnsExistingRequest(t *testing.T) {
	ctx := context.Background()
	s, err := Open(filepath.Join(t.TempDir(), "wickle.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	first, err := s.CreateRequest(ctx, model.CreateRequest{Title: "First", DedupeKey: "same"})
	if err != nil {
		t.Fatal(err)
	}
	second, err := s.CreateRequest(ctx, model.CreateRequest{Title: "Second", DedupeKey: "same"})
	if err != nil {
		t.Fatal(err)
	}
	if first.ID != second.ID {
		t.Fatalf("dedupe returned new request: %s != %s", first.ID, second.ID)
	}
}
