package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"mime"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/callumalpass/pickle/internal/config"
	"github.com/callumalpass/pickle/internal/model"
	"github.com/callumalpass/pickle/internal/server"
	"github.com/callumalpass/pickle/internal/store"
	"github.com/gorilla/websocket"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "pickle:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	if len(args) == 0 {
		usage(os.Stdout)
		return nil
	}
	switch args[0] {
	case "init":
		return cmdInit(args[1:])
	case "serve":
		return cmdServe(args[1:])
	case "ask":
		return cmdAsk(args[1:])
	case "message":
		return cmdMessage(args[1:])
	case "inbox", "list":
		return cmdInbox(args[1:])
	case "show":
		return cmdShow(args[1:])
	case "respond":
		return cmdRespond(args[1:])
	case "response":
		return cmdResponse(args[1:])
	case "wait":
		return cmdWait(args[1:])
	case "events":
		return cmdEvents(args[1:])
	case "watch":
		return cmdWatch(args[1:])
	case "token":
		return cmdToken(args[1:])
	case "help", "-h", "--help":
		usage(os.Stdout)
		return nil
	default:
		return fmt.Errorf("unknown command %q", args[0])
	}
}

func cmdInit(args []string) error {
	fs := flag.NewFlagSet("init", flag.ContinueOnError)
	apiURL := fs.String("api-url", "", "default API URL")
	dataPath := fs.String("data", "", "SQLite database path")
	if err := fs.Parse(args); err != nil {
		return err
	}
	cfg, created, err := config.Ensure()
	if err != nil {
		return err
	}
	if *apiURL != "" {
		cfg.APIURL = *apiURL
	}
	if *dataPath != "" {
		cfg.DataPath = *dataPath
	}
	if err := os.MkdirAll(filepath.Dir(cfg.DataPath), 0o700); err != nil {
		return err
	}
	st, err := store.Open(cfg.DataPath)
	if err != nil {
		return err
	}
	_ = st.Close()
	if err := config.Save(cfg); err != nil {
		return err
	}
	if created {
		fmt.Println("created", config.Path())
	} else {
		fmt.Println("updated", config.Path())
	}
	fmt.Println("data", cfg.DataPath)
	fmt.Println("api", cfg.APIURL)
	return nil
}

func cmdServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	listen := fs.String("listen", "127.0.0.1:8787", "host:port to listen on")
	if err := fs.Parse(args); err != nil {
		return err
	}
	cfg, _, err := config.Ensure()
	if err != nil {
		return err
	}
	st, err := store.Open(cfg.DataPath)
	if err != nil {
		return err
	}
	defer st.Close()
	logger := log.New(os.Stderr, "pickled: ", log.LstdFlags)
	srv, err := server.New(st, cfg.Token, logger)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	logger.Printf("listening on %s", *listen)
	logger.Printf("api token loaded from %s", config.Path())
	return server.ListenAndServe(ctx, *listen, srv)
}

func cmdAsk(args []string) error {
	fs := flag.NewFlagSet("ask", flag.ContinueOnError)
	source := fs.String("source", "agent", "request source")
	kind := fs.String("kind", "approval", "request kind")
	title := fs.String("title", "", "request title")
	body := fs.String("body", "", "request body")
	bodyFile := fs.String("body-file", "", "file to read request body from")
	schemaFile := fs.String("schema", "", "JSON Schema file")
	priority := fs.String("priority", "normal", "priority")
	dedupe := fs.String("dedupe-key", "", "dedupe key")
	metadataFile := fs.String("metadata", "", "metadata JSON file")
	links := multiFlag{}
	tags := multiFlag{}
	attachments := multiFlag{}
	fs.Var(&links, "link", "link as label=url or label=/path")
	fs.Var(&tags, "tag", "tag to attach; repeatable")
	fs.Var(&attachments, "attach", "file to attach; repeatable")
	jsonOut := fs.Bool("json", false, "print JSON")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *title == "" {
		return fmt.Errorf("--title is required")
	}
	bodyText := *body
	if *bodyFile != "" {
		b, err := os.ReadFile(*bodyFile)
		if err != nil {
			return err
		}
		bodyText = string(b)
	}
	schema := model.DefaultSchema()
	if *schemaFile != "" {
		b, err := os.ReadFile(*schemaFile)
		if err != nil {
			return err
		}
		if !json.Valid(b) {
			return fmt.Errorf("schema file is not valid JSON")
		}
		schema = b
	}
	metadata := model.DefaultMetadata()
	if *metadataFile != "" {
		b, err := os.ReadFile(*metadataFile)
		if err != nil {
			return err
		}
		if !json.Valid(b) {
			return fmt.Errorf("metadata file is not valid JSON")
		}
		metadata = b
	}
	createAttachments, err := readAttachmentFiles(attachments)
	if err != nil {
		return err
	}
	cfg, _, err := config.Ensure()
	if err != nil {
		return err
	}
	st, err := store.Open(cfg.DataPath)
	if err != nil {
		return err
	}
	defer st.Close()
	req, err := st.CreateRequest(context.Background(), model.CreateRequest{
		Source:      *source,
		Kind:        *kind,
		Title:       *title,
		Body:        bodyText,
		Schema:      schema,
		Priority:    *priority,
		Tags:        parseTags(tags),
		Links:       parseLinks(links),
		Attachments: createAttachments,
		Metadata:    metadata,
		DedupeKey:   *dedupe,
	})
	if err != nil {
		return err
	}
	if *jsonOut {
		return printJSON(req)
	}
	fmt.Println(req.ID)
	return nil
}

func cmdMessage(args []string) error {
	fs := flag.NewFlagSet("message", flag.ContinueOnError)
	title := fs.String("title", "", "message title")
	body := fs.String("body", "", "message body")
	bodyFile := fs.String("body-file", "", "file to read message body from")
	tags := multiFlag{}
	attachments := multiFlag{}
	fs.Var(&tags, "tag", "tag to attach; repeatable")
	fs.Var(&attachments, "attach", "file to attach; repeatable")
	jsonOut := fs.Bool("json", false, "print JSON")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *title == "" {
		return fmt.Errorf("--title is required")
	}
	bodyText := *body
	if *bodyFile != "" {
		b, err := os.ReadFile(*bodyFile)
		if err != nil {
			return err
		}
		bodyText = string(b)
	}
	createAttachments, err := readAttachmentFiles(attachments)
	if err != nil {
		return err
	}
	cfg, _, err := config.Ensure()
	if err != nil {
		return err
	}
	st, err := store.Open(cfg.DataPath)
	if err != nil {
		return err
	}
	defer st.Close()
	req, err := st.CreateRequest(context.Background(), model.CreateRequest{
		Source:      "callum",
		Kind:        model.KindMessage,
		Title:       *title,
		Body:        bodyText,
		Schema:      model.DefaultSchema(),
		Priority:    "normal",
		Tags:        parseTags(tags),
		Attachments: createAttachments,
	})
	if err != nil {
		return err
	}
	if *jsonOut {
		return printJSON(req)
	}
	fmt.Println(req.ID)
	return nil
}

func cmdInbox(args []string) error {
	fs := flag.NewFlagSet("inbox", flag.ContinueOnError)
	status := fs.String("status", model.StatusPending, "request status, or all")
	limit := fs.Int("limit", 50, "maximum requests")
	jsonOut := fs.Bool("json", false, "print JSON")
	if err := fs.Parse(args); err != nil {
		return err
	}
	st, err := openStore()
	if err != nil {
		return err
	}
	defer st.Close()
	requests, err := st.ListRequests(context.Background(), *status, *limit)
	if err != nil {
		return err
	}
	if *jsonOut {
		return printJSON(map[string]any{"requests": requests})
	}
	for _, req := range requests {
		fmt.Printf("%s  %-8s %-10s %s  %s\n", req.ID, req.Status, req.Source, req.CreatedAt.Local().Format("2006-01-02 15:04"), req.Title)
	}
	return nil
}

func cmdShow(args []string) error {
	fs := flag.NewFlagSet("show", flag.ContinueOnError)
	jsonOut := fs.Bool("json", false, "print JSON")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return fmt.Errorf("usage: pickle show <request-id>")
	}
	req, err := getRequest(fs.Arg(0))
	if err != nil {
		return err
	}
	if *jsonOut {
		return printJSON(req)
	}
	fmt.Printf("%s\n%s\n\n", req.ID, req.Title)
	fmt.Printf("source: %s\nkind: %s\nstatus: %s\npriority: %s\ncreated: %s\n", req.Source, req.Kind, req.Status, req.Priority, req.CreatedAt.Local().Format(time.RFC3339))
	if req.Body != "" {
		fmt.Printf("\n%s\n", req.Body)
	}
	if len(req.Attachments) > 0 {
		fmt.Println("\nattachments:")
		for _, attachment := range req.Attachments {
			fmt.Printf("- %s  %s  %d bytes  %s\n", attachment.ID, attachment.Filename, attachment.SizeBytes, attachment.ContentType)
		}
	}
	if req.Response != nil {
		fmt.Printf("\nresponse by %s at %s:\n%s\n", req.Response.Responder, req.Response.CreatedAt.Local().Format(time.RFC3339), req.Response.Payload)
	}
	return nil
}

func cmdRespond(args []string) error {
	fs := flag.NewFlagSet("respond", flag.ContinueOnError)
	responder := fs.String("responder", "callum", "responder name")
	payload := fs.String("json", "", "response JSON")
	payloadFile := fs.String("json-file", "", "response JSON file")
	jsonOut := fs.Bool("out-json", false, "print request JSON")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return fmt.Errorf("usage: pickle respond <request-id> --json '{...}'")
	}
	raw := []byte(*payload)
	if *payloadFile != "" {
		b, err := os.ReadFile(*payloadFile)
		if err != nil {
			return err
		}
		raw = b
	}
	if len(raw) == 0 {
		return fmt.Errorf("--json or --json-file is required")
	}
	st, err := openStore()
	if err != nil {
		return err
	}
	defer st.Close()
	req, err := st.Respond(context.Background(), fs.Arg(0), model.CreateResponse{
		Responder: *responder,
		Payload:   raw,
	})
	if err != nil {
		return err
	}
	if *jsonOut {
		return printJSON(req)
	}
	fmt.Println("answered", req.ID)
	return nil
}

func cmdResponse(args []string) error {
	fs := flag.NewFlagSet("response", flag.ContinueOnError)
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return fmt.Errorf("usage: pickle response <request-id>")
	}
	req, err := getRequest(fs.Arg(0))
	if err != nil {
		return err
	}
	if req.Response == nil {
		return fmt.Errorf("request %s has no response", req.ID)
	}
	fmt.Println(string(req.Response.Payload))
	return nil
}

func cmdWait(args []string) error {
	fs := flag.NewFlagSet("wait", flag.ContinueOnError)
	timeout := fs.Duration("timeout", 0, "timeout, for example 10m")
	poll := fs.Duration("poll", time.Second, "poll interval")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return fmt.Errorf("usage: pickle wait <request-id>")
	}
	ctx := context.Background()
	if *timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, *timeout)
		defer cancel()
	}
	ticker := time.NewTicker(*poll)
	defer ticker.Stop()
	for {
		req, err := getRequest(fs.Arg(0))
		if err == nil && req.Response != nil {
			fmt.Println(string(req.Response.Payload))
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

func cmdEvents(args []string) error {
	fs := flag.NewFlagSet("events", flag.ContinueOnError)
	after := fs.Int64("after", 0, "event id to start after")
	limit := fs.Int("limit", 50, "maximum events")
	if err := fs.Parse(args); err != nil {
		return err
	}
	st, err := openStore()
	if err != nil {
		return err
	}
	defer st.Close()
	events, err := st.ListEventsAfter(context.Background(), *after, *limit)
	if err != nil {
		return err
	}
	return printJSON(map[string]any{"events": events})
}

func cmdWatch(args []string) error {
	fs := flag.NewFlagSet("watch", flag.ContinueOnError)
	apiURL := fs.String("api-url", "", "API URL")
	if err := fs.Parse(args); err != nil {
		return err
	}
	cfg, _, err := config.Ensure()
	if err != nil {
		return err
	}
	rawURL := cfg.APIURL
	if *apiURL != "" {
		rawURL = *apiURL
	}
	wsURL := server.URLToWebSocket(rawURL)
	header := http.Header{}
	header.Set("Authorization", "Bearer "+cfg.Token)
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		return err
	}
	defer conn.Close()
	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		}
		fmt.Println(string(msg))
	}
}

func cmdToken(args []string) error {
	fs := flag.NewFlagSet("token", flag.ContinueOnError)
	if err := fs.Parse(args); err != nil {
		return err
	}
	cfg, _, err := config.Ensure()
	if err != nil {
		return err
	}
	fmt.Println(cfg.Token)
	return nil
}

func openStore() (*store.Store, error) {
	cfg, _, err := config.Ensure()
	if err != nil {
		return nil, err
	}
	return store.Open(cfg.DataPath)
}

func getRequest(id string) (model.Request, error) {
	st, err := openStore()
	if err != nil {
		return model.Request{}, err
	}
	defer st.Close()
	req, err := st.GetRequest(context.Background(), id)
	if errors.Is(err, store.ErrNotFound) {
		return model.Request{}, fmt.Errorf("request %s not found", id)
	}
	return req, err
}

type multiFlag []string

func (m *multiFlag) String() string {
	return strings.Join(*m, ",")
}

func (m *multiFlag) Set(v string) error {
	*m = append(*m, v)
	return nil
}

func parseLinks(values []string) []model.Link {
	var links []model.Link
	for _, value := range values {
		label, target, ok := strings.Cut(value, "=")
		if !ok {
			label = value
			target = value
		}
		link := model.Link{Label: strings.TrimSpace(label)}
		target = strings.TrimSpace(target)
		if strings.HasPrefix(target, "http://") || strings.HasPrefix(target, "https://") {
			link.URL = target
		} else {
			link.Path = target
		}
		links = append(links, link)
	}
	return links
}

func parseTags(values []string) []string {
	tags := make([]string, 0, len(values))
	seen := map[string]bool{}
	for _, raw := range values {
		for _, part := range strings.Split(raw, ",") {
			tag := strings.TrimSpace(strings.TrimPrefix(part, "#"))
			if tag == "" || seen[tag] {
				continue
			}
			seen[tag] = true
			tags = append(tags, tag)
		}
	}
	return tags
}

func readAttachmentFiles(paths []string) ([]model.CreateAttachment, error) {
	if len(paths) == 0 {
		return nil, nil
	}
	attachments := make([]model.CreateAttachment, 0, len(paths))
	for _, rawPath := range paths {
		path := filepath.Clean(strings.TrimSpace(rawPath))
		if path == "" || path == "." {
			return nil, fmt.Errorf("attachment path is required")
		}
		info, err := os.Stat(path)
		if err != nil {
			return nil, err
		}
		if info.IsDir() {
			return nil, fmt.Errorf("attachment is a directory: %s", path)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		attachments = append(attachments, model.CreateAttachment{
			Filename:    filepath.Base(path),
			ContentType: detectAttachmentType(path, data),
			Data:        data,
		})
	}
	return attachments, nil
}

func detectAttachmentType(path string, data []byte) string {
	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".md", ".markdown":
		return "text/markdown"
	case ".txt", ".log":
		return "text/plain"
	case ".webp":
		return "image/webp"
	}
	if typ := mime.TypeByExtension(ext); typ != "" {
		return strings.Split(typ, ";")[0]
	}
	return strings.Split(http.DetectContentType(data), ";")[0]
}

func printJSON(v any) error {
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	return enc.Encode(v)
}

func usage(w io.Writer) {
	fmt.Fprintln(w, `Pickle is a personal agent inbox for structured automation handoffs.

Usage:
  pickle init [--api-url URL] [--data PATH]
  pickle serve [--listen HOST:PORT]
  pickle ask --title TITLE [--body TEXT] [--schema FILE] [--attach FILE]
  pickle message --title TITLE [--body TEXT] [--tag TAG] [--attach FILE]
  pickle inbox [--status pending|answered|all]
  pickle show <request-id>
  pickle respond <request-id> --json '{"decision":"approve"}'
  pickle response <request-id>
  pickle wait <request-id> [--timeout 10m]
  pickle events [--after ID]
  pickle watch
  pickle token`)
}
