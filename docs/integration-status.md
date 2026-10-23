# Minecraft integration status

> Authoritative implementation/evidence snapshot after Sandbox `1b68d40`. This is a scoped delivery status, not a runtime, EULA, world, provider, body-binding, permit, input-control, or release approval.

## Evidence ledger

| Milestone | Status | Reviewed scope and evidence | Explicit boundary |
| --- | --- | --- | --- |
| G1 canonical contract | PASS | Canonical contract and bidirectional offline interop were independently accepted | Does not authorize runtime or game integration |
| G2 offline foundation | PASS | Independently accepted offline foundation; platform capability is Windows/Python `3.10.21`/SQLite `3.53.4` `UNAVAILABLE`, `verified=False` | Only disabled-actuation transport development is authorized |
| Phase 3a source + heartbeat loopback | Scoped PASS | Sandbox `90831ec` transport, `c58c0e4` watchdog fix, `b90446a` fresh harness; upstream `2078976a` heartbeat/support. Frozen report `loopback-final-frozen-20261023-091500.json`: 2 roles, 0 failures/errors/skips, SERVER `2487 ms`, CLIENT `2491 ms`, JUnit SHA-256 `1dc53e8c96db9a9e809453b67ac050f671242a75380a7cae7aad3787d5c37430`, unchanged reviewed graph | Not full G3, permit delivery, body binding, Forge enforcement, client input, or game evidence |
| ASGI message-size behavior | PASS | Sandbox `8891047`; report `frame-limit-cleanup-20261023-092806-2280.json` tied to its recorded source hashes and loaded modules: 4096-byte valid HELLO → CHALLENGE; 4097-byte text and fragmented aggregate → WebSocket `1009`; owned process cleanup `terminated` | Verifies Uvicorn WebSocket **message** limit before route receive, not physical frame size, performance, all backend behavior, or production activation |
| Phase 3b mapped gameplay helper guards | Scoped source PASS | Sandbox `24ba04c`, `6846cd0`, `4a02c41`; 23 injectors: 18 `AFTER` exact `PacketUtils` handoffs plus 5 `HEAD` guards at stable mapped helpers `broadcastChatMessage`, `performChatCommand`, `updateBookContents`, `signBook`, and `updateSignText`. Five remap/refmap/AP/reobf static gates passed; 128 tests had zero errors/failures and 2 explicit opt-in loopback skips; build passed and independent scoped source review passed | Not full G3 or proof of real Forge startup, injector cardinality at runtime, runtime protocol behavior, mutation safety, body binding, permit delivery, client input, or all packet coverage |
| Offline BODY_PROOF primitives, codec, and ingress | Scoped offline PASS | Sandbox `5b393f2` LP11 auth/pending, `861e147` strict JSON codec, and `18d8ca8` thread-first single-use ingress. Suite at that slice: 154 tests in 27 suites, 0 failures/errors, 2 explicit opt-in loopback skips; Python 7-test vector suite passed. Dummy nonzero-UUID Java/Python vector matched in `body-proof-uuid-compared-v1.json` | Offline compile/test/source evidence only; the dummy vector is not a live connection or body-binding proof |
| Offline BODY_PROOF raw frame and registration declaration | Scoped source PASS | Sandbox `0c45014` raw-frame/handler core and `95c9afb` explicit uncalled registration declaration. Suite at that slice: 166 tests in 30 suites, 0 failures/errors, 2 explicit opt-in loopback skips; build/reobf passed and source review passed | Registration is deliberately uncalled; no runtime packet, game-thread, disconnect, or frame-coverage proof |
| Offline BODY server lifecycle facade | Scoped source PASS | Sandbox `b2b95ce` explicit unregistered `BodyProofServerLifecycle` facade. Suite at that slice: 171 tests in 31 suites, 0 failures/errors, 2 explicit opt-in loopback skips; source review passed | No runtime Forge event delivery, actual registration/mod-entry call, logout connection capture, notice join, BODY_BOUND, binding, permit, or G3 proof |
| Offline BODY routing and ownership slices | Scoped source PASS | Sandbox `4696638` explicit unregistered stop-only Forge-event adapter, `0d54869` exact-connection/challenge-ID conditional-discard issuer with supplier/clock reentrancy fixes, `5986254` trusted-provisioned single-connection client owner with key zero-on-close and one challenge/no retry, and `c45379a` explicit uncalled bidirectional routes | Suite at that slice: 201 tests in 35 suites, 0 failures/errors, 2 explicit opt-in loopback skips; build/reobf and independent source review passed. Offline integration joins issuer/auth/codec/client-owner/ingress using transport-queue doubles, not an actual network |
| Client logout adaptation | Scoped source PASS | Sandbox `43465f6` adds an explicit unregistered `ClientPlayerNetworkEvent.LoggingOut` adapter bound to the event-carried exact connection and captured client handle. Null, stale-reference and wrong-thread events do not fall back to current player/network state; cleanup failure retains the route and key owner | No EventBus registration or full network-failure coverage. Review used parent-provided exact source because the independent reviewer had a filesystem/tool gap |
| Local verified-proof provenance | Scoped source PASS | Sandbox `1b68d40`: verifier-private proof construction retains the original local monotonic deadline, verifier identity and exact connection; acceptance is one-use `AVAILABLE` → `CONSUMED` or terminal `INVALIDATED`. Ingress close is permanent generation withdrawal; pending plus available-proof capacity is shared at 2 with no TTL reset. Observer failure invalidates the exact proof before physical close, and successful callbacks are followed by a generation recheck | Current suite: 216 tests in 36 suites, 0 failures/errors, 2 explicit opt-in loopback skips; build/reobf and independent source review passed. Acceptance remains package-private with a test-only bridge; the public invalidation operation can only deny. This is local provenance, not body-binding authorization |

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
- The declared profile is `luosandbox:body_proof_v1`, version `1`: proof discriminator `0` is `PLAY_TO_SERVER`, and challenge discriminator `1` is `PLAY_TO_CLIENT`. Default classload and mod entry do not call registration
- Owner route capture is immutable and volatile before enqueue; execution rechecks generation. Cleanup occurs before withdrawal; partial registration failure is non-retryable and requires restart
- Unknown outer discriminator and empty custom payload do not consume one-use state; pending TTL remains at most 5 seconds and capacity remains 2
- The unregistered lifecycle facade keys owner state by exact server reference plus generation handle and has `NEW`, `ACTIVE`, `STOPPING_QUARANTINED`, and `CLOSED` states. Cleanup precedes forgetting owner state; cleanup failure blocks replacement until successful trusted cleanup
- Lifecycle accepts a lazy exact-connection supplier supplied by a trusted adapter and performs no UUID/name/player lookup
- Stop-only Forge-event adaptation is explicitly unregistered. Challenge issuance uses exact server reference plus generation handle and challenge ID for conditional discard; supplier/clock reentrancy is fenced
- Client ownership is one trusted-provisioned connection, zeroes key material on close, and allows one challenge with no retry. Volatile client reservation and route capture occur before enqueue; sending rechecks generation
- Declared bidirectional routes remain uncalled: S2C uses ID `1` with a distinct class, C2S uses ID `0`, and both retain the same profile/version. No implicit registration, key-file loading, EventBus call, notice join, BODY_BOUND, binding, permit, or input exists
- The client logout adapter is explicit and unregistered. It uses only the event-carried exact connection and captured handle; null/stale/wrong-thread events do nothing, while cleanup failure retains the route and key owner. It does not claim coverage of every network-error path
- Verified proof provenance keeps the original Java-local monotonic deadline; it never refreshes TTL or compares an absolute Java monotonic value with a Python clock. Disconnect, permanent ingress close, expiry, replacement challenge and observer/generation failure terminally invalidate retained proofs
- The frozen controller rule remains unchanged: controller replacement or loss invalidates an unfinished notice/proof join. No grant-before-disconnect redemption rule is implemented
- Post-authenticated origin/session-frame MAC, controller-withdrawal fencing, BODY_BIND_NOTICE join, BODY_BOUND, coordinator prepare, binding, permit and activation remain unimplemented

## Finite next work graph

1. Reconcile the mapped-guard slice against actual Forge `47.4.28` startup, injector cardinality, refmap/reobf behavior, and runtime protocol/mutation evidence
2. Connect the explicit logout/lifecycle/route declarations to actual Forge lifecycle, registration, packet/thread, and historical disconnect evidence; retain the stated unknown full-network-error coverage
3. Add authenticated post-auth origin and controller-withdrawal fencing, then join BODY_BIND_NOTICE to local proof provenance, emit BODY_BOUND, and call the existing coordinator prepare path before permit work
4. Prove a typed game connection plus permit/revoke lifecycle using actual typed integration tests
5. Extend only explicitly enumerated ordinary movement/interaction enforcement; keep custom payload and other unenumerated handlers outside claims until separately reviewed
6. Implement legal client-thread input ownership with complete STOP/release behavior
7. Complete authenticated binding/coordinator/terminal-wire integration and its offline fault coverage, then attempt isolated runtime only after external EULA, world, legal, account, and action authorities are explicitly available

Future acceptance requires credible automated checks and the applicable Oracle gates. Any public release or push remains optional and requires an explicit decision.
