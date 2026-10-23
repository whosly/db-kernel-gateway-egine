# MySQL first-connect identity routing (2026-10-23)

## Problem

MySQL is server-first: Initial Handshake (with scramble) arrives from the backend
before the client Handshake Response carries username/database. PostgreSQL can
peek StartupMessage and `acquire(RoutingContext)` before connect; MySQL cannot
without either forging a greeting or connecting first.

Forging Initial Handshake violates AGENTS transparency rule 5.

## Chosen approach: deferred AuthSwitch rebind

When `gateway.routing` has rules:

1. `acquire(empty)` → fallback/default backend (unchanged bootstrap).
2. Forward that backend's **real** greeting to the client (never forged).
3. Buffer the client Handshake Response; parse `RoutingContext`.
4. `RoutingBackendProvider.selectProvider(context)`:
   - **Same as fallback** → replay HR to current backend; fully transparent.
   - **Different provider** → hold HR; close bootstrap; `acquire(context)` on the
     matched provider; read its real greeting; send **AuthSwitchRequest** to the
     client with that scramble/plugin; rebuild HR from AuthSwitchResponse; complete
     auth with sequence fixup; then transparent `DuplexRelay`.

### Tradeoffs

| Path | Transparency | Notes |
|---|---|---|
| Identity matches fallback | Full | Bytes from real backends only; HR replayed |
| Identity needs other backend | Limited auth mediation | AuthSwitch synthesized; Initial Handshake still from real MySQL; post-OK duplex is transparent |
| Client SSLRequest mid-handshake | Stay on fallback | Cannot see identity before TLS; document |
| No routing rules / routing off | Unchanged | No deferred phase |

### Rejected alternatives

- **Forge greeting / full terminate-auth**: violates rule 5; bigger surface.
- **Sticky IP + forced reconnect**: no forge, poor UX, NAT collisions.
- **Fail-closed ERR on mismatch only**: does not route; only denies.

## Config

No new flag: activates whenever MySQL adapter's `BackendProvider` is a
`RoutingBackendProvider` with rules (same as PG peek path).

## Status

Implemented on branch `future/database-wire-protocol-foundation` (`MySqlDeferredIdentityRouter` +
codec/handshake helpers; adapter runs deferred only when `RoutingBackendProvider` has rules).
Unit coverage: `MySqlDeferredIdentityRouterTest`, `MySqlHandshakeResponseCodecTest`,
`MySqlInitialHandshakeTest`, `MySqlAuthSwitchTest`.
