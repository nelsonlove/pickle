#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
SERVER_PID=""

cleanup() {
  if [[ -n "${SERVER_PID}" ]]; then
    kill "${SERVER_PID}" >/dev/null 2>&1 || true
    wait "${SERVER_PID}" >/dev/null 2>&1 || true
  fi
  rm -rf "${TMP}"
}
trap cleanup EXIT

export WICKLE_CONFIG_HOME="${TMP}/config"
export WICKLE_DATA_HOME="${TMP}/data"

BIN="${TMP}/wickle"
go build -o "${BIN}" "${ROOT}/cmd/wickle"

"${BIN}" init --api-url "http://127.0.0.1:18787" >/dev/null
"${BIN}" serve --listen "127.0.0.1:18787" >"${TMP}/server.log" 2>&1 &
SERVER_PID="$!"

for _ in {1..50}; do
  if curl -fsS "http://127.0.0.1:18787/health" >/dev/null; then
    break
  fi
  sleep 0.1
done
curl -fsS "http://127.0.0.1:18787/health" >/dev/null

WATCH_LOG="${TMP}/watch.log"
"${BIN}" watch >"${WATCH_LOG}" 2>&1 &
WATCH_PID="$!"
sleep 0.2

REQ_ID="$("${BIN}" ask \
  --source tasknotes-ops \
  --kind approval \
  --title "Smoke approval" \
  --body "Approve the smoke test." \
  --schema "${ROOT}/testdata/approval.schema.json")"

for _ in {1..30}; do
  if grep -q "request.created" "${WATCH_LOG}"; then
    break
  fi
  sleep 0.2
done
grep -q "request.created" "${WATCH_LOG}"
kill "${WATCH_PID}" >/dev/null 2>&1 || true
wait "${WATCH_PID}" >/dev/null 2>&1 || true

"${BIN}" inbox --json | grep -q "${REQ_ID}"
"${BIN}" show --json "${REQ_ID}" | grep -q "Smoke approval"
"${BIN}" respond --json '{"decision":"approve","comment":"Smoke passed."}' "${REQ_ID}" >/dev/null
"${BIN}" response "${REQ_ID}" | grep -q '"decision":"approve"'
"${BIN}" events | grep -q "request.answered"

MSG_ID="$("${BIN}" message \
  --title "Smoke note" \
  --body "A human-authored note from Android/CLI." \
  --tag ops \
  --tag "#follow-up" \
  --json | jq -r '.id')"
"${BIN}" show --json "${MSG_ID}" | jq -e '.kind == "message" and (.tags == ["ops", "follow-up"])' >/dev/null

echo "wickle smoke passed: ${REQ_ID}"
