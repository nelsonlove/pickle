#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
SERVER_PID=""
WATCH_PID=""

cleanup() {
  if [[ -n "${WATCH_PID}" ]]; then
    kill "${WATCH_PID}" >/dev/null 2>&1 || true
    wait "${WATCH_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${SERVER_PID}" ]]; then
    kill "${SERVER_PID}" >/dev/null 2>&1 || true
    wait "${SERVER_PID}" >/dev/null 2>&1 || true
  fi
  rm -rf "${TMP}"
}
trap cleanup EXIT

export PICKLE_CONFIG_HOME="${TMP}/config"
export PICKLE_DATA_HOME="${TMP}/data"

cargo build --manifest-path "${ROOT}/Cargo.toml" >/dev/null
BIN="${ROOT}/target/debug/pickle"

"${BIN}" init \
  --api-url "http://127.0.0.1:18787" \
  --collection-name alpha \
  --collection-path "${TMP}/alpha" \
  --set-default >/dev/null
"${BIN}" collections add beta "${TMP}/beta" >/dev/null

"${BIN}" serve --listen "127.0.0.1:18787" >"${TMP}/server.log" 2>&1 &
SERVER_PID="$!"

for _ in {1..50}; do
  if curl -fsS "http://127.0.0.1:18787/health" >/dev/null; then
    break
  fi
  sleep 0.1
done
curl -fsS "http://127.0.0.1:18787/health" >/dev/null

TOKEN="$("${BIN}" token)"

printf '# Smoke attachment\n\nReview this markdown file.\n' >"${TMP}/context.md"

WATCH_LOG="${TMP}/watch.log"
"${BIN}" watch >"${WATCH_LOG}" 2>&1 &
WATCH_PID="$!"
sleep 0.2

REQ_ID="$("${BIN}" ask \
  --source tasknotes-ops \
  --kind approval \
  --title "Smoke approval" \
  --message "Approve the smoke test." \
  --body "Approve the smoke test after reading the attachment." \
  --attach "${TMP}/context.md" \
  --tag ops \
  --tag tasknotes)"

for _ in {1..30}; do
  if grep -q "request.created" "${WATCH_LOG}"; then
    break
  fi
  sleep 0.2
done
grep -q "request.created" "${WATCH_LOG}"

"${BIN}" inbox --json | jq -e --arg id "${REQ_ID}" '.requests[] | select(.id == $id and .state == "pending")' >/dev/null
"${BIN}" show --json "${REQ_ID}" | jq -e '
  .title == "Smoke approval"
  and .message == "Approve the smoke test."
  and .response_type == "pickle_response_approval"
  and .response_type_definition.name == "pickle_response_approval"
  and (.attachments | length == 1)
  and .attachments[0].content_type == "text/markdown"
' >/dev/null

"${BIN}" respond --json '{"decision":"approve","comment":"Smoke passed."}' "${REQ_ID}" >/dev/null
"${BIN}" response "${REQ_ID}" | jq -e '.decision == "approve"' >/dev/null
"${BIN}" show --json "${REQ_ID}" | jq -e '.state == "answered" and .status == "answered" and .response_count == 1' >/dev/null
if grep -R '^status: answered' "${TMP}/alpha/requests" >/dev/null; then
  echo "request frontmatter must not store status: answered" >&2
  exit 1
fi

MSG_ID="$("${BIN}" message \
  --title "Smoke note" \
  --message "A human-authored note from Android/CLI." \
  --tag ops \
  --tag "#follow-up" \
  --json | jq -r '.id')"
"${BIN}" show --json "${MSG_ID}" | jq -e '.kind == "message" and .response_type == "pickle_response_ack" and (.tags == ["ops", "follow-up"])' >/dev/null

BETA_ID="$("${BIN}" --collection beta ask \
  --source smoke \
  --title "Beta approval" \
  --message "Beta collection isolation")"
"${BIN}" --collection beta inbox --json | jq -e --arg id "${BETA_ID}" '.requests[] | select(.id == $id)' >/dev/null
if "${BIN}" inbox --status all --json | jq -e --arg id "${BETA_ID}" '.requests[] | select(.id == $id)' >/dev/null; then
  echo "beta request leaked into default collection" >&2
  exit 1
fi

curl -fsS \
  -H "Authorization: Bearer ${TOKEN}" \
  "http://127.0.0.1:18787/api/v1/types?collection=alpha" \
  | jq -e '.types[] | select(.name == "pickle_response_approval")' >/dev/null
curl -fsS \
  -H "Authorization: Bearer ${TOKEN}" \
  "http://127.0.0.1:18787/api/v1/inbox?collection=beta&status=pending" \
  | jq -e --arg id "${BETA_ID}" '.requests[] | select(.id == $id)' >/dev/null

echo "pickle smoke passed: ${REQ_ID}"
