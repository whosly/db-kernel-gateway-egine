# Repository Rules

This repository implements a multi-protocol database gateway engine.

Before changing database protocol code, read and follow:

- `docs/rules/database-protocol-rules.md`
- `docs/rules/ai-error-handling-rules.md`

Key rules:

- New project branches must be named after the actual feature or workstream.
  Use a semantic prefix such as `future/`, followed by a short kebab-case
  feature name, for example `future/database-wire-protocol-foundation`.
  Do not use tool names, personal names, or vague names such as `tmp`, `test`,
  `dev`, or `fix`.
- The rules describe database wire protocols, not the current implementation.
- Start protocol implementation with MySQL Client/Server Protocol and
  PostgreSQL Frontend/Backend Protocol.
- Model each protocol as a state machine: startup, negotiation, authentication,
  ready, executing, streaming, closing, closed.
- Implement protocol-specific frame/message codecs before command handlers.
- Keep MySQL-specific code in `com.whosly.gateway.adapter.mysql`.
- Keep PostgreSQL-specific code in `com.whosly.gateway.adapter.postgresql`.
- Keep shared protocol abstractions in `com.whosly.gateway.adapter.protocol`.
- Do not map unsupported protocols to a different adapter as a placeholder.
- Do not log plaintext credentials or authentication payloads.
- Write focused tests before changing frame, handshake, auth, command, result,
  transaction-state, or error behavior.
- When builds, tests, protocol checks, or user feedback reveal an error, follow
  `docs/rules/ai-error-handling-rules.md`: classify the failure, find the root
  cause, fix one cause at a time, verify, and update rules/tests when the issue
  exposes a process gap.
