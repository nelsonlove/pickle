# Wickle Product Context

## Register

product

## Users

Developers and coding-agent users who run local automations and need a durable
way for those agents to ask for human decisions. The first user is Callum, using
Tickle jobs, repo-local ops files, Tailscale, and an Android phone.

## Purpose

Wickle is a personal agent inbox for structured requests, approvals, annotations,
and automation handoffs. Agents file a request, the user responds asynchronously,
and later automation can continue from the recorded response.

Wickle is not a chat app, task manager, or generic message queue. It is the
human checkpoint in a local automation system.

## Product Principles

- Human responses are durable automation inputs, not ephemeral notifications.
- The local machine remains the system of record.
- Android is a thin private client over Tailscale.
- Push should work without a public server when possible.
- Schemas should be simple enough for agents to emit and clients to render.
- Every request should be inspectable as JSON and through the UI.

## Tone

Small, precise, grounded, and calm. Wickle should feel like a quiet operations
desk: pending decisions are obvious, context is easy to scan, and approval
actions are deliberate.

## Anti-References

Avoid enterprise workflow suites, generic SaaS inboxes, noisy chat timelines,
mascot-heavy productivity tools, decorative dashboards, and opaque automation
platforms.
