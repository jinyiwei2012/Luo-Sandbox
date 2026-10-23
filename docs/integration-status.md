# Minecraft integration status

> Authoritative implementation/evidence snapshot after Sandbox `95c9afb`. This is a scoped delivery status, not a runtime, EULA, world, provider, body-binding, permit, input-control, or release approval.

## Evidence ledger

| Milestone | Status | Reviewed scope and evidence | Explicit boundary |
| --- | --- | --- | --- |
| G1 canonical contract | PASS | Canonical contract and bidirectional offline interop were independently accepted | Does not authorize runtime or game integration |
| G2 offline foundation | PASS | Independently accepted offline foundation; platform capability is Windows/Python `3.10.21`/SQLite `3.53.4` `UNAVAILABLE`, `verified=False` | Only disabled-actuation transport development is authorized |
| Phase 3a source + heartbeat loopback | Scoped PASS | Sandbox `90831ec` transport, `c58c0e4` watchdog fix, `b90446a` fresh harness; upstream `2078976a` heartbeat/support. Frozen report `loopback-final-frozen-20261023-091500.json`: 2 roles, 0 failures/errors/skips, SERVER `2487 ms`, CLIENT `2491 ms`, JUnit SHA-256 `1dc53e8c96db9a9e809453b67ac050f671242a75380a7cae7aad3787d5c37430`, unchanged reviewed graph | Not full G3, permit delivery, body binding, Forge enforcement, client input, or game evidence |
| ASGI message-size behavior | PASS | Sandbox `8891047`; report `frame-limit-cleanup-20261023-092806-2280.json` tied to its recorded source hashes and loaded modules: 4096-byte valid HELLO → CHALLENGE; 4097-byte text and fragmented aggregate → WebSocket `1009`; owned process cleanup `terminated` | Verifies Uvicorn WebSocket **message** limit before route receive, not physical frame size, performance, all backend behavior, or production activation |
| Phase 3b mapped gameplay helper guards | Scoped source PASS | Sandbox `24ba04c`, `6846cd0`, `4a02c41`; 23 injectors: 18 `AFTER` exact `PacketUtils` handoffs plus 5 `HEAD` guards at stable mapped helpers `broadcastChatMessage`, `performChatCommand`, `updateBookContents`, `signBook`, and `updateSignText`. Five remap/refmap/AP/reobf static gates passed; 128 tests had zero errors/failures and 2 explicit opt-in loopback skips; build passed and independent scoped source review passed | Not full G3 or proof of real Forge startup, injector cardinality at runtime, runtime protocol behavior, mutation safety, body binding, permit delivery, client input, or all packet coverage |
| Offline BODY_PROOF primitives, codec, and ingress | Scoped offline PASS | Sandbox `5b393f2` LP11 auth/pending, `861e147` strict JSON codec, and `18d8ca8` thread-first single-use ingress. Suite at that slice: 154 tests in 27 suites, 0 failures/errors, 2 explicit opt-in loopback skips; Python 7-test vector suite passed. Dummy nonzero-UUID Java/Python vector matched in `body-proof-uuid-compared-v1.json` | Offline compile/test/source evidence only; the dummy vector is not a live connection or body-binding proof |
| Offline BODY_PROOF raw frame and registration declaration | Scoped source PASS | Sandbox `0c45014` raw-frame/handler core and `95c9afb` explicit uncalled registration declaration. Current full compile/tests: 166 tests in 30 suites, 0 failures/errors, 2 explicit opt-in loopback skips; build/reobf passed and source review passed | Registration is deliberately uncalled; no runtime packet, game-thread, disconnect, or frame-coverage proof |

Historical loopback passes with different test-support hashes remain historical evidence and are not treated as equivalent to the current frozen source snapshot. Earlier `prewire CONFIGURED` reports document configuration only; the independent message probe above documents observed protocol behavior.

## Confirmed remediation history

- Confirmed and fixed: Java heartbeat watchdog compared nanoseconds with milliseconds; fixed in `c58c0e4`
- Confirmed and fixed: Python outbound heartbeats could starve behind receive traffic; fixed upstream in `2078976a`
- Rejected hypotheses: Python cleanup starvation and connector-owned Java `HttpClient` strong-reference retention were investigated and not adopted
- Rejected implementation approach: synthetic lambda injection failed annotation processing and was rolled back; it is not implemented

## Current safety state

- Existing endpoint/configuration defaults remain disabled; no production app assembly, live body binding, permit, input, provider, account, EULA, world, or public endpoint was activated
- Downloads/resources and build outputs are ignored local materials, not committed runtime evidence
- User authorization permits autonomous technical work; it does not invent legal, account, provider, EULA, world, or invited-human consent
- Source-level Forge guards now cover the enumerated mapped helpers, while Forge custom payload is unchanged and other unenumerated handlers remain out of scope; client-thread runtime behavior has not been tested in Forge
- The controlled marker is non-authorizing: it is set before candidate publication and preserves historical denial after logout, clone, or bridge uninstall
- Denied commands preserve caller spam bookkeeping; denied chat bypasses helper spam bookkeeping while retaining arrival-signed bookkeeping
- BODY_PROOF codec has exact 8 challenge and 10 proof fields with a 4096-byte cap; ingress owns at most 2 private pending challenges, one per connection, for 5 seconds against an exact connection reference
- Malformed BODY_PROOF decoding discards the exact connection's pending challenge before clock access; clock failure permanently closes the ingress. Tracking remains bounded to 2 entries; a regression exercises 100 successive expired connections without accumulating references
- Exact Forge `47.4.28` sources were hash-verified, but outer framing failure policy is unknown and no frame-coverage claim is made
- Raw BODY_PROOF JSON accepts only 1..4096 bytes with no inner VarInt length prefix; a malformed raw frame becomes a payload-free `MALFORMED` sentinel, then queued ingress discards the exact pending challenge
- The declared profile is `luosandbox:body_proof_v1`, version `1`, discriminator `0`, `PLAY_TO_SERVER` only. Default classload and mod entry do not call registration
- Owner route capture is immutable and volatile before enqueue; execution rechecks generation. Cleanup occurs before withdrawal; partial registration failure is non-retryable and requires restart
- Unknown outer discriminator and empty custom payload do not consume one-use state; pending TTL remains at most 5 seconds and capacity remains 2

## Finite next work graph

1. Reconcile the mapped-guard slice against actual Forge `47.4.28` startup, injector cardinality, refmap/reobf behavior, and runtime protocol/mutation evidence
2. Add lifecycle cleanup/disconnect adaptation and an actual invoked registration path, then establish runtime packet/thread and outer-framing failure evidence
3. Implement server-to-client challenge and client `K_body` proof, join BODY_PROOF to authenticated WS notice origin, emit BODY_BOUND, and complete candidate binding before any permit work
4. Prove a typed game connection plus permit/revoke lifecycle using actual typed integration tests
5. Extend only explicitly enumerated ordinary movement/interaction enforcement; keep custom payload and other unenumerated handlers outside claims until separately reviewed
6. Implement legal client-thread input ownership with complete STOP/release behavior
7. Complete authenticated binding/coordinator/terminal-wire integration and its offline fault coverage, then attempt isolated runtime only after external EULA, world, legal, account, and action authorities are explicitly available

Future acceptance requires credible automated checks and the applicable Oracle gates. Any public release or push remains optional and requires an explicit decision.
