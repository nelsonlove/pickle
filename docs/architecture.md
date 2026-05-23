# Pickle Architecture

## Core Decision

Pickle owns human interaction state. Tickle owns scheduling and job execution.
Agents create Pickle requests when they need a human response; jobs later read
that response and continue.

## Components

- `cmd/pickle`: one binary with CLI and daemon subcommands.
- `internal/store`: SQLite persistence and event log.
- `internal/server`: authenticated HTTP API plus WebSocket event stream.
- `apps/android`: native Android client and foreground realtime notification
  service.

## Request Lifecycle

1. An agent runs `pickle ask`.
2. Pickle captures any request attachments, creates a `pending` request, and
   appends `request.created`.
3. The daemon polls the event log and broadcasts the event to connected clients.
4. Android receives the event, refreshes inbox data, and posts a notification.
5. The user submits a structured response.
6. Pickle records one response, marks the request `answered`, and appends
   `request.answered`.
7. A later automation calls `pickle response <id>` or `pickle wait <id>`.

## Push Strategy

The first-class push path is a foreground Android service that maintains a
WebSocket over Tailscale. It avoids a public relay and keeps private request
details on the local machine.

FCM can be added as a second provider later. The daemon event log and device
registration boundary are intentionally provider-neutral.

## Data Model

Requests are durable work items. Events are append-only notifications that
describe state changes. Responses are immutable once written so downstream
automation has a stable input.

Attachments are request-scoped captured artifacts. SQLite stores attachment
metadata and hashes; file bytes live under the Pickle data directory and are
served through authenticated lazy-download endpoints. Events include attachment
metadata only, never file bytes.

Structured response schemas use a small JSON Schema subset that native clients
can render predictably: enums, booleans, strings, required object fields, and
arrays with primitive or enum `items`. Array schemas may use `minItems`.
