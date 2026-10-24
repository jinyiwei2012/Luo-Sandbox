# Binding quiescence observation (Java-only, server-local)

> **Status: scoped source/evidence PASS-with-residuals (ora-18 final review, parent accepted).**
> Java-only **LOCAL INCOMPLETE** evidence, not READY, authority or wire; not overall G3 acceptance.
> Builds on the accepted protected-movement drain observation (`7c4f68a`) and the exact PREPARE
> revocation fence. Acceptance covers the exact reviewed Java bytes and bounded offline evidence only.

## 1. What this is

`BindingQuiescenceObservation` is a sealed, one-use, owner-thread, server-local evidence object. It
carries a per-scope status snapshot (`DRAINED` / `NOT_DRAINED` / `UNAVAILABLE` / `FAILED`) for the six
scopes that complete binding quiescence would have to cover. `Snapshot.complete()` is true only if
**every** scope is `DRAINED`.

It deliberately cannot be mapped to READY, rebind, CAS, COMMIT, ACTIVE, permit creation, or wire
delivery. No method returns a permit, binding, receipt, body or authority type.

## 2. Scope table (honest observability)

| Scope | Status today | Source (file:line) |
|---|---|---|
| `MOVEMENT_HANDLER` | Observable: `DRAINED` / `NOT_DRAINED` / `FAILED` | `ForgePacketDefenseBridge.sampleQuiescence` (`ForgePacketDefenseBridge.java:373`, call at `:394`) → package-private exact-body `ProtectedMovementHandlerTracker.observeDrained` overload (`ProtectedMovementHandlerTracker.java:158`); requires the tracker body reference to match the validated fence view |
| `LOCAL_PERMIT_AUTHORITY` | Observable: `DRAINED` / `NOT_DRAINED` / `FAILED` | `ForgePacketDefenseBridge.sampleQuiescence` (`ForgePacketDefenseBridge.java:406`) → owner-thread-only `ServerControlCoordinator.bindingPrepareFenceHolds` (`ServerControlCoordinator.java:183`): exact binding, lifecycle quarantined, `permits.activePermit()` empty, `permittedRequestId == null` |
| `CHAT_DEFERRED` | `UNAVAILABLE` | Deferred chat/command/book/sign handling is a stateless per-packet decision (`ForgePacketDefenseBridge.java:165-202`, `ForgeServerDefense.java:145`); nothing tracks in-flight deferred work |
| `PERMIT_QUEUE_WATCHDOG` | `UNAVAILABLE` | No permit queue or watchdog exists in `src/main` |
| `CLIENT_PERMIT_CONSUMPTION` | `UNAVAILABLE` | `PermitNotifier.send/revoke` is fire-and-forget (`ServerControlCoordinator.java:10-13`, `:102`); there is no client ack channel |
| `SCHEDULED_SERVER_TASKS` | `UNAVAILABLE` | Tasks submitted with `server.execute(...)` are untracked (`ForgeServerDefense.java:162`) |

No fake observers: unobservable scopes are never reported as `DRAINED`. Consequence: with current code
`complete()` is **always false in production**. Complete quiescence is impossible until every
unavailable scope gains a real observer.

## 3. Object design

- `final` class, private constructor, package-private `create(...)` currently called by
  `ForgePacketDefenseBridge`. This is **PACKAGE TRUST**, not compiler-enforced bridge-only minting:
  other code in the package can call the factory. No public factory. Identity-only `equals`/`hashCode`.
- Private fields bind: minting bridge instance (owner), server, listener, player, `ControlledBody`
  identity, full `PermitAuthority.Binding`, exact `PrepareFenceReceipt` identity, PREPARE generation,
  mint time and the original receipt deadline (never extended; 5000 ms from PREPARE).
- `ControlledIdentityRegistry.prepareFenceView(..., now)` (package-private, synchronized) reuses full
  receipt validation, including connection identity, expiry and registry-relative clock rollback, then
  captures body identity, generation and original deadline atomically; validation updates the clock
  watermark and can invalidate an expired fence, but never creates a receipt or refreshes its deadline.
- Public surface: scope/status enums, `Snapshot.status/statuses/complete`, `mintSnapshot()`,
  `isSpent()`. Mint snapshot is informational; redeem re-samples.

## 4. Mint / redeem semantics

- `ForgePacketDefenseBridge.mintBindingQuiescenceObservation(...)` and
  `redeemBindingQuiescenceObservation(...)` are package-private and owner-thread-first.
- Mint requires: owner thread, bridge not uninstalled, `validatesBindingPrepareFence` (receipt,
  player, binding, deadline), live fence view, then samples every observer.
- Redeem consumes the observation **first** (`AtomicBoolean`); every outcome - success, mismatch,
  timeout, clock rollback, foreign thread, exception - leaves it spent, so a second redeem always
  fails. `RuntimeException` poisons and returns empty; `Error` poisons and rethrows.
- Redeem then revalidates: deadline window (`mintedAt <= now < originalDeadline`), live PREPARE
  fence (receipt identity, generation, binding, player/connection identity), exact owner / server /
  listener / player / body / binding / receipt identity, same generation and deadline, and re-samples
  all observers. Result is a fresh `Snapshot` (evidence), not a token.
- Observer exception => that scope is `FAILED`, never `DRAINED`.
- Registry-managed bind, candidate/transfer, fence and offline-release invalidation paths advance the
  registry generation and clear the fence. This is not a claim that every external lifecycle change
  advances that generation. Mint/redeem re-fetch a fully validated fence after sampling and require
  identical body reference, generation and original deadline; no retry or deadline refresh occurs.
  Combined movement sampling requires the exact fenced body reference, not only player/binding fields.
  Off-thread uninstall publishes only a volatile flag; the owner lazily clears tracker state. The final
  volatile success check is the linearization point, not a promise to block a later uninstall.

## 5. Non-goals / explicit constraints

- **No delivery.** The frozen v2 contract has no message kind for this. Any delivery to Python
  requires a separately reviewed protocol extension, outside this slice; no v3 is approved and frozen
  v2 has no binding-READY kind.
- No READY mapping, rebind, CAS, COMMIT, ACTIVE, permit creation or revocation side effects.
- `ControlledPacketGuard` and the mixin are unchanged; accepted RETURN injection semantics remain intact.
  The prior movement acceptance (`7c4f68a`) retains its runtime Mixin residual; offline source/tests do
  not establish actual Minecraft runtime injection or full handler/world quiescence.
  `ProtectedMovementHandlerTracker` adds only a package-private exact-body combined-observer overload.
  Minimal additive read-only hooks were added to `ControlledIdentityRegistry` (fence view) and
  `ServerControlCoordinator` (`bindingPrepareFenceHolds`), because the coordinator's permit state is
  private and otherwise unobservable.

## 6. Tests

`BindingQuiescenceObservationTest` uses the real `BoundedLifecycle` / `PermitAuthority` /
`ServerControlCoordinator` / `ControlledIdentityRegistry` / `ControlledPacketGuard` / bridge chain
from `ForgePacketDefenseBridgeTest.Fixture` (real LOOK permit, tracked movement, real
`beginBindingPrepare`). No direct `authorizeLast`, no field-minted objects. Covers positive path,
movement not returned, deadline, replay, foreign thread, wrong identities, cross-fixture, new PREPARE
generation, control loss, offline release, owner uninstall, observer exception, and a reflection
check for the sealed API.

## 7. Accepted evidence and residual boundary

Parent accepted ora-18 final scoped PASS-with-residuals for the exact reviewed Java bytes. The oracle
verified all 10 entries of the pre-follow-up `after-sha256.txt` manifest (SHA-256
`7A1F415DFF2CD16AE93EBB0144B20874FF818196008687C828FB96C8F0829288`) and 59 artifact hashes under
`C:\Users\Administrator\AppData\Local\Temp\opencode\ora18-remediation-20261024-192535`.
This docs-only follow-up changes the three documentation entries, not the seven Java entries.

Final focused XML: **5 suites / 87 tests / 0 failures / 0 errors / 0 skips**. Final full XML:
**52 suites / 324 tests / 0 failures / 0 errors / 6 existing opt-in skips**. The skipped cases remain
`ControlTransportLoopbackTest` 2, `PostAuthV2BodyRouteLoopbackTest` 1, `PostAuthV2LoopbackTest` 2 and
`PostAuthV2VerifiedBodyProofLoopbackTest` 1; they are not network PASS. Actual compile/test/build/reobf
logs succeeded. No unchanged Java build is repeated for this docs-only follow-up.

Four `UNAVAILABLE` scopes keep production `complete()` false. Package trust, runtime Mixin evidence,
complete quiescence and delivery remain residual boundaries; no READY/CAS/COMMIT/ACTIVE, permit
re-enable, protocol extension approval, runtime activation or overall G3 acceptance follows.
