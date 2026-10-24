# Default-disabled Java v2 connector — staged bounded contract

**Staged architecture scoped PASS_WITH_RESIDUALS, parent accepted after independent mom-1 review;
default-disabled UNWIRED deterministic connector core now has ora18/parent scoped source/evidence
PASS-with-residuals; JDK network/startup/production-port assembly and activation NOT authorized.** This document describes
a bounded connector seam, not another preflight service or an activation decision. The staged
design and its numerical accounting contract are accepted at design level; implementation evidence and
external-port compatibility remain separate gates. Existing frozen protocol requirements are identified
separately. This documentation-only amendment supplies no new test, network or runtime evidence.
Historical document baseline SHA-256:
`F77DFF3210E92EE8B720905A8DF96AAABFB671DEF15C30EA8FFD9DA6D7455D30`.
Current amendment base is Sandbox `3c714f19c8d6d63a7ed42f80ca4d7d3591d445a1`.
The original six design BLOCK findings are resolved at design level by the scoped review, not erased
from history or relabeled implementation PASS. Actual port, representation, completion and cleanup
evidence remain implementation gates. The separate session prerequisite and the unwired three-cell
`V2OwnerMailbox` and `V2SessionSendAdapter` unwired production primitives now have ora18/parent
scoped source/evidence acceptance (§10).
Further implementation requires a separate bounded plan/review and authorization. No readiness or
capability upgrade follows; no future commit hash is asserted.

## 1. Purpose, exclusions and future change envelope

The accepted unwired default-disabled Java v2 deterministic core performs actual HELLO → CHALLENGE → PROOF →
authenticated handshake READY verification, create private verified-handshake provenance, construct
one private `PostAuthPeerSession`, and own invalidation/local fencing/key cleanup/physical closure.
**Handshake auth READY is not binding READY.** Public transcript fields, successful KDF construction,
or a boolean called authenticated/available cannot substitute for verification provenance.

Excluded: BODY proof/routes, candidate/admission, coordinator/permit/epoch/business protocol,
production startup, registration, activation, reconnect, and any change to v1. Frozen post-auth v2
has no PREPARE/binding-READY/COMMIT/ACTIVE business kinds. Java manifest parsing of supplied bytes
does not establish filesystem or credential custody; Python verified custody is not transferred by
copying its DTOs/hashes. Windows epoch capability remains UNAVAILABLE/unverified. No boolean
capability substitute, auto-provisioning or undocumented v3 approval is introduced.

The original planned connector envelope, relative to `bridge/forge/src/`, was:

1. `main/java/io/github/jinyiwei2012/luosandbox/transport/V2PeerConnector.java`
2. `main/java/io/github/jinyiwei2012/luosandbox/transport/V2JdkWebSocketAdapter.java`
3. `test/java/io/github/jinyiwei2012/luosandbox/transport/V2PeerConnectorTest.java`
4. `test/java/io/github/jinyiwei2012/luosandbox/transport/V2JdkWebSocketAdapterTest.java`

Those four files describe the later full connector envelope, not the immediate next implementation
step. Parent previously authorized `main/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthPeerSession.java`
and `test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthPeerSessionTest.java` for the absolute
OPEN deadline and single-read cadence prerequisite; its implementation now has scoped independent
source/evidence acceptance in `2684526`. The separate unwired mailbox/paired-test primitive is also
accepted in `c2769cc`; the separate unwired session-send adapter is now also accepted, not the full
adapter or connector. The next eligible stage still requires a separate bounded plan/review
and authorization for bounded
**production adapter primitives plus tests**, reusable by the later connector without wiring. Do not
build a test-only duplicate framework. The deterministic core is now accepted as recorded below;
the separate JDK adapter source is now also accepted as an unwired slice below; remaining
network/startup/production-port work remains unauthorized pending
those gates and external owner-port validation. Any prerequisite file scope requires its own explicit
review; architecture acceptance itself authorizes no further edits. The benefit is one
missing physical-connection/currentness boundary around existing crypto/session primitives; a second
deployment preflight, role map, business queue or coordinator would duplicate existing ownership
without closing that boundary. Existing sole Python coordinator/current-role owner/worker remain.

## 2. Current source anchors and discrepancies

Paths in this section are under `bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/`.
Session line anchors describe the pre-amendment reviewed baseline, not a claim about the parallel
prerequisite writer's current bytes or implementation acceptance:

- `security/ControlAuth.java:25-60,68-83`: roles/labels, public `Transcript`, 16-field LP transcript,
  HMAC verification, canonical B32/nonzero UUID/millisecond UTC primitives. The retained auth domain
  is `luosandbox.control-auth.v1`; v2 changes the transcript protocol value, not this domain.
- `security/PostAuthSessionKeys.java:24-53,72-77`: `derive(byte[], Transcript)` accepts caller-trusted
  context, explicitly does not authenticate it, clones/erases temporary bytes, and closes directional
  keys. It is not a verified-provenance factory.
- `transport/PostAuthPeerSession.java:17-34,69-89`: public constructor, owner/clock/post/abort/
  diagnostics/sender/application/lifecycle ports; constructor checks owner and clock and starts its
  own 5000 ms OPEN deadline. The connector constructor must **not** construct this session.
- Session `:100-135,161-170,211-284,314-342`: receive/seq0 ACK, heartbeat loop, send completion posting,
  abort rejection, cleanup and Error behavior. Currentness of physical transport is caller-owned.
- `transport/ControlWebSocketTransport.java:123-132` is a v1 reference only. Its CHALLENGE check uses
  `observed.isAfter(expires)`, so equality is not rejected there. Proposed v2 requires expiry-boundary
  rejection and does not silently change v1. Its public config/credential access is not a v2 custody
  solution.
- [Frozen post-auth v2](minecraft-control-postauth-v2-draft.md) §§2,4-6: unchanged handshake transcript,
  session KDF/frame codec, 4096-byte message/2048-byte payload, SESSION_OPEN/seq0 heartbeat ACK,
  500 ms cadence/2000 ms watchdog and original bootstrap deadlines. Its binding admission narrative
  is not a wire extension or an authoritative Java READY producer.

Concrete adaptation gaps are listed in §10. This proposal does not assert existing APIs already
provide no-catch-up timers, nonthrowing callbacks, verified credentials or hard global memory bounds.
The former outer-member-order codec prerequisite is closed by accepted Sandbox `28bc7d9` and clean
local-only upstream `57e46850`: Java full 355 tests, Python focused 95/adjacent 86 PASS and actual
18-case interop were independently reviewed. Exact 14-member set validation is order-independent;
encoder order, duplicate/value rules, semantic MAC and ordered 23-key manifest are unchanged. No
rerun or connector acceptance is inferred from that prerequisite closure.

## 3. Explicit inputs and inert construction

Inputs would be trusted immutable v2 context and narrow ports, not a general framework:

- Explicit literal loopback `ws` URI and explicit port, with no DNS name, userinfo, query or fragment;
  exact endpoint path is trusted configuration. Reject non-loopback literals/invalid ports/profile.
- Protocol `LUO_CONTROL_POSTAUTH_V2`, role SERVER_AUTHORITY or CLIENT_CONTROLLER (never AGENT_HUB),
  peer/hub install IDs, key ID and exact mod/config/binding artifact hashes.
- External closable credential owner, trusted UTC and monotonic clocks, owner gate, bounded owner
  post, reserved terminal/abort cell on that same owner pump, timer, physical dialer, local fence, sanitized diagnostic
  sink and cryptographic nonce source. An arbitrary public supplier is not verified provisioning.

Construction only validates/stores supplied data and creates one private inert attempt identity
(generation 1). It performs **zero credential, clock, connect, timer, post or registration calls**.
Disabled start checks the disabled setting first and performs zero such calls, including owner-gate
calls. Enabled start is owner-first before mutable state or external services. Null/throwing owner
gate fails closed. No endpoint is registered by classload or start.

An enabled attempt obtains a disposable exactly-32-byte PSK clone from the external owner. The clone
is erased in finally on proof/KDF success or failure; the owner is released exactly once once keys
are derived or the attempt fails. Private retention needed between CHALLENGE and auth READY must
have one explicit owner and failure cleanup, not extra uncontrolled copies. Java zeroization is best
effort: JIT/GC/provider-internal copies cannot be certified erased; `ControlAuth.hmac` also clones its
key. No raw key, credential, session, transcript or authority object escapes the connector publicly.

## 4. Single-use state and exact identities

Proposed states: DISABLED, IDLE, CONNECTING, WAIT_CHALLENGE, WAIT_READY, WAIT_SESSION_OPEN, OPEN,
STOPPING, TERMINAL_API_CLOSED and TERMINAL_UNKNOWN. An attempted disabled start also consumes the
one-start opportunity. A second start is rejected in **every** state, including disabled/terminal;
no restart, replacement or reconnect path exists. API_CLOSED reports API-side closure only, not
callback/future/resource quiescence or successful cleanup.

Every listener, build/send completion and timer event carries the exact private attempt, socket
where available, generation and operation/send identity. One atomic single-assignment socket cell
reconciles onOpen and build-future completion in either order. Matching repeated discovery is not
replacement; a different or stale socket is physically aborted and cannot become current. A late
build success after invalidation is aborted even if the future was cancelled. No unbounded tombstone
map or retained completed-player/session history is permitted.

## 5. Proposed finite budgets and charging rules

| Resource | Proposed bound / relationship |
|---|---|
| Attempt/socket ownership | 1 attempt, generation 1, one assigned current socket |
| Handshake / post-auth UTF-8 message | 4096 bytes each; post-auth is frozen |
| Post-auth raw payload | frozen 2048 bytes, independently checked |
| Text fragmentation | 64 callbacks/message including empty fragments; 65th fails |
| Assembly deadline | original 5000 ms from first fragment; never refreshed |
| Ordinary retained events | 8 slots, containing at most 2 references to the fixed text lanes |
| Text storage | 2 lanes, each char[4096] (8192 bytes) + byte[4096]: 12288 bytes/lane, 24576 total primitive backing |
| Assembly | per-lane actual UTF-8 cap 4096 bytes; at most one pending high surrogate per assembling lane |
| Control | one fixed metadata cell, no retained ping/pong payload or alias |
| Reserved cells | build, send, timer, terminal: 1 each; with control + 8 ordinary = 13 cells |
| Execution permits | one owner-pump token + one emergency-physical-abort token |
| Outbound | existing session queue 8 **including in-flight**; no second frame queue |
| Connector-initiated send | one in flight across HELLO/PROOF/session/connector close/control; excludes JDK automatic traffic |
| Connect timeout | finite 5000 ms on JDK builder |
| Handshake deadline | original 5000 ms from enabled start, not from onOpen |
| Session OPEN | absolute readyVerifiedAt + 5000 ms overload implemented and independently accepted in the narrow session scope |
| Heartbeat | 500 ms interval / 2000 ms watchdog; approved local conservative missed-cadence policy in §10 |
| Graceful close | at most 1000 ms then abort |

The 13 retained cells are 8 ordinary plus build/send/timer/terminal/control metadata, not 13 arbitrary
runnables with captured payloads. Two fixed text lanes replace the earlier ambiguous 8192-byte
aggregate budget. Each lane transitions FREE → ASSEMBLING → RETAINED_EARLY or OWNER_PROCESSING →
FREE; a completed ordinary message can transfer directly to owner processing. Enqueue/dequeue or
reference transfer is **not release**. A lane becomes FREE only after every processing/verification/
send-prerequisite use finishes. No third assembler, extra message queue or hidden string stash.

Example: PROOF send completion pending, early READY occupies lane A, following SESSION_OPEN occupies
lane B. They remain retained until prerequisites permit processing; moving A to OWNER_PROCESSING
does not free it while B is retained. Ordinary processing and assembly may overlap only using those
same two lanes. Any required text-to-byte copy uses that lane's fixed backing, with bounded scanning
before copying; parser/session snapshots and crypto clones must be separately inventoried, not
silently counted as free. 24576 covers **primitive lane backing only**, not total heap, decoder/parser
objects, JDK buffers, external callback data or Error graphs. Representation/lifetime proof remains
a review item; a future implementation must demonstrate no alias is reused while live.

Receive credit is at most one, granted only with capacity for **any** demand-controlled callback.
Two occupied lanes do not imply unconditional deadlock: continuation capacity within an already
reserved ASSEMBLING lane can permit the next callback under the same bounded accounting. If both
lanes lack capacity for any possible next demand-controlled callback, pause credit and keep original
deadlines running; a compliant peer cannot know local completion timing. If progress cannot occur before expiry, fail closed; if already delivered
data cannot fit, terminate without stashing it. Control metadata cannot release a live lane or refresh
an original deadline. The numerical design is accepted only as a scoped architecture contract;
actual representation, charging and lifetime proof remain implementation gates.

One earliest-deadline timer is armed/coalesced; stale tokens are ignored by exact identity. It covers
connect/handshake/challenge/assembly/session/heartbeat/grace-close due times without one timer per
fragment or catch-up loop. Missed timer service must fail closed or use the reviewed session adapter
policy in §10; it must not generate an unbounded burst of historical heartbeats.

### One asynchronous owner bridge, not a second abort executor

The external owner lifecycle must publish STOPPED independently and observably off-owner; publication
atomically rejects future posts. Accepted queued work is not promised to execute after stop. Liveness
is conditional on the owner continuing to service its queue; no finite owner-cleanup guarantee exists.
Only one asynchronous, **non-inline** enqueue port schedules one pump. Reserved terminal/abort work
uses the same pump, not a magical second executor that is assumed available when the first fails.

Under the mailbox lock, producers validate attempt/lifecycle identity, fill a bounded cell and, only
when no pump is scheduled/running, reserve a fresh handoff identity and set SCHEDULED. The external
enqueue occurs **outside** the lock. The controlled entry first checks that enqueue has returned
accepted, exact handoff identity/state, non-inline invocation and owner gate before any session access.
An execution racing enqueue-return before acceptance is published fails closed rather than guessing
acceptance. This conservative return-race behavior is an accepted fail-closed availability tradeoff;
compatibility with the actual external owner port still requires implementation proof, not a presumed
general executor adaptation.

The pump transitions SCHEDULED → RUNNING under lock and consumes at most 13 cells per turn. It claims
cells under lock, invokes session/ports outside lock, then releases ownership under lock. At turn end,
under the same lock, either it clears RUNNING to IDLE if empty or reserves exactly one successor
handoff/SCHEDULED token if work remains; enqueue again occurs outside lock. Producers racing token
clear see either RUNNING/SCHEDULED and leave work for it, or IDLE and reserve a new token, never both.
A queued duplicate or wrong handoff cannot clear another token. Inline/wrong-owner/duplicate execution,
rejection, enqueue throw or STOPPED publishes invalidity before session access and contains physical
abort. No external callback, gate, diagnostics, enqueue, session operation or close runs under the lock.

One send cell retains the exact completion identity until owner consumption; no second completion
Runnable queue. Session-facing dedicated future failure uses a fixed **causeless** marker, not the
raw external Throwable chain. Controlled session completion Runnable and at most one original Error
reference need bounded ownership/retention rules; an Error's reachable graph is outside the primitive
budget. Post/abort wrappers are nonthrowing and fill/coalesce the same reserved terminal cell. If the
pump cannot be posted, atomically invalidate, stop sends/demand and emergency-abort physically; do
not touch session/fence/keys off-owner. Owner cleanup remains UNKNOWN if it never runs.

## 6. JDK 17 physical adapter semantics and ingress

Official JDK 17 references recorded by the prior oracle as retrieved **2026-10-24** (not newly fetched
or network-validated by this document-only writer):

- [WebSocket](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/WebSocket.html)
- [WebSocket.Listener](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/WebSocket.Listener.html)
- [Listener.onPing](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/WebSocket.Listener.html#onPing(java.net.http.WebSocket,java.nio.ByteBuffer))
- [WebSocket.Builder](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/WebSocket.Builder.html)
- [java.net.http package](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/package-summary.html)

Per-socket listener callbacks are serialized/nonreentrant; that does not serialize build/send futures,
timers or owner work, and does not put callbacks on the server owner thread. Returned completion
stages govern safe reuse of callback data, **not** postponement of all later listener invocations.
Demand counts receive-method invocations including text fragments, binary, ping, pong and close;
onError is ungated. Default onOpen requests 1, so the adapter overrides it and owns demand explicitly.

Grant exactly one credit only under §5 capacity rules. Replenish after owner consumption/release of
callback storage, including non-final fragments; an active assembly can accept its next fragment
within its reserved lane, without waiting for the entire message to complete. Control frames
do not refresh assembly or handshake deadlines. Copy callback text into its fixed lane before
completing the returned stage; never keep a JDK buffer/CharSequence alias. Control callbacks retain
only bounded metadata, no payload aliases/copies. Validate length,
fragment count and available budget **before allocation/copy**. Count actual UTF-8 bytes with bounded
scanning, preserve at most one split high surrogate, and reject malformed surrogate sequences/final
dangling surrogate. Empty fragments count. Reject binary without copying. JDK already allocated
callback/HTTP/TLS buffers are not claimed bounded by this adapter.

buildAsync/onOpen order is unspecified for this design; cancellation is not cleanup. A completed
send means local send completion, not peer acknowledgement; failed send does not prove zero bytes
reached the peer. JDK text/binary send exclusion and separate control rules are not relied on as
the whole concurrency policy: the arbiter covers **connector-initiated** sends only. `sendClose` closes output
only; input may remain open. `abort` closes both API sides, but is not future/callback/resource
quiescence, and no terminal callback is assumed to arrive after it. A finite connect timeout is
mandatory; late build results are still fenced/aborted by identity.

JDK 17 WebSocket class semantics include automatic Pong responses irrespective of an overridden
onPing. The default Listener.onPing implementation only requests one additional invocation and
returns null; overriding it replaces that default demand behavior, **not** core automatic Pong.
Do not send an explicit Pong. Override default demand and use explicit credits; control cells keep
fixed metadata only. No total physical single-flight guarantee, auto-Pong completion/ordering or
internal JDK buffer bound follows from our local send arbiter. Returned callback stage controls
buffer lifetime, not an auto-Pong barrier. [RFC 6455 §§5.5.2–5.5.3](https://www.rfc-editor.org/rfc/rfc6455#section-5.5.2)
requires identical response payload, with close exceptions and permitted coalescing of multiple
unanswered Pings; do not claim one observable Pong per Ping. These are official-source findings
retrieved 2026-10-24, not a runtime/JDK-build attestation.

Ingress is a small thread-safe bounded mailbox only. Listener methods are short and nonthrowing;
terminal notifications coalesce a stable sanitized reason code/severity, not arbitrary exception
messages/chains. If the first Error is retained for owner rethrow, that one reference is explicitly
outside the raw-byte budget, not an assertion its object graph is bounded; review may forbid retaining
it. Diagnostic exceptions are contained. No foreign-thread session access, local fence or key cleanup.

If ordinary posting is rejected/throws: publish invalidity, stop demand/new sends, populate/coalesce
the reserved terminal cell on the same pump, and use the one emergency physical-abort token on the callback thread if needed.
If owner cleanup never executes, report it UNKNOWN, not complete. Normally an available owner first
invalidates and invokes local fence **before** graceful physical close. Emergency callback-thread
abort may precede fencing: this is an explicit containment **exception**, not normal successful
cleanup or permission to mutate owner state off-thread. Fatal VM damage has no recovery guarantee.

## 7. Actual handshake, original deadlines and private session

Use strict bounded JSON with exact keys, duplicate rejection, no coercions/extensions and canonical
field primitives. HELLO keys are security/kind/peerRole/peerInstallId/hubInstallId/keyId/protocol/
modHash/configHash/bindingHash/peerNonce. CHALLENGE keys are security/kind/challengeId/hubNonce/
issuedAt/expiresAt/hubProof. PROOF keys are security/kind/challengeId/peerProof. Auth READY keys are
security/kind/challengeId/readyProof. Require exact security domain/kinds, canonical nonzero UUID,
canonical B32 encoding of raw 32-byte nonces/proofs/hashes, and round-trip canonical UTC millisecond
timestamps. Fresh peer nonce comes from the supplied cryptographic source, never a predictable
counter/test nonce in production.

CHALLENGE context is built from exact trusted role/IDs/protocol/artifact hashes plus the actual sent
nonce and received challenge. Verify HUB_PROOF against that full transcript; require expiresAt =
issuedAt + 5000 ms, existing 1000 ms future-issuance tolerance, and reject now >= expiry. Reject UTC
or monotonic rollback, negative monotonic values and arithmetic overflow. At challenge receipt:
`challengeDeadline = min(originalHandshakeDeadline, receiptMono + remainingUtcLifetime)` using
checked arithmetic and validated remaining lifetime; fragments/control/PROOF completion never refresh
it. No challenge/proof retry. Keep the original enabled-start handshake bound through auth READY.

An early READY or SESSION_OPEN is **retained**, not rejected merely because the local PROOF send
future completion has not been consumed or session installation is pending. A compliant peer cannot
observe those local milestones. Defer processing/verification until required sends/currentness
prerequisites settle; keep original handshake/challenge deadlines and discard on failure/expiry.
The two-lane example in §5 is required, not an extra queue or permission to reject early messages.

At actual owner READY_PROOF validation success, recheck deadlines/currentness and capture
`readyVerifiedAt`; only this success retires handshake/challenge deadlines. Set absolute OPEN deadline
with checked `readyVerifiedAt + 5000`. Derive privately and construct once; KDF, completion handoff or
constructor delay cannot extend that OPEN deadline. The baseline constructor starts a fresh deadline;
the absolute-deadline overload prerequisite is now implemented and independently accepted in its
narrow source/evidence scope. Assembly deadlines remain attached to their original messages.

The accepted new overload contract validates required references and generation, then applies the
owner gate before any clock or lifecycle call. Read the real monotonic clock exactly once. Require
`deadline >= 5000`, set `origin = deadline - 5000`, and require `now >= origin && now < deadline`.
Store the supplied deadline unchanged: no normalization or extension. The absolute context supplies
trusted timing, **not authentication provenance**. The caller owns keys until successful constructor
return: **every throwing new-constructor path leaves keys unclosed and invokes neither fence nor
physical close**; caller failure cleanup owns the best-effort key close. Existing `checkedNow` /
`checkedDeadline` fatal paths cannot be reused before ownership transfer. Preserve the legacy
constructor signature, relative 5000 ms behavior and current failure cleanup, including overflow.
These session requirements now have scoped source/evidence acceptance, not connector integration proof. Public Transcript/KDF
success is not authentication proof. Release/erase PSK ownership on derivation or failure as in §3.

Adapt exact session ports: `OwnerThreadGate`, `MonotonicMillis`, `OwnerPost`, `OwnerAbort`,
`CleanupDiagnostics`, `AsyncSender`, `ApplicationFrames`, `Lifecycle`. Session stays private with no
public business sender/candidate API escape. ApplicationFrames rejects **all** business/application
frames; only session bootstrap/heartbeat are allowed. Actual SESSION_OPEN must validate MAC/context
and sequence 0; session admits its first outbound sequence-0 exact `{}` HEARTBEAT ACK. This is not
binding or application admission. HELLO, PROOF, session sends and connector control/close use one
connector-initiated single-flight arbiter (not JDK automatic Pong traffic). Retain early unprocessed
READY/SESSION_OPEN within the fixed lanes until previous send completion is settled; do not label
them authenticated before actual validation or expose session/open success before those
identity/deadline checks. Existing session queue remains the sole 8-frame queue including flight;
the connector must not silently insert another frame queue to solve ordering.

## 8. Cleanup and separately reported outcomes

One owner cleanup transaction sets invalidity/transaction guards **before** invoking any external port.
With an installed session, connector close invokes session.close exactly once. Session fatal's
Lifecycle.fence adapter sets/checks the invalidity guard, attempts external local fence once and
remaining credential cleanup, but never recursively calls session.close. On successful constructor
ownership transfer the session owns derived keys; before that, or with no session, connector cleanup
independently attempts caller-owned keys and credential release. Reentrant ports cannot repeat
fence/session close or acquire replacement authority; external-fence failure records UNKNOWN.

Session Lifecycle.physicalClose **records intent only**. Every owner entry (receive, tick, send
completion, abort, start and close) runs within an outer try/finally that flushes pending physical-close
intent **after** the independent key-close attempt, even when session.fatal throws. Then graceful close
has at most 1000 ms before abort. Session current implementation orders fence→keys.close→physicalClose;
the record-only callback and outer envelope must preserve that order across error/reentrancy paths.
All independent cleanup attempts run despite earlier failures; preserve Error priority and rethrow
only after best effort. Diagnostics cannot mask errors or skip cleanup. No recursive lifecycle close,
double fence, double credential release or wait for an unpromised abort callback. A foreign emergency
physical abort can precede owner fence only as containment; owner cleanup stays UNKNOWN, never success.

Track application terminality, owner cleanup performed/unknown, input/output API-side state,
build/send futures settled/unsettled and resource closure UNKNOWN **separately**. A timer timeout,
cancelled future, session CLOSED or both API sides closed cannot stand in for all these outcomes.
No callback quiescence or reclaimed resources is claimed without independent evidence. No new
authority is created by a close, ready state or diagnostic success.

## 9. Required future validation, not executed evidence

Tests must use real ControlAuth/KDF/frame codec/session and a deterministic physical adapter, never
an injected authenticated-success flag or public forged capability. Required cases:

- Disabled construction/start zero calls; owner-first enabled entry; every second start rejected.
- Actual crypto handshake → SESSION_OPEN → seq0 ACK; wrong proof/hash/role/nonce/ID, malformed exact
  schema/duplicates/canonical fields, original deadline edges, UTC/mono rollback and overflow.
- onOpen/build completion both orders, different/stale sockets, late build after cancel/abort,
  send/timer completion identity races, STOPPED publication, enqueue-return race, inline/wrong-owner/
  duplicate pump, token-clear/reschedule races and same-pump reserved-terminal rejection/throw.
- Fragment 64/65, empty fragments, 4096/4097 bytes, two-lane/slot saturation, split surrogate
  UTF-8 boundaries, malformed text, binary/control handling and concurrent reserved-cell pressure.
- Physical send order, early READY/OPEN, queue includes flight, stale callbacks do not mutate current
  state; PROOF pending + READY lane A + OPEN lane B remains legal, with no local-completion-based
  rejection; absolute OPEN expiry survives delayed KDF/construction and failed key ownership transfer.
  Single-read cadence exact due/due+499/due+500 and watchdog-first boundaries have no catch-up burst.
- Automatic Pong is not explicitly duplicated or counted as connector single-flight; callback-stage
  completion is not used as a Pong completion barrier, and no runtime auto-Pong evidence is fabricated.
- Owner invalidation/fence before ordinary close, explicit emergency-abort-before-fence exception,
  timeout/abort without callback, unsettled futures and late socket discovery.
- Cleanup Error/diagnostic failures, release exactly once, no recursion/double completion,
  application rejection and no BODY/admission/coordinator/permit/epoch construction.

Record exact source hashes, actual JDK version, XML counts, bounded-accounting counters and deterministic
call traces. Deterministic adapter evidence is **not network or Minecraft/runtime PASS**. Preserve
earlier stage evidence scopes and failures rather than relabel them connector acceptance.

## 10. Review gates and specific current API gaps

1. **Session API prerequisite, implemented and independently accepted in its two-file scope:**
   the reviewed baseline `tick()` loops over missed 500 ms slots (`PostAuthPeerSession.java:166-170`)
   and its constructor uses construction-time OPEN expiry (`:85-88`). The accepted absolute OPEN
   constructor contract is in §7. The approved conservative **local availability policy** uses the
   single actual clock read from `beforeOperation`, with watchdog checked first. Before due, send
   nothing. At due through due+499 ms, admit exactly **one** heartbeat and checked `next += 500`;
   at due+500 or later, close with `cadence_missed`, without historical burst or reanchoring.
   Checked arithmetic overflow fails closed. It can close before the 2000 ms watchdog and is
   **not a frozen-protocol mandate**. Ora18 and parent accepted the precise session bytes and evidence
   as scoped PASS-with-residuals. Do not use
   external prechecks, synthetic time or a test-only replica as production proof. Design acceptance
   and session source acceptance are not acceptance or authorization of full adapter work.
   Accepted session SHA-256 is `2CFAA43726530E98F9C092A0AD69AF7C9ADE526177C48E6E120650D7E2C485C1`;
   paired-test SHA-256 is `6C45A98246C81A143C9A3A15EED85A1B6DAE1566949EBB9562FB524109E52AF8`.
   Archive `Temp/opencode/session-timing-20261024-212043`: focused 1 suite/24 tests with
   0 failures/errors/skips; full 54 suites/363 tests, 0 failures/errors and 6 existing loopback
   skips (ControlTransportLoopbackTest 2, PostAuthV2BodyRouteLoopbackTest 1,
   PostAuthV2LoopbackTest 2, PostAuthV2VerifiedBodyProofLoopbackTest 1). Compile/test/build/reobf
   succeeded; 3/3 source, 57/57 artifact and 27/27 static-fixture identities were verified.
   Source manifest SHA-256: `27F33A221B6F200C0C0925F827C6C059EDB24D88F0455C021EB94A27EB23DAE5`;
   artifact manifest SHA-256: `F49EC25DD14A15213AF47D0E073DF0AC9C0DEAF880E0B4CCFA70F0B6A476E811`.
   Frozen-spec SHA-256 remains `1E2780004BABEA1C6119DC21D3A39A7C9AE19FABA5BB3225949C6047BC85B374`.
   Residuals: gate Error lacks every special-path injection, reflection-driven overflow is not
   runtime proof, and no actual connector/mailbox/timer integration is exercised. No new cross-repo
   interop ran: the earlier 18-case codec interop and historical 355-test full run remain distinct.
2. **Unwired session-send/completion adaptation accepted; full integration open:** session completion callbacks can call `requestOwnerAbort`, which
   may rethrow Error and attaches suppression (`:224-238,267-284`); cleanup helpers also rethrow Error
   and retain Throwable graphs (`:314-342`). Wrapping only calls into session is not sufficient proof
   that future completion-registration bodies are nonthrowing. Connector-supplied post/abort ports
   must implement the controlled one-pump/cell protocol in §5, catch boundary failures,
   publish/coalesce invalidity and reserve owner cleanup without
   off-owner session access. The accepted narrow `V2SessionSendAdapter` slice below supplies scoped
   implementation/tests, not actual connector or arbitrary-future compatibility. Full completion-stage
   registration and Error propagation integration still need explicit production-port evidence. If exceptions escape a callback
   path that cannot be intercepted, this is an implementation API-gap BLOCK, not a reopened design
   ambiguity or permission to swallow it silently or broaden session edits.
3. **Custody/provisioning absent:** no Java verified filesystem/credential-owner production adapter
   exists in this proposal. Trusted input ports are design dependencies, not public wrappers that
   magically authenticate caller-supplied bytes. Production remains unwired/default-disabled until
   independently verified provisioning exists; constructor validation cannot establish it.
4. **Unwired three-cell mailbox accepted; full adapter/owner proof remains open:** external STOPPED publication, non-inline enqueue return race,
   scheduled/running/handoff identity protocol, two-lane representation/lifetimes, reserved completion
   cells and timer services need actual production primitives/contract tests. Accepting a Runnable is
   not proof owner cleanup ran, and stop permits no eventual-execution promise. No independent abort
   executor is assumed. Early-message retention (not local-completion-based rejection), cleanup outer
   envelopes and key ownership on constructor failure must pass independent review. No blocking join
   on a game thread. Accepted staged sequence: independently reviewed session amendments,
   then bounded production adapter primitives/tests without wiring, then a separately authorized
    full connector. The accepted session, three-cell mailbox and session-send prerequisites do not authorize the
   next stage or full connector/adapter assembly and do not close remaining production integration gates.

### Accepted unwired mailbox slice (not the 13-cell adapter)

Ora18 and parent accepted `V2OwnerMailbox.java` and its paired test as scoped source/evidence
PASS-with-residuals after P1A/P1B remediation. The prior P1 BLOCK and 385-test run remain truthful
historical records, superseded by the accepted final 391-test run, not retroactively successful.
This production primitive is **unwired**, with only send/timer/terminal cells and at most two ordinary
plus one reserved terminal execution per turn. Error triggers best-effort terminal handling before
rethrow when a valid owner permits it. Infrastructure failure permits one explicit terminal handoff,
not automatic retry. First reason is retained and notification is once-only; STOPPED/bad gate leaves
owner cleanup UNKNOWN. The same Runnable coalesces; a different completion yields SEND_CELL_FULL.
The real-session immediately-completed-next-send limitation remains: this is not arbitrary future
compatibility. Preacceptance execution fails closed as an availability tradeoff; actual production
port liveness/non-inline/STOPPED behavior is unverified. Production search found no external
instantiation. No 13-cell/byte-ingress/authentication/socket-currentness/cleanup-quiescence or full
connector proof follows.

Archive `Temp/opencode/v2-mailbox-remediation-20261024-215109`: focused 2 suites/52 tests,
0 failures/errors/skips, 41.992 s; full 55 suites/391 tests, 0 failures/errors and 6 existing
loopback skips, 54.179 s, actual build/reobf success. Verified 3/3 source, 59/59 artifacts and
105 existing unchanged identities. Mailbox SHA-256:
`A5506D21881835237E096F0F85789D054B61D63A7D8CBC4B4ABAAA5BE58F9DDB`;
test SHA-256: `A929CFC904A026828095F7C63D4167B83A20971DFAFD6E828218BBEC7435830C`.
Session remains `2CFAA43726530E98F9C092A0AD69AF7C9ADE526177C48E6E120650D7E2C485C1`.
Source manifest: `5608749229E953A12FADFFB67CB5BA7ACDAA364F6D76FCAB2A684365FF84D193`;
artifact manifest: `0A87BD6A38BDDD06166DA18A7C5747A329A6E37667EB5CA04A9FA38F6074656C`.
Session `2684526`/363-test acceptance and earlier 18-case cross-repo interop are separate historical
evidence; no new cross-repo interop ran. No future mailbox commit hash is asserted.

### Accepted unwired session-send adapter; OwnerAbort P1 closed

Ora18 and parent accepted scoped source/evidence PASS-with-residuals for `V2SessionSendAdapter.java`
and its paired test, with reviewed bytes pending commit and no guessed future hash. It provides one
physical flight, one cloned frame, fixed causeless failure, once-only containment and the same mailbox
owner ports. OwnerAbort **always delegates to the arbiter even when invalid**; a false result may
schedule one explicit recovery handoff. Real crypto/session physical-failure tests confirm owner-only
terminal cleanup, no foreign-thread cleanup and unchanged once-only containment. OwnerEnvelope and
actual ports remain externally trusted dependencies, not an implemented production cleanup transaction.
Logical future completion is not physical settlement; an unknown/unsettled frame may persist.
Immediately completed next sends can still yield SEND_CELL_FULL, with no extra queue. This unwired
three-mailbox-cell slice is not 13-cell ingress, auth provenance/transcript verification, socket
currentness or resource quiescence. Actual connector/JDK socket/shared HELLO-PROOF-session-close
arbiter/ingress/production ports remain absent; full connector remains unauthorized. The next stage
requires its own bounded plan/review/authorization, with no inferred scope.

Archive `Temp/opencode/v2-send-abort-remediation-20261024-222042`: focused 3 suites/77 tests,
0 failures/errors/skips, 53.208 s; full 56 suites/416 tests, 0 failures/errors and 6 existing
loopback skips (same 2/1/2/1 suites), 72.443 s; actual build/reobf success. Verified 4/4 source,
61/61 artifacts and 107 existing unchanged identities. Source/test SHA-256:
`DED865A8946C1369C3FC64E45E36202D8EA521F37880F858D56759DFA786B450` /
`456D720955CCD7DD04F0C24EF256D4597224D6C6D590957EE176D0B110171447`.
Source manifest `090362289B937036B5E661C74024A21463207219016AB3CCA0A421400E8997C7`;
artifact manifest `E44E84D35B5E4FB5887F51A735DBA193663CB6A66963AF1E106134F4F3BD148C`.
The prior 413-test run/OwnerAbort BLOCK remain historical, superseded after remediation, not erased.
Mailbox `c2769cc`/391-test and session `2684526`/363-test remain distinct stage anchors; no new
cross-repo interop or network execution is claimed. Frozen v2 and local-only upstream `57e46850` are unchanged.

### Current accepted six-file deterministic connector core

Ora18 and parent accepted scoped source/evidence PASS-with-residuals after cleanup/currentness/
nonzero-nonce P1 remediation. Reviewed bytes remain pending commit; no future commit hash is asserted.
This supersedes earlier core-absent/full-core-unauthorized descriptions above only for the accepted
default-disabled **UNWIRED deterministic core**, not JDK network/startup/production-port assembly.
It extends the same mailbox to 13 cells, round-robin at most two nonterminal plus one terminal per
turn, with two fixed lanes totaling 24576 primitive backing bytes, **not total heap**. Actual crypto
HELLO/CHALLENGE/PROOF/auth READY creates private verified provenance, then the real absolute-OPEN
session validates MAC-valid SESSION_OPEN and emits sequence-0 ACK. Early READY/OPEN are retained.
Adapter unsettled query is owner-only, not resource-quiescence proof. `cleanupSnapshot` keeps owner
UNKNOWN sticky independently of input/output API-closed flags. External-reentry post-boundary checks,
local secret and session ownership address the accepted P1s. Peer/hub nonzero nonce shape is checked,
not proof of production randomness. Immediately completed next sends can still yield SEND_CELL_FULL;
no hidden queue is added. At that core slice, external production callers/JDK adapter/real owner
ports were absent; the later JDK adapter source acceptance below does not supply callers or owner ports.
No BODY/admission/store/permit/binding READY or runtime/network/activation authority follows.

Archive `Temp/opencode/v2-connector-remediation-20261024-231224`: focused 4 suites/121 tests,
0 failures/errors/skips, 47.0855566 s; full 57 suites/460 tests, 0 failures/errors and 6 existing
loopback skips (ControlTransportLoopbackTest 2, PostAuthV2BodyRouteLoopbackTest 1,
PostAuthV2LoopbackTest 2, PostAuthV2VerifiedBodyProofLoopbackTest 1), 55.0754280 s; exit 0 and actual
build/reobf success. Verified 6/6 current files, 111/111 all-source identities and 98/98 artifacts.
Final six-file manifest `26EE030207F0B82C6D21223C9BEB07E402D94744DE17929B92A4C105B1DE5FE5`;
all-source manifest `601B021F8304EC50781E48F2670394D3C8EECD2C10B8855616E07A78B185D378`;
aggregate manifest `C4DAE204E672229CD9C015EA641B205DAF476124882EE17D2526DF5FB98BF699`.
The previous 110/449 evidence and missing-handoff/internal-review BLOCK remain genuine superseded
history, not retroactive PASS. That prior archive had no aggregate manifest; this latest archive has one.
Previous 416/391/363 counts remain historical stage evidence, not the latest full count. No new
cross-repo interop/network/runtime/exhaustive-interleaving/formatter claim is made. Further stages
require a separate bounded plan/review/authorization. Frozen v2/local-only upstream `57e46850` and
four UNAVAILABLE/complete=false/epoch/Java-filesystem-custody/runtime Mixin/G3/READY gates are unchanged.

### Current accepted UNWIRED JDK WebSocket adapter source

Ora18/parent scoped source/evidence PASS-with-residuals accepts only `V2JdkWebSocketAdapter.java`
and its paired test, reviewed bytes pending commit without a guessed future hash. Earlier
JDK-adapter-absent statements are historical for those slices: the source now exists, but remains
unwired with no external caller. It stores an external HttpClient/supplier inertly, enforces exact
socket identity/cancellation/late-socket containment and explicit request credits without default
demand or explicit Pong. Sending is strict bounded UTF-8 with no JSON rewrite; the private close
gate separates input/output. onClose gate stays pending until successful **explicit** close/abort,
not cleanup quiescence. Real core/crypto/session bootstrap is exercised under a fake JDK surface,
not real network, authentication activation or runtime proof. External client proxy/authenticator/
executor policy and real owner ports remain unverified. Automatic Pong/Close wire behavior is not
tested; core 24576 primitive backing bytes exclude String/JDK buffers and total heap. No startup,
loopback/network execution or activation authorization follows; the next stage needs its own bounded
plan/review. Four UNAVAILABLE/complete=false/epoch/Java filesystem custody/runtime Mixin/G3/binding
READY/delivery gates and local-only upstream `57e46850` remain unchanged.

Archive `Temp/opencode/v2-jdk-adapter-20261024-233142`: only `focused-verified`, `full-verified`
and `verified-jars` are final evidence. Earlier compilation failure and stale `*-final` directories
remain historical, not final evidence. Focused 3 suites/73 tests, 0 failures/errors/skips,
35.8166049 s; full 58 suites/476 tests, 0 failures/errors/6 existing loopback skips
(ControlTransportLoopbackTest 2, PostAuthV2BodyRouteLoopbackTest 1, PostAuthV2LoopbackTest 2,
PostAuthV2VerifiedBodyProofLoopbackTest 1), 42.7547901 s; actual test/build/reobf exit 0.
Actual JDK: Eclipse Adoptium 17.0.20.1, runtime 17.0.20.1+1, distinct from API documentation research.
Verified before 111/111 unchanged, final 113/113 current sources and aggregate 106/106 artifacts.
Before manifest `601B021F8304EC50781E48F2670394D3C8EECD2C10B8855616E07A78B185D378`;
final manifest `D457C718EB524DAC33C2F993413E4CA1B8C57D5105F818711C70BFC73D3917D1`;
aggregate `6FE87B499197144C772687D76C687FF7D898D12880052E087DEE40401733751A`.
Adapter/test SHA-256 `687A62099C7CEBC6C704FCBA9990A469B7B2FA394DCC094E2D434092A1AEA55D` /
`4A3A402B5CD06BDDBB9BEB304A25FE845FE8D692B981B4A7DFE756F501219C98`.
The 460-test core acceptance in `d514529` remains distinct historical evidence; latest full count
is 476. Six skips are not network PASS; no new cross-repo interop is claimed.

### Test-only loopback harness source/mock acceptance; network/native gates open

Parent accepted ora18 scoped source/mock PASS-with-residuals after native-ownership P1 closure,
**not native containment or network acceptance**. The three new harness files remain reviewed bytes
pending commit, with no guessed future commit hash. Runner/test/JUnit-source SHA-256 respectively:
`8AB21596B1933C551C4F1C42755E4247091D3205D311CD70673E7280AE3C3AD6` /
`63BC2471C6A51E1F69ED266995704A1A76CACB5A3F221FAC9737866381DC9630` /
`EF00DF4BCF8D78ADAE91A01F136A73D65AFE74BFE3A6FEEAEBCF3140A2FB83C4`.
Bounded output, exact trace and independent finally cleanup were reviewed. Native cleanup UNKNOWN,
root assigned and exit confirmed are distinct outcomes; none substitutes for the others. Prior BLOCKs
remain historical, closed at source/mock level rather than retroactively successful native runs.

Archive `Temp/opencode/v2-native-ownership-20261025-002558`: 22 Python tests, 0 failures/errors/skips,
4.7215235 s, exit 0; verified 3/3 new files, 148/148 sources and 14/14 artifacts. Final-source manifest
`CC712E2318D3C56882F4FEF4B6885D934A54AC56969D428941D767448730519D`;
three-file manifest `F959D2F5450BB67D1E73D9FABE7C7F11C3D8EA8C2220208496395D30696F09AB`;
artifact manifest `A592A416B166BE927BB6461E7D44ADE0982F8CA1D92E8D4ED6282E4516141032`.
Unchanged Java evidence is reused from `Temp/opencode/v2-harness-safety-20261025-001207`:
2 helper tests and full 60 suites/481 tests, 0 failures/errors/9 skips (3 new network opt-ins plus
ControlTransportLoopbackTest 2, PostAuthV2BodyRouteLoopbackTest 1, PostAuthV2LoopbackTest 2,
PostAuthV2VerifiedBodyProofLoopbackTest 1). This is not new Python coverage or network PASS.
At that source/mock slice the labels were **NETWORK_NOT_RUN / NATIVE_CONTAINMENT_UNVERIFIED**;
the separately accepted four-case local native smoke below supersedes the blanket native label only
within its recorded scope. NETWORK_NOT_RUN remains; no loopback runner PASS is inferred.
Option A Hub counters remain NOT_EXPOSED, application jobs NOT_MEASURED; source worker absence is
not measured runtime absence. Exact local-only upstream `57e46850`, committed-clean preflight before
any future actual network run, exclusive-workspace/tool trust and readiness-attribution limitations
remain required. Production remains unwired; four UNAVAILABLE/complete=false/epoch/filesystem custody/
runtime Mixin/G3/binding READY gates are unchanged. No new build/network/activation acceptance follows.

### Accepted four-case LOCAL NON-NETWORK native smoke

Ora18/parent accepted scoped PASS-with-residuals for four actual runner OwnedTree/LoggedProcess/
WinAPI cases on Windows 10.0.29695 AMD64, Anaconda Python 3.10.21 64-bit: natural root exit,
child Job termination, spaced .bat path and real 2 MiB quota. Default invocation skips all four;
the explicit native command passed 4 cases, exit 0, wall 1.9103160 s/pytest 0.63 s. Tree/quota
cases have expected `forceKilled=true`: those negative underlying runs never count as runner PASS.
Recorded cases finish root/tree confirmed, active count 0, readers ended and cleanupUnknown=false;
this is not global OS quiescence. Handle closure records runner attempts, not a global audit.
Child PID comes from the owned root, with a read-only handle wait, not independent creation-time/
membership proof. All nested jobs, quoting and permission-failure modes are not proven; temporary
evidence is retained. The historical 22 mock tests and Java 481 tests/9 skips are reused evidence,
not reexecuted here or native coverage. Native smoke is not network acceptance.

Archive `Temp/opencode/v2-native-smoke-20261025-003541`: verified 28/28 artifacts, 3/3 frozen files
and 1/1 new native test. Native test SHA-256
`95A596B1C6E5DAC850466A356B50C72C020AE35E0B569398137B23E5D62278F9`;
frozen manifest `2585BECCA02A604904B479CA1D431C164D65181547A26E287ED1A29A519016F6`;
new manifest `024E65EAFB32F496DCF470CF341330A140ACEDC647EC63FCF5D266841856DB1E`;
aggregate `83C4ACF14A9361F3423EC914F5D45DC41DA3BE11B57315192DDBBE6DD5813C5B`.
Four reviewed new harness/native-test files remain pending commit, no future hash guessed.
NETWORK_NOT_RUN remains; future dummy loopback requires committed-clean preflight, strict pins and
separate parent execution authorization. Prior BLOCK history remains intact. No G3/epoch/Java custody/
runtime Mixin/binding READY/delivery/four UNAVAILABLE/complete=false gate changes follow.

### Latest probe metadata/diagnostic source/mock acceptance

Ora18/parent scoped source/mock PASS-with-residuals accepts the two-file Python amendment, pending
commit without a guessed future hash. Required Python/uvicorn metadata stays strict; only optional
PackageNotFoundError maps to NOT_INSTALLED. AUTO selectionEvidence=NOT_OBSERVED: metadata is not
actual import or backend selection. No package installation/backend change follows. Failed probe
receipts preserve bounded fully redacted combined identity, numeric exit-or-UNAVAILABLE, size/hash
and native cleanup; copy failure leaves private evidence UNKNOWN. Trust exclusive workspace/tools;
raw private logs remain sensitive.

First actual ONE attempt `Temp/opencode/v2-actual-run-20261025-004540` remains BLOCKED /
LOCAL_PROBE_FAILED, networkExecuted=false, 0 cases. Report SHA-256
`E54F46BA2B9DD6D69E8EEAD495D08C6B848F355981A445F53A65E6EEA24A4DC7`;
PID 20808 exit confirmed/no force/no UNKNOWN cleanup. Missing wsproto is LIKELY, NOT PROVEN:
original stderr was deleted. No retroactive PASS. Subsequent network NOT RUN; reviewed committed-clean
bytes and separate parent execution authorization are prerequisites.

Offline archive `Temp/opencode/v2-probe-receipt-20261025-005102`: 28 tests, 0 failures/errors/skips,
3.8852325 command seconds/exit 0; 2/2 source, 149/149 all-source, 13/13 artifacts verified.
Runner/test SHA-256 `E2B72DD2B0E1608FAA3CC694DC99B2206103F218C329DD91D80B490B24A96731` /
`E40F4BAF119FB7A97EF64008BCAAD3E28353017757AEA0229718DC192FFE57F3`;
final all-source manifest `94C3D5B130C5A709864D73CF8B6D475D366AC18D5A383F42B01D05024DAEAF89`;
two-file manifest `95C0A76B65F2F9D4B458C2FE8C95401479C6BC63111439091BD8D299352C7EAF`;
aggregate `77C70C7030ECC900D2A3B41B1D7D6BB9E754C7BF575567DDE4A32C5769CF3A70`.
No actual new version probe/native/Java build; native four-case and Java 481/9 skips are distinct
historical evidence, not new Python coverage. Harness/source/native anchor `3c714f1`, strict local-only
upstream `57e46850` and four UNAVAILABLE/complete=false/epoch/custody/runtime Mixin/G3/binding READY
gates remain unchanged. No capability or activation upgrade.

Thus actual production owner ports, network cleanup integration and verified provisioning are
gates, not implemented preflight. Four observation scopes CHAT_DEFERRED, SCHEDULED_SERVER_TASKS,
PERMIT_QUEUE_WATCHDOG and CLIENT_PERMIT_CONSUMPTION remain UNAVAILABLE; production complete=false.
Runtime Mixin residual, READY authoritative producer/reviewed delivery and epoch capability gaps
remain. No G3 acceptance, startup wiring, registration, activation, EULA or provider consent follows
from scoped architecture acceptance or this documentation-only amendment.
