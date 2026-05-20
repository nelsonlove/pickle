# Wickle Architecture

## Core Decision

Wickle owns human interaction state. Tickle owns scheduling and job execution.
Agents create Wickle requests when they need a human response; jobs later read
that response and continue.

## Components

- `cmd/wickle`: one binary with CLI and daemon subcommands.
- `internal/store`: SQLite persistence and event log.
- `internal/server`: authenticated HTTP API plus WebSocket event stream.
- `apps/android`: native Android client and foreground realtime notification
  service.

## Request Lifecycle

1. An agent runs `wickle ask`.
2. Wickle creates a `pending` request and appends `request.created`.
3. The daemon polls the event log and broadcasts the event to connected clients.
4. Android receives the event, refreshes inbox data, and posts a notification.
5. The user submits a structured response.
6. Wickle records one response, marks the request `answered`, and appends
   `request.answered`.
7. A later automation calls `wickle response <id>` or `wickle wait <id>`.

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

Structured response schemas use a small JSON Schema subset that native clients
can render predictably: enums, booleans, strings, and required object fields.
