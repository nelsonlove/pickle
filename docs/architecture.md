# Pickle Architecture

## Core Decision

Pickle is an mdbase-backed request/response inbox. The mdbase files are the
canonical store; the server, CLI, Android client, and automations are views and
writers over those files.

Tickle owns scheduling and job execution. Agents create Pickle requests when
they need a human decision; Tickle jobs later read linked response files and
continue.

## Components

- `src/main.rs`: Rust CLI and daemon entrypoint.
- `src/collection.rs`: mdbase collection adapter and request-state derivation.
- `src/server.rs`: authenticated HTTP API plus WebSocket event stream.
- `src/templates.rs`: built-in mdbase collection/type templates.
- `src/legacy_sqlite.rs`: one-way SQLite migration importer.
- `../pickle-android`: native Android client and foreground realtime
  notification service.
- `../pickle-obsidian`: Obsidian plugin for browsing/responding through Bases.

## Collection Contract

A collection contains `pickle_request` files and response files. Responses link
to requests through their `request` field. That link is the lifecycle boundary:

1. No linked response means `pending`.
2. One linked response means `answered`.
3. More than one linked response means `conflict`.
4. `status: cancelled` on the request still means `cancelled`.

The request `status` field is retained only as a legacy/cancel marker. Writers
must not set `status: answered`.

## Request Lifecycle

1. An agent runs `pickle ask` or an automation posts to `/api/v1/requests`.
2. Pickle writes one `requests/*.md` file and captures attachments under
   `attachments/<request-id>/`.
3. The daemon exposes the request through `/api/v1/inbox`, `/api/v1/events`, and
   `/api/v1/stream`.
4. Android or Obsidian renders the response form from the request's
   `response_type_definition`.
5. The user submits a structured response.
6. Pickle writes or updates one linked `responses/*.md` file.
7. A later automation calls `pickle response <id>` or `pickle wait <id>`.

## Multi-Collection Selection

`~/.config/pickle/config.json` maps collection names to mdbase roots and stores
the default collection. The CLI accepts global `--collection <name-or-path>`.
The HTTP API accepts `?collection=<name>` or `X-Pickle-Collection`.

This lets one server manage a personal default collection, a TaskNotes
`.ops/_pickle` collection, and any future project-specific collections without
copying daemons.

## Attachments

Attachments are request-scoped captured artifacts. The request frontmatter stores
relative `attachment_paths`; file bytes live inside the mdbase collection.
Clients download them through authenticated lazy-download endpoints.

## Event Stream

The event stream is derived from the current mdbase state rather than a separate
event table. Each request emits `request.created`; each linked response emits
`request.answered`. Event ids are stable for a given sorted snapshot and suitable
for client catch-up polling.

## Schema Surface

Response schemas are mdbase type definitions, not JSON Schema. Clients can fetch
`/api/v1/types` or inspect the `response_type_definition` embedded in each
request. Supported field shapes follow mdbase definitions: strings, enums,
booleans, lists, objects, links, datetimes, defaults, generated fields, and
required markers.
