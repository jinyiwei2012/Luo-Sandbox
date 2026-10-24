# Deferred work observation boundaries

Status: **scoped offline structural evidence PASS-with-residuals, parent accepted after independent
oracle review**. Java-only offline original-byte structure evidence;
not transformed Mixin, runtime execution, task drain, quiescence, admission authority or G3 evidence.
The prior accepted movement observation (`7c4f68a`) and binding-quiescence local incomplete evidence
remain distinct. No production code or scope status changes in this lane.

## Reproduction and bounded report

Run the existing Forge wrapper with `test --tests '*DeferredWorkBoundaryEvidenceTest' --tests
'*MixinBuildMetadataTest' --offline --no-daemon --rerun-tasks --max-workers=2`, using the existing local
Gradle home and Python interop fixture directory. Missing local artifacts are a blocker, not permission
to download. No Minecraft class is loaded/executed or transformed: ASM reads class resource bytes and
independently compares them with the original local JAR entry or compiled class file.

Ignored report: `.gradle-user-home/interop/evidence/deferred-work-boundary-evidence.json`, labelled
`OFFLINE_ORIGINAL_TARGET_STRUCTURE_ONLY`. It records resource/artifact paths, class SHA-256 and byte
length, class hierarchy, generator source SHA-256 from final source bytes, exact method descriptors,
direct invocation edges, invokedynamic implementation handles, returns, ATHROW, exception regions,
jump targets and fields. Instruction indices include ASM labels/debug nodes, not JVM byte offsets.
Invokedynamic handle tags are recorded separately from direct invocation opcodes by edge kind; an
implementation handle is a structural reference, not proof it ran or completed.

Fixed class allowlist: `ServerGamePacketListenerImpl`, compiled mod `ForgeServerDefense`,
`MinecraftServer`, `BlockableEventLoop`, `ReentrantBlockableEventLoop`, `TickTask`. Fixed method-name
allowlists and listener-only reverse-call/lambda closure are in the generator and report. Limits:
6 classes, 2,000,000 bytes/class, 512 methods/class, 20,000 instruction nodes/method, 96 selected
listener methods, 8 closure rounds, 160 reported methods. No arbitrary recursive whole-game traversal.
All listener-local references to the five exact helpers are separately enumerated, including callers
not otherwise treated as established producers. Cross-class edges are reported but not followed.

## Five denial boundaries, not upstream completion

Existing exact Mixin selectors (asserted against current source and unique original target methods):

| Helper | Exact descriptor |
|---|---|
| `broadcastChatMessage` | `(Lnet/minecraft/network/chat/PlayerChatMessage;)V` |
| `performChatCommand` | `(Lnet/minecraft/network/protocol/game/ServerboundChatCommandPacket;Lnet/minecraft/network/chat/LastSeenMessages;)V` |
| `updateBookContents` | `(Ljava/util/List;I)V` |
| `signBook` | `(Lnet/minecraft/server/network/FilteredText;Ljava/util/List;I)V` |
| `updateSignText` | `(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;Ljava/util/List;)V` |

The existing HEAD guards deny at these helpers through `handleDeferredGameplay`/`evaluateDeferredGameplay`;
`ForgeServerDefense.onChat` also denies the event. None tracks upstream outstanding work.
Original listener edges include chat/command `MinecraftServer.submit`, chat synthetic lambdas feeding
`FutureChain.append` and `CompletableFuture.thenAcceptAsync`, text filtering via `filterTextPacket`
and `thenApply`, and book/sign callbacks via `thenAcceptAsync`. Synthetic helper callers include
`lambda$handleChat$8`, `lambda$handleChatCommand$11`, `lambda$handleEditBook$3/$4` and
`lambda$handleSignUpdate$14`; exact descriptors and all enumerated edges are in JSON.

These establish class-local reference paths only. They do not establish a trusted producer/admission
registry, exhaustive upstream async creation, cancellation acknowledgement, or callback completion.
CompletableFuture internals, dynamic text filters/decorators, Forge hooks, other executors and external
callers remain unresolved. A helper RETURN or denied HEAD cannot imply that those paths drained.

## Container task and local event-loop boundary

Compiled `ForgeServerDefense.onContainerOpen(PlayerContainerEvent.Open)` invokes `registry.fence`
before creating the `ServerPlayer.closeContainer()V` Runnable implementation handle and invoking
`MinecraftServer.execute(Runnable)V`. The test checks instruction ordering, not a general path-sensitive
control-flow proof. Submission occurs for protected candidates as well as resolved bodies, and does
not depend on a successful fence return. `registry.fence` changes current/receipt state; a future
observer cannot recover an exact old body by looking up current state after this fence, nor invent
a controlled body for a candidate-only submission.

The exact local hierarchy is `MinecraftServer` → `ReentrantBlockableEventLoop` →
`BlockableEventLoop`. The first two declare no exact `execute(Runnable)V` override. In the inherited
implementation, `scheduleExecutables()Z` branches between `wrapRunnable`/`tell` and direct
`Runnable.run()V`. `tell` invokes `Queue.add` then `LockSupport.unpark`; it does not await the task.
`scheduleExecutables` consults thread ownership and the reentrant layer's running-task state;
`MinecraftServer.scheduleExecutables` additionally includes `!isStopped()`. Thus a non-owner caller
must not be assumed to always queue, particularly during shutdown. The separate `executeIfPossible`
rejection path is not a rejection guarantee for direct `execute(Runnable)` calls.
The report includes local wrappers/bridges, `TickTask.run`, task polling and task execution methods.

Submission returning on the queued branch is not runnable completion. On the inline branch the
Runnable runs before execute returns, so future accounting must register **before** calling execute,
not afterward. `BlockableEventLoop.doRunTask` catches `Exception` around runnable execution and logs;
`ReentrantBlockableEventLoop.doRunTask` has a finally/rethrow region for its task-depth accounting.
An outer execute-return observer therefore cannot infer successful work from return or absence of
an observed throw; no explicit ATHROW also does not exclude exceptions propagated by callees.

## Actionable future seam, not an implementation approval

A future separately reviewed wrapper could register an exact submission identity before either
inline or queued execution, then use its own try/finally with a one-use terminal transition inside
`run` to account for normal versus exceptional completion exactly once. It would need bounded
capacity, exact captured body/generation when one exists, owner-thread publication rules, reentrancy
handling, and explicit candidate-only semantics. This evidence installs none of that machinery.

Submission throw is **not cancellation success**: the queue/add/unpark boundary and dynamic dispatch
do not provide transactional no-acceptance proof. Work may have been accepted or run before an
exception is observed. A wrapper must retain unresolved/UNKNOWN accounting unless independently
proven terminal; submission-catch plus run-finally must not double-decrement. Shutdown, duplicate
execution, queue withdrawal, external overrides and complete producer coverage require separate
review and tests. Static structure here cannot establish their safe terminal accounting.

## Unchanged residuals

`CHAT_DEFERRED`, `SCHEDULED_SERVER_TASKS`, `PERMIT_QUEUE_WATCHDOG` and `CLIENT_PERMIT_CONSUMPTION`
remain **UNAVAILABLE**; production `complete()` remains false. A transport watchdog is not a
body-binding observer. No whole vanilla/Forge scope closure, runtime Mixin success, READY/CAS/COMMIT/
ACTIVE/re-enable, delivery, game, EULA or network PASS is claimed. Any future delivery needs a
separately reviewed protocol extension; frozen v2 is unchanged and no v3 is approved.

## Accepted evidence record

The independent oracle verified the archived report and generator source under
`Temp/opencode/deferred-boundary-20261024-194347` (the approved local Temp evidence directory).
The accepted report enumerates **6 original classes, 56 reported methods and 5 listener-local helper
call sites**. The 56-method count is the result of the bounded selection/closure for these artifacts,
**not a fixed 56-method whitelist**. It establishes neither producer admission nor actual completion,
whole-scope closure or runtime behavior.

Final focused validation: **2 suites / 4 tests / 0 failures / 0 errors / 0 skips**. Production Java
source hashes remained identical **46/46**. No full build or network PASS is claimed; unchanged tests
are not rerun for this docs-only acceptance update.

Verified generator source SHA-256:
`7096DBEE8ECBC0F4BC79E866FB1ECCD2737595A3B984A21E7B4AFAF10693B44C`.
Verified archived report SHA-256:
`72AEE2B328923697B1C3538ACAC5837EE15381E292B11CD18AE62089B5F31949`.
Future wrapper accounting remains design only: submission throw remains UNKNOWN absent independent
terminal evidence, with no double release between submission-catch and run-finally.
