---
name: pickle
description: Use when creating, inspecting, responding to, or integrating Pickle requests. Pickle is a local human-approval inbox for agents and Tickle jobs, with durable SQLite-backed requests, structured JSON responses, Android notifications over Tailscale, and CLI commands such as pickle ask, inbox, show, respond, response, wait, events, and watch.
---

# Pickle

Pickle is a personal agent inbox for structured requests, approvals,
annotations, and automation handoffs. Use it when an agent or local automation
needs a durable human decision instead of guessing or blocking in chat.

## CLI

Prefer the installed CLI:

```bash
pickle --help
```

Common commands:

```bash
pickle ask --source agent --kind approval --title "Approve action?" --body "Question..." --schema approval.schema.json
pickle ask --title "Review output?" --body "Please inspect the attached files." --attach summary.md --attach screenshot.png
pickle inbox --status pending --json
pickle show --json <request-id>
pickle respond --json '{"decision":"approve","comment":"Looks right."}' <request-id>
pickle response <request-id>
pickle events --after 0
pickle token
```

## Approval Pattern

1. Create one Pickle request with a concise title, clear question, relevant
   links or attachments, and metadata pointing back to the calling workflow.
2. Store the Pickle request id in the upstream system of record so later
   automation can find it again.
3. Prefer asynchronous follow-up automation over blocking a long-running job
   with `pickle wait`, unless the user explicitly wants a synchronous checkpoint.
4. When the response arrives, copy the response into the upstream workflow state
   before acting on it.

Use this response shape for ordinary approve/reject/revise requests:

```json
{
  "type": "object",
  "properties": {
    "decision": {
      "type": "string",
      "enum": ["approve", "reject", "revise"]
    },
    "comment": {
      "type": "string"
    }
  },
  "required": ["decision"]
}
```

## Guardrails

- Pickle is not the source of truth for project state. It records human input;
  the repo-local state file, ledger task, issue sidecar, or other workflow store
  should remain canonical. Project-specific policies belong in that workflow's
  prompt or scripts, not in this generic Pickle skill.
- Keep request bodies private and concise. Link to local files rather than
  copying large logs when a path is enough. Attach small plaintext, Markdown,
  PNG, JPEG, or WebP artifacts when the human needs to inspect the exact
  captured output from the phone.
- Use `--dedupe-key` when repeated automation checks could ask the same question
  more than once.
- When exposing Pickle beyond localhost, keep it behind Tailscale or another
  private network boundary.
