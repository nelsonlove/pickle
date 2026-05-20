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
		Tags:   []string{"ops", "#approval", "ops"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if req.Status != model.StatusPending {
		t.Fatalf("status = %q", req.Status)
	}
	if got, want := req.Tags, []string{"ops", "approval"}; !equalStringSlices(got, want) {
		t.Fatalf("tags = %#v, want %#v", got, want)
	}
	events, err := s.ListEventsAfter(ctx, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 1 || events[0].Type != model.EventRequestCreated {
		t.Fatalf("unexpected events: %#v", events)
	}
	var createdPayload struct {
		Tags []string `json:"tags"`
	}
	if err := json.Unmarshal(events[0].Payload, &createdPayload); err != nil {
		t.Fatal(err)
	}
	if got, want := createdPayload.Tags, []string{"ops", "approval"}; !equalStringSlices(got, want) {
		t.Fatalf("event tags = %#v, want %#v", got, want)
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

func TestListRequestsReturnsMessageTags(t *testing.T) {
	ctx := context.Background()
	s, err := Open(filepath.Join(t.TempDir(), "wickle.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	req, err := s.CreateRequest(ctx, model.CreateRequest{
		Source: "callum",
		Kind:   model.KindMessage,
		Title:  "Note for agents",
		Body:   "Please look at the fresh ops files.",
		Tags:   []string{"ops", "follow-up"},
	})
	if err != nil {
		t.Fatal(err)
	}
	requests, err := s.ListRequests(ctx, model.StatusPending, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(requests) != 1 || requests[0].ID != req.ID {
		t.Fatalf("requests = %#v", requests)
	}
	if requests[0].Kind != model.KindMessage {
		t.Fatalf("kind = %q", requests[0].Kind)
	}
	if got, want := requests[0].Tags, []string{"ops", "follow-up"}; !equalStringSlices(got, want) {
		t.Fatalf("tags = %#v, want %#v", got, want)
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

func equalStringSlices(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
