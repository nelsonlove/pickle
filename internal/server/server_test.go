package server

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/callumalpass/pickle/internal/model"
	"github.com/callumalpass/pickle/internal/store"
)

func TestAPIRequiresTokenAndCreatesRequest(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	st, err := store.Open(filepath.Join(t.TempDir(), "pickle.sqlite"))
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

	body := bytes.NewBufferString(`{"source":"test","kind":"approval","title":"Approve deploy?","tags":["ops","deploy"]}`)
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
	if len(created.Tags) != 2 || created.Tags[0] != "ops" || created.Tags[1] != "deploy" {
		t.Fatalf("bad tags: %#v", created.Tags)
	}
}

func TestAPICreatesAndServesAttachment(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	st, err := store.Open(filepath.Join(t.TempDir(), "pickle.sqlite"))
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

	payload := map[string]any{
		"source": "test",
		"kind":   "approval",
		"title":  "Review markdown",
		"attachments": []map[string]string{
			{
				"filename":     "context.md",
				"content_type": "text/markdown",
				"data_base64":  base64.StdEncoding.EncodeToString([]byte("# Context\n\nLooks fine.\n")),
			},
		},
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	req, err := http.NewRequest(http.MethodPost, ts.URL+"/api/v1/requests", bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer secret")
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
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
	if len(created.Attachments) != 1 {
		t.Fatalf("attachments = %#v", created.Attachments)
	}

	attachmentURL := ts.URL + "/api/v1/requests/" + created.ID + "/attachments/" + created.Attachments[0].ID
	req, err = http.NewRequest(http.MethodGet, attachmentURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer secret")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("attachment status = %d", resp.StatusCode)
	}
	if got := resp.Header.Get("Content-Type"); got != "text/markdown" {
		t.Fatalf("content-type = %q", got)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "# Context\n\nLooks fine.\n" {
		t.Fatalf("attachment body = %q", body)
	}
}
