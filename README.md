# Pickle

Pickle is a personal agent inbox for structured requests, approvals, annotations,
and automation handoffs.

Agents and scripts create requests through the `pickle` CLI. A local daemon
stores them in SQLite, exposes a Tailscale-friendly HTTP API, and streams new
events to the Android app. The Android app has separate Inbox, Detail, Post, and
Settings pages, and runs a foreground realtime service so new requests can
produce phone notifications without a public relay.

## Shape

```text
agent / tickle job
        -> pickle ask
human / phone
        -> pickle message
        -> ~/.local/share/pickle/pickle.sqlite
        -> pickled HTTP + WebSocket over Tailscale
        -> Android inbox + notifications
        -> structured response
        -> pickle wait / pickle response / tickle trigger
```

## Backend Quick Start

```bash
go test ./...
go run ./cmd/pickle init
go run ./cmd/pickle serve --listen 0.0.0.0:8787
```

In another terminal:

```bash
go run ./cmd/pickle ask \
  --source tasknotes-ops \
  --kind approval \
  --title "Close TaskNotes issue #1530?" \
  --body "Agent recommends closing this as wontfix." \
  --attach context.md \
  --tag ops \
  --tag tasknotes \
  --schema testdata/approval.schema.json

go run ./cmd/pickle message \
  --title "Look at the fresh ops sidecar" \
  --body "No approval needed yet; just leaving this for the next agent." \
  --attach screenshot.png \
  --tag ops \
  --tag note

go run ./cmd/pickle inbox
go run ./cmd/pickle respond req_xxx --json '{"decision":"approve","comment":"Looks right."}'
```

## Attachments

Requests can capture small review artifacts at creation time:

```bash
pickle ask \
  --title "Review generated output?" \
  --body "Please inspect the attached markdown summary and screenshot." \
  --attach summary.md \
  --attach screenshot.png
```

Supported attachment types are plaintext, Markdown, PNG, JPEG, and WebP. Pickle
stores attachment metadata in SQLite and stores the file bytes under the Pickle
data directory. Android downloads attachments lazily from authenticated request
attachment endpoints and previews Markdown/plaintext inline plus images as
visual previews.

For a real phone over Tailscale, keep `pickled` bound to a private interface or
to all interfaces behind Tailscale:

```bash
pickle serve --listen 0.0.0.0:8787
```

Then enter the Tailnet URL and `pickle token` value in the Android app.

## Android

The Android project lives in `apps/android`. Configure the server URL and token
inside the app settings. When testing on an emulator, use:

```text
http://10.0.2.2:8787
```

On a real phone over Tailscale, use the machine's Tailnet name or Tailscale IP:

```text
http://<machine-tailnet-name>:8787
```

The realtime service keeps a WebSocket open and posts local notifications for
new pending requests. This is the default push path because it works over
Tailscale without Firebase credentials or a public server.

For emulator smoke tests, the activity also accepts configuration extras:

```bash
adb shell am start \
  -n com.callumalpass.pickle/com.callumalpass.pickle.MainActivity \
  --es pickle_server_url http://10.0.2.2:8787 \
  --es pickle_token "$(pickle token)"
```

The app has a foreground `Push` service. Android shows a persistent low-priority
connection notification plus high-priority request notifications. Push enabled
state is persisted, the service is restarted after device boot or app update,
and the service catches up from the server event log before resuming the live
WebSocket so requests created while the phone was asleep or disconnected are not
silently missed.

## Verification

Backend:

```bash
go test ./...
scripts/smoke.sh
```

Android:

```bash
cd apps/android
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew connectedDebugAndroidTest
```

The smoke suite was run against a headless Android 36 `medium_phone` emulator.
The notification path was verified through `dumpsys notification` after creating
a Pickle request from the host CLI.

## Security

`pickle init` creates a bearer token in `~/.config/pickle/config.json`. The HTTP
API requires that token for every `/api/*` route. If you bind beyond localhost,
use Tailscale or another private network boundary.

## Status

This repo is an end-to-end first implementation. The core store, CLI, daemon,
HTTP API, realtime stream, Android client, and smoke fixtures are designed to be
small enough to audit and extend.
