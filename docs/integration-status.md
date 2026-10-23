# Minecraft integration status

> Authoritative implementation/evidence snapshot after Sandbox `8891047`. This is a scoped delivery status, not a runtime, EULA, world, provider, body-binding, permit, input-control, or release approval.

## Evidence ledger

| Milestone | Status | Reviewed scope and evidence | Explicit boundary |
| --- | --- | --- | --- |
| G1 canonical contract | PASS | Canonical contract and bidirectional offline interop were independently accepted | Does not authorize runtime or game integration |
| G2 offline foundation | PASS | Independently accepted offline foundation; platform capability is Windows/Python `3.10.21`/SQLite `3.53.4` `UNAVAILABLE`, `verified=False` | Only disabled-actuation transport development is authorized |
| Phase 3a source + heartbeat loopback | Scoped PASS | Sandbox `90831ec` transport, `c58c0e4` watchdog fix, `b90446a` fresh harness; upstream `2078976a` heartbeat/support. Frozen report `loopback-final-frozen-20261023-091500.json`: 2 roles, 0 failures/errors/skips, SERVER `2487 ms`, CLIENT `2491 ms`, JUnit SHA-256 `1dc53e8c96db9a9e809453b67ac050f671242a75380a7cae7aad3787d5c37430`, unchanged reviewed graph | Not full G3, permit delivery, body binding, Forge enforcement, client input, or game evidence |
| ASGI message-size behavior | PASS | Sandbox `8891047`; report `frame-limit-cleanup-20261023-092806-2280.json` tied to its recorded source hashes and loaded modules: 4096-byte valid HELLO → CHALLENGE; 4097-byte text and fragmented aggregate → WebSocket `1009`; owned process cleanup `terminated` | Verifies Uvicorn WebSocket **message** limit before route receive, not physical frame size, performance, all backend behavior, or production activation |

Historical loopback passes with different test-support hashes remain historical evidence and are not treated as equivalent to the current frozen source snapshot. Earlier `prewire CONFIGURED` reports document configuration only; the independent message probe above documents observed protocol behavior.

## Confirmed remediation history

- Confirmed and fixed: Java heartbeat watchdog compared nanoseconds with milliseconds; fixed in `c58c0e4`
- Confirmed and fixed: Python outbound heartbeats could starve behind receive traffic; fixed upstream in `2078976a`
- Rejected hypotheses: Python cleanup starvation and connector-owned Java `HttpClient` strong-reference retention were investigated and not adopted

## Current safety state

- Existing endpoint/configuration defaults remain disabled; no production app assembly, live body binding, permit, input, provider, account, EULA, world, or public endpoint was activated
- Downloads/resources and build outputs are ignored local materials, not committed runtime evidence
- User authorization permits autonomous technical work; it does not invent legal, account, provider, EULA, world, or invited-human consent
- Pure permit/input tests and source review exist, but ordinary Forge packet guards and client-thread runtime behavior have not been tested in Forge

## Finite next work graph

1. Research exact Forge `47.4.28` hooks, mappings, and ordinary-packet source-guard coverage
2. Implement side-safe bootstrap with features disabled by default
3. Prove a typed `SimpleChannel` game connection plus permit/revoke lifecycle using actual typed integration tests
4. Add server ordinary movement and interaction default-deny enforcement, then narrowly whitelist controlled behavior
5. Implement legal client-thread input ownership with complete STOP/release behavior
6. Complete authenticated binding/coordinator/terminal-wire integration and its offline fault coverage
7. Attempt isolated runtime only after external EULA, world, legal, account, and action authorities are explicitly available; never replace those conditions with simulated acceptance

Future acceptance requires credible automated checks and the applicable Oracle gates. Any public release or push remains optional and requires an explicit decision.
