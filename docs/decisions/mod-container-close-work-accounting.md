# Mod-owned container-close work accounting

Status: **Scoped source/evidence PASS-with-residuals, parent accepted after final independent oracle
review; capture-failure P1 closed**. Java-only bounded local accounting for the one
`ForgeServerDefense.onContainerOpen` submission site. Not authority, binding quiescence, complete
server-queue coverage, runtime Mixin evidence, READY or production activation.

## Owner entry and unchanged defense

The supported event entry is server-owner-thread only. For a ServerPlayer event, the trusted server
owner gate is checked before registry or tracker access. Off-owner entry now fails fast with
`IllegalStateException`; it is not automatically scheduled. Non-server-player events remain ignored.
This is an explicit entry behavior change, not a claim that all Forge events have correct ownership.

The original candidate-or-resolved predicate is retained. For protected connections the registry
captures the exact current body and registry generation before the original CONTROL_LOST fence.
The package-private synchronized capture checks exact player reference, identity/connection adapter
results, full current binding and post-reentrant current/generation consistency. It does not mutate
generation, create a receipt or establish authority. A missing valid current body is classified
`NON_CURRENT_PROTECTED_CONNECTION`; no candidate body is invented. The captured generation is the
**pre-fence registry generation**, not a PREPARE generation.

Fence false still proceeds to close. Fence RuntimeException/Error means no registration/submission
and propagates unchanged **unless capture already threw Error**, in which case that original capture
Error has priority. After successful capture and a returning fence, registration precedes the same
`server.execute(wrapper)` call; failed capture uses the untracked path below. The test seam takes
deterministic owner/scheduler/action ports but does not replace production
server execution or introduce a generic scheduler. No extra notifier/permit/authority calls occur.

### Capture failure must not remove the original defense

The shared narrow capture helper catches accounting-capture RuntimeException/Error and permanently
invalidates the ledger. Failed capture is not a verified CURRENT_BODY or normal noncurrent result:
the original close is submitted untracked, with the observation permanently UNKNOWN.

For capture RuntimeException, the original fence still runs; if it returns (false included), the
original execute/close runs. If defense succeeds, only the accounting capture exception is swallowed.
Original fence or execute RuntimeException/Error propagates as the exact original defense object;
fence failure still means no submission. For capture Error, the original fence is attempted best effort;
if it throws, no work is submitted, and if it returns (false included), original execute/close is
attempted. The exact original capture Error is always rethrown, even if fence or execute also fails. No suppression,
retry, rescheduling or generic cleanup framework is used. This bounded exceptional policy does not
guarantee recovery after fatal VM damage. A failed capture creates no work record or invented body.

The event retains its direct server.execute call for structural provenance. Production and test seam
share capture-failure handling; a narrow ASM parity test checks capture→fence→execute ordering plus
fatal-priority catch/finally structure in both. This is static parity evidence, not Forge event runtime
execution or path-complete control-flow proof. Behavioral tests use the seam with the real registry/adapter/fence chain, mixed with pure
ledger unit cases; they do not claim actual Forge event delivery.

## Bounded ledger and distinct outcomes

Each defense instance owns a ledger with capacity **8**. Internally minted opaque work identities
retain exact server/player, classification, and optional exact body/full binding/pre-fence generation.
Submission states are `CALLING_EXECUTE`, `RETURNED`, `THREW`; execution states are `NOT_ENTERED`,
`RUNNING`, `RETURNED_NORMALLY`, `THREW`. They are independent: inline execution may finish before
execute returns, or finish normally before execute throws. Only an actual run exit together with
submission-call termination can retire an active slot. No unbounded completed-work history exists.
The external scheduler can retain its wrapper; the ledger itself does not retain retired identities.

Queued/drop/never-run work remains outstanding indefinitely. There is no timeout, cancellation,
retry or shutdown clearing. Submission throw without proven execution terminal remains outstanding
and UNKNOWN; eventual real execution may retire it, but never changes THREW submission into success.
Any submission or action exception permanently faults the narrow observation to UNKNOWN, even if
all slots subsequently retire. RuntimeException and Error preserve the exact original exception
object. Ledger bookkeeping invokes no user callbacks; gate failures are contained so they cannot
replace an action exception.

Overflow permanently loses coverage but still performs the original fence and executes the original
delegate, retaining no ninth work record. Later zero does not repair the fault. Duplicate wrapper
invocation, including late/reentrant invocation, still executes the delegate each time to preserve
original side effects, permanently faults coverage, and does not double-release or release another
entry. The first execution's terminal accounting owns the entry.

## Foreign execution and observation

All ledger state transitions and summaries are owner-first. A null/throwing/false trusted gate fails
closed. Off-owner wrapper execution only publishes volatile invalidation and still runs the original
delegate with exact exception propagation; it does not mutate owner-confined entries or claim that
execution completed there. `invalidate()` is arbitrary-thread volatile publication only, with no
shutdown wiring. The next owner observation is UNKNOWN and may retain stale outstanding/running
counts as conservative evidence of missing owner accounting.

This specifically covers the earlier structural caveat that stopped-server direct execute may run
inline on an unexpected thread. It is not a claim that non-owner callers always queue or that
`executeIfPossible` rejection guarantees direct execute rejection.

The only immutable package-private summary has availability `UNOBSERVED`, `TRACKING` or `UNKNOWN`,
outstanding/running counts, and the optional strict `noOutstandingObservedSubmissions` predicate.
That predicate requires at least one registered submission, no invalidation/coverage fault and no
active work. Initial and invalidated ledgers cannot report it. It says only that this instance's
observed mod-owned submissions have no active records, not that arbitrary server work stopped.

## Verification and residual boundaries

Tests use the real registry/provision/bind/fence chain with deterministic inline/queued/reentrant
schedulers. They cover pre-registration occupancy, body/generation capture and same-binding body
replacement, candidate-only/false-fence close, fence and action exceptions, submission ambiguity,
overflow, never-run work, duplicate execution, off-owner publication and owner-first rejection.
Reflection in tests reads private accounting state only; it does not manufacture work or completion.

Existing deferred-work byte evidence remains **OFFLINE_ORIGINAL_TARGET_STRUCTURE_ONLY**, not runtime
execution/injection or drain proof. `CHAT_DEFERRED`, `SCHEDULED_SERVER_TASKS`, `PERMIT_QUEUE_WATCHDOG`
and `CLIENT_PERMIT_CONSUMPTION` remain **UNAVAILABLE**, and production `complete()` remains false.
No bridge/snapshot/scope-enum/Mixin/coordinator/permit/wire changes or integration are included.
No whole vanilla/Forge scope closure, READY/CAS/COMMIT/ACTIVE/re-enable, delivery, network/game/EULA,
or G3 acceptance follows. Any future integration or protocol extension needs separate review.

## Review history and accepted evidence

The original final review BLOCK identified a P1: the new accounting capture could throw before the
original fence/close, suppressing defense. The slice was DRAFT / BLOCK pending rereview. The bounded
capture-failure remediation above closed that finding in the final independent oracle review;
parent accepted scoped source/evidence PASS-with-residuals, not full scope or shutdown closure.

Reviewed evidence archive: `Temp/opencode/container-capture-remediation-20261024-200644` under the
approved local Temp directory. Reviewed `source-final-sha256.json` manifest SHA-256:
`851BEF72C2C2C967DB5723C441DE9F552B7DD1E5D7D426FEC04300FE3CAA7450`.
Its four Java/test entries remain the reviewed bytes; this docs-only acceptance update changes only
the document entry. The oracle verified **62 artifact hashes** and fresh successful logs/XML:

- Focused: **4 suites / 56 tests / 0 failures / 0 errors / 0 skips**
- Full `build reobfJar`: **54 suites / 353 tests / 0 failures / 0 errors / 6 existing opt-in skips**
- Skips: `ControlTransportLoopbackTest` 2, `PostAuthV2BodyRouteLoopbackTest` 1,
  `PostAuthV2LoopbackTest` 2, `PostAuthV2VerifiedBodyProofLoopbackTest` 1; not network PASS

Prior 347-test evidence is historical and superseded for this slice. The new **full-run** deferred
structural report SHA-256 is
`2A7AAD875A77588829ED40967D75FF0B84B33C68D0958344C48A238DD7A7E59A`;
it is distinct from the focused-run report and does not inherit an older report's byte provenance.
Both remain original-byte structural evidence only. No redundant build is run for this docs-only
update. Capacity 8, permanent UNKNOWN/fault behavior, owner-entry policy and all residual boundaries
above remain unchanged; UNKNOWN is never completion evidence or authority.
