# Repository Rules

This repository implements a multi-protocol database gateway engine.

Before changing database protocol code, read and follow:

- `docs/rules/database-protocol-rules.md`: protocol rules.
- `docs/rules/ai-error-handling-rules.md`: how failures must be handled.
- `docs/PROTOCOL_REFERENCE_TABLES.md`: mirror of the code-owned reference tables.

## Scope and layering

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

## Transparency invariants

1. One data path: bytes are always forwarded verbatim. Observation must never
   change, delay, reorder or block what the client and the target exchange.
2. Observation may fail: observation and audit failures are fail-open. An
   observation problem must never fail a client connection. Policy and rewrite
   failures are fail-closed instead: when the gateway cannot decide or cannot
   rewrite, it must not forward something it does not understand.
3. Mutation must fail closed: SQL rewriting and result-set masking are the only
   operations allowed to change bytes, and they fail closed. Never send a
   partially rewritten statement or a partially masked result set. Rewrites run
   after policy enforcement, so a rewrite can never bypass a policy check.
4. No serialization without mutation: a message that was not mutated must be
   written from its original bytes. Never re-serialize for convenience.
5. Never forge handshake, capabilities or authentication, and never advertise a
   capability the gateway does not implement.
6. Observation state carries confidence. State that is not confirmed must not be
   used for routing or connection-pooling decisions.
7. Protocol state has one owner. The two relay directions share one observation
   state machine per connection, so it is driven inside a single per-connection
   monitor. That monitor covers protocol state only: never hold it across audit
   delivery, risk evaluation or any other blocking I/O, or one direction's slow
   downstream would stall the other. Readers get an immutable snapshot taken under
   the same monitor; never read protocol fields unprotected.

## Reference tables have a single source of truth

- Capability flags, status flags, command codes, error-code/SQLSTATE mappings,
  message types and type OIDs are owned by the Java enums under
  `com.whosly.gateway.adapter.*`.
- `docs/PROTOCOL_REFERENCE_TABLES.md` is a mirror, never the source. It must not
  claim a table exists when the implementation does not.

## Security

- Do not log plaintext credentials, authentication payloads, cancel keys, salt,
  tokens, or connection strings that carry credentials.

## Working agreements

- New branches must be named after the actual feature or workstream, using a
  semantic prefix such as `future/` followed by a short kebab-case name, for
  example `future/database-wire-protocol-foundation`. Do not use tool names,
  personal names, or vague names such as `tmp`, `test`, `dev` or `fix`.
- `mvn clean test` must pass before a protocol change is considered done.
- Write focused tests before changing frame, handshake, auth, command, result,
  transaction-state, error or observation behavior.
- When builds, tests, protocol checks or user feedback reveal an error, follow
  `docs/rules/ai-error-handling-rules.md`: classify the failure, find the root
  cause, fix one cause at a time, verify, and update rules or tests when the
  issue exposes a process gap.
