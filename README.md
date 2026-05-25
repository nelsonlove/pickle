# Pickle

Pickle is a local human inbox for agents and automations. It stores requests,
attachments, and human responses in an mdbase collection so the markdown files
are the source of truth.

Agents and Tickle jobs create typed requests with `pickle ask` or
`pickle message`. A local Rust server exposes the same collection over an
authenticated HTTP/WebSocket API for private-network clients such as the Android
app.

## Data Model

Each Pickle collection is an mdbase collection with:

- `_types/pickle_request.md`
- `_types/pickle_response_approval.md`
- `_types/pickle_response_ack.md`
- `requests/*.md`
- `responses/*.md`
- `attachments/<request-id>/*`

Request state is derived from response links. A request with no linked response
is `pending`, one linked response is `answered`, and multiple linked responses
is `conflict`. The legacy `status` field is only used for explicit
`cancelled`; it is not the source of truth for answered state.

`message` is the short human-facing request text. The markdown body can hold
longer context.

## Quick Start

```bash
cargo test
cargo build
cargo run -- init
cargo run -- serve --listen 0.0.0.0:8787
```

Create requests:

```bash
pickle ask \
  --source tasknotes-ops \
  --kind approval \
  --title "Close TaskNotes issue #1530?" \
  --message "Approve closing this issue as wontfix?" \
  --body "Agent recommends closing after checking the linked sidecar." \
  --attach context.md \
  --tag ops \
  --tag tasknotes

pickle message \
  --title "Look at the fresh ops sidecar" \
  --message "No approval needed yet; leaving this for the next agent." \
  --attach screenshot.png \
  --tag ops
```

Respond and read the result:

```bash
pickle inbox --status pending
pickle show req_xxx
pickle respond req_xxx --json '{"decision":"approve","comment":"Looks right."}'
pickle response req_xxx
```

## Collections

The config file lives at `~/.config/pickle/config.json`. It can point at
multiple named mdbase collections:

```json
{
  "default_collection": "tasknotes",
  "collections": {
    "tasknotes": { "path": "/home/calluma/projects/tasknotes/.ops/_pickle" },
    "default": { "path": "/home/calluma/.local/share/pickle/collection" }
  },
  "api_url": "http://127.0.0.1:8787",
  "token": "..."
}
```

Useful commands:

```bash
pickle collections list
pickle collections add tasknotes ~/projects/tasknotes/.ops/_pickle --set-default
pickle collections use tasknotes
pickle --collection tasknotes inbox --status all
```

The HTTP API selects collections with `?collection=<name>` or the
`X-Pickle-Collection` header. Without either, it uses `default_collection`.

## SQLite Migration

The old SQLite store is import-only now:

```bash
pickle migrate-sqlite \
  --sqlite ~/.local/share/pickle/pickle.sqlite \
  --collection-name tasknotes \
  --collection-path ~/projects/tasknotes/.ops/_pickle \
  --set-default
```

Migration preserves legacy SQLite status under `metadata.legacy_state` and
creates response files linked back to request files. It does not mark requests
answered by mutating request frontmatter.

## API

Authenticated routes:

- `GET /api/v1/collections`
- `GET /api/v1/inbox?status=pending&collection=tasknotes`
- `POST /api/v1/requests`
- `GET /api/v1/requests/{id}`
- `POST /api/v1/requests/{id}/responses`
- `GET /api/v1/requests/{id}/attachments/{attachment_id}`
- `GET /api/v1/types`
- `GET /api/v1/types/{name}`
- `GET /api/v1/events`
- `GET /api/v1/stream`

`GET /api/v1/types` exposes mdbase type definitions directly so clients can
render response forms from the collection schema.

## Android

The Android client is maintained in the sibling `pickle-android` repository. It
uses the same HTTP API, reads each request's `response_type_definition`, and
keeps a foreground WebSocket service open for local notifications over
Tailscale.

For emulator testing, use:

```text
http://10.0.2.2:8787
```

For a phone on Tailscale, use the machine's Tailnet name or Tailscale IP and
the value from:

```bash
pickle token
```

## Verification

```bash
cargo fmt --check
cargo test
scripts/smoke.sh
```

The smoke test builds the Rust CLI, creates an isolated mdbase collection,
starts the HTTP server, verifies collection selection, creates an approval with
an attachment, responds to it, and checks that request state is link-derived.

## Security

`pickle init` creates a bearer token in `~/.config/pickle/config.json`. Every
`/api/*` route requires that token. If you bind beyond localhost, keep the
listener behind Tailscale or another private network boundary.
