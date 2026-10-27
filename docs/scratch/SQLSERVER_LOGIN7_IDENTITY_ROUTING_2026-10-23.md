# SQL Server Login7 identity routing (2026-10-23)

## Problem

TDS is client-first, but the first message is **PreLogin** (no user/database).
Login7 arrives only after a PreLogin round-trip with *some* backend. Pure
`acquire(RoutingContext)` before connect is impossible without forging a
PreLogin response to the client.

## Chosen approach: deferred Login7 rebind (silent backend PreLogin)

Analogous to MySQL deferred AuthSwitch, without forging PreLogin to the client:

When `gateway.routing` has rules:

1. `acquire(empty)` → fallback/default backend (bootstrap).
2. Relay **real** client PreLogin → bootstrap; relay **real** bootstrap PreLogin
   response → client (never forged).
3. If ENCRYPTION will hide Login7 (client ENCRYPT_ON or server ENCRYPT_ON/REQ) —
   stay on bootstrap; empty identity (like MySQL SSLRequest).
4. Buffer Login7; parse `RoutingContext` via `SqlServerLogin7Routing`.
5. `RoutingBackendProvider.selectProvider(context)`:
   - **Same as fallback** → replay Login7 wire bytes; fully transparent.
   - **Different provider** → release bootstrap; `acquire(context)`; silently
     complete PreLogin with the selected backend (discard its response — client
     already has bootstrap's); forward Login7 unchanged; then transparent
     `DuplexRelay`.

### Tradeoffs

| Path | Transparency | Notes |
|---|---|---|
| Identity matches fallback | Full | Real PreLogin both ways; Login7 replayed |
| Identity needs other backend | Limited handshake mediation | Client saw bootstrap PreLogin options; Login7 authenticates against selected backend's silent PreLogin; post-login duplex transparent |
| PreLogin ENCRYPTION | Stay on fallback | Cannot see cleartext Login7 |
| No routing rules / routing off | Unchanged | No deferred phase |

### Rejected alternatives

- **Forge PreLogin to client**: larger surface; earlier docs assumed this was
  required — silent backend re-PreLogin avoids forging to the client.
- **Fail-closed only on mismatch**: does not route.

## Config

No new flag: activates whenever SQL Server adapter's `BackendProvider` is a
`RoutingBackendProvider` with rules (same as MySQL deferred).

## Status

Implemented: `SqlServerDeferredIdentityRouter` + `TdsLogicalMessage` +
`TdsPreLoginOptions`; adapter wiring; unit tests.
