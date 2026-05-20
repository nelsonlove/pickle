package server

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/callumalpass/wickle/internal/model"
	"github.com/callumalpass/wickle/internal/store"
)

func TestAPIRequiresTokenAndCreatesRequest(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	st, err := store.Open(filepath.Join(t.TempDir(), "wickle.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	srv, err := New(st, "secret", nil)
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv.Handler(ctx))
	defer ts.Close()

	resp, err := http.Post(ts.URL+"/api/v1/requests", "application/json", bytes.NewBufferString(`{"title":"No token"}`))
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d", resp.StatusCode)
	}
	_ = resp.Body.Close()

	body := bytes.NewBufferString(`{"source":"test","kind":"approval","title":"Approve deploy?"}`)
	req, err := http.NewRequest(http.MethodPost, ts.URL+"/api/v1/requests", body)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer secret")
	req.Header.Set("Content-Type", "application/json")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status = %d", resp.StatusCode)
	}
	var created model.Request
	if err := json.NewDecoder(resp.Body).Decode(&created); err != nil {
		t.Fatal(err)
	}
	if created.ID == "" || created.Status != model.StatusPending {
		t.Fatalf("bad request: %#v", created)
	}
}
