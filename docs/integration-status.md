# Minecraft integration status

> Reconciled accepted evidence through Sandbox `38f59cd83ac07360f7e98bc73ee4286cb58c7ceb`: movement `7c4f68a`, incomplete local observation `61dc228`, deferred offline structure `4ee697f`, and mod-owned container-close accounting `38f59cd` are scoped PASS-with-residuals, not overall G3 or complete quiescence. Latest accepted upstream local-only anchor is `57e46850c56079a4e95c645234b696eebbaaaf4f` (clean, no push/PR); `09687d48` remains the historical headless-admission anchor. No push, PR, release, or tag is claimed for Agent-Luo. This is a scoped delivery status, not a runtime, EULA, world, provider, body-binding authority, permit, input-control, or release approval.

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
| Local verified-proof provenance | Scoped source PASS | Sandbox `1b68d40`: verifier-private proof construction retains the original local monotonic deadline, verifier identity and exact connection; acceptance is one-use `AVAILABLE` → `CONSUMED` or terminal `INVALIDATED`. Ingress close is permanent generation withdrawal; pending plus available-proof capacity is shared at 2 with no TTL reset. Observer failure invalidates the exact proof before physical close, and successful callbacks are followed by a generation recheck | Suite at that slice: 216 tests in 36 suites, 0 failures/errors, 2 explicit opt-in loopback skips; build/reobf and independent source review passed. Acceptance remains package-private with a test-only bridge; the public invalidation operation can only deny. This is local provenance, not body-binding authorization |
| Post-auth v2 crypto/frame vectors (test-only) | `MATCHED_UNATTESTED` | Sandbox `61a82ae` adds independent Java/Python vector composition, strict source gates, and an external Java JSON numeric comparison. That standalone comparison report is `MATCHED_UNATTESTED`; parent also observed a separate forced two-JUnit crypto execution with zero skips | Full Java suite at that slice: 218 tests in 37 suites, 0 failures/errors, 2 explicit opt-in loopback skips. Neither evidence path attests hardware, runtime transport, production implementation, or v2 activation |
| Post-auth v2 raw config fixture (test-only) | Scoped test-fixture evidence | Sandbox `2c8b541` tests the 174-byte LF raw-config fixture and SHA-256 prefix `ccc9…`; with `core.autocrlf=true`, checked-out fixture bytes were preserved. The fresh config force was writer evidence; raw-hash/LF mechanics were parent-verified | Full Java suite at that slice: 220 tests in 38 suites, 0 failures/errors, 2 explicit opt-in loopback skips. This proves neither a production loader nor manifest format, packaging, or deployment |
| Post-auth v2 bootstrap and admission models (test-only) | `MODEL_TESTS_PASSED` | Sandbox `fdbd93a` adds 16 focused bootstrap-queue model cases; `9cd9e85` adds 31 focused admission/worker cases covering route identity, snapshot-worker-head identity, capacity 2, early `READY`, and generation fences. Parent reran the focused 16/31 cases; the explicit job-identity review remedy was parent-verified | These are models, not actual `asyncio`, SQLite, or production coordinator behavior. Scoped reviews closed except final explicit mechanical remedies (OSError sanitization, LF checkout, and job identity), which parent verified; no fresh Oracle final PASS is claimed |
| Epoch store cleanup + worker ownership evidence | Scoped source/probe PASS | Upstream `70dcafb1` fixes ordinary/fatal/reentrant/concurrent native close uncertainty: operational authority is removed first, uncertain cleanup is terminal/no-retry, and capability remains disabled. Sandbox `0267fdf` introduced the probe; `83065af` repinned it to `70dcafb1`. Parent recorded 13 focused tests and `epoch-worker-70dcafb1-report.json` observed temporary one-thread SQLite lifecycle with native Windows lock-only exclusion/reacquisition and clean probe cleanup. Related evidence passed 46 tests with 1 explicit skip | Test-only worker probe, not production worker. Store itself does not enforce affinity; ACL/VFS/durability remain `UNAVAILABLE`. Cleanup UNKNOWN does not prove the native resource was released |
| Post-auth v2 normative contract | `SPEC_FROZEN` | Parent freeze decision 2026-10-23 after independent `PASS_FOR_SPEC_FREEZE` and verification of exact manifest-key-count and pending-phase remedies. Historical path `docs/decisions/minecraft-control-postauth-v2-draft.md` is authoritative: role-scoped 23-key raw manifest, v2 protocol/profile, queue 8, route 2, pending 8, whole-store capacity 2 and READY/CAS ordering | The freeze initially did not authorize implementation; the listed bounded unassembled slices were separately authorized. Runtime activation remains prohibited and production disabled. Old vector/config/model passes are historical and must be regenerated against the frozen contract |
| Frozen v2 manifest interop | Scoped source/interop PASS | Sandbox `364a50e` implements strict 23-key manifest validation; local upstream `05acdd0f` implements the Python counterpart. Java/Python interop has 22 cases; the manifest source gate passed (Java array snapshot and Python ID correction) | Full Java suite at this slice: 229 tests in 40 suites, 0 failures/errors, 2 explicit opt-in loopback skips. No filesystem loader, ACL, credential startup assembly, or runtime activation evidence |
| Frozen v2 frame codec interop | Scoped source/interop PASS | Sandbox `beda942` adds unassembled keys and context-bound frame codec; local upstream `35b4386d` supplies the Python counterpart. Eighteen actual codec cases passed; parent recorded 140 related pytest cases, tool 8 codec checks, and a separate forced JUnit codec execution with 0 skips | Full Java suite at this slice: 241 tests in 43 suites, 0 failures/errors, 2 explicit opt-in loopback skips. External Java JSON reports remain `MATCHED_UNATTESTED` documents, distinct from parent-observed forced execution and not JSON attestation |
| Frozen v2 peer-session interop | Scoped source/interop PASS | Sandbox `ab2a1a7` adds an unassembled Java `PeerSession` with fixture-backed two-role interop; local upstream `f2108373` supplies the Hub session. Frozen §6.3 heartbeat is limited to valid `HEARTBEAT`: 2 s refresh, 500 ms production cadence, and exact 5 s bootstrap owner check. Parent recorded 150 related pytest cases, tool 6 session checks, and separate forced JUnit session execution with 0 skips | Full Java suite at this slice: 256 tests in 45 suites, 0 failures/errors, 2 explicit opt-in loopback skips; session core build had 255 and interop metadata-only completed 256. Offline fixtures create continuous instances per stage and final Python uses a new instance for prefix replay; this is not live IPC or network evidence. Structurally valid literal `false` timeout/send-failure flags classify as `FAILED`; malformed non-boolean values classify as `BLOCKED`, while expected `true` is positive. Parent verified the explicit review remedy |
| Shared v1 current-role owner | Scoped source PASS | Local upstream `45aebe05` consumes minted private handshake provenance and shares a v1 current-role owner across exact context, reservation, lease, connection and challenge. It is event-loop and thread bound, has 2 slots, rejects duplicate roles and hot replacement, keeps public legacy handshakes non-promotable (copied/replaced unmarked), sends READY before promotion, withdraws synchronously before awaiting close, and uses v1-only `AllowedPeer` to block manual v2 startup. Parent reran 172 related pytest cases successfully | No coordinator-fence assembly, v2 endpoint, binding, permit, or activation claim. The revision is local-only, not pushed or publicly fetchable |
| Fresh v1 dual-role transport gate | Scoped transport PASS | Sandbox `8c2c67c` records exact 10-source closure and 5 loaded Python module identities, with 12 runner unit tests and report `current-role-loopback-20261024-final.json`. A genuine fresh real-v1 dual-role run recorded SERVER `2482 ms` and CLIENT `2504 ms` (each ≥2300), exactly 2 roles, and 0 errors/failures/skips; JUnit SHA-256 `85729c86a042e04bd25852b277d375ba140365bcb6f12fefb944e5f594fe2b19`. Cleanup was `OWNED_PROCESS_TERMINATED_AND_WAITED`; the report records source before/after | Fresh v1 transport evidence, not full G3, v2 socket assembly, real v2 network acceptance, BODY join, binding, coordinator/store, permit, input, game, or activation evidence |
| Deployment-file snapshot reader | Scoped source PASS | Local upstream `f68554e4f7c8e1919d32b37d7ebf6960974ac384` adds `deployment_files.py` and tests. Parent recorded 96 related pytest passes: 19 focused cases including actual Windows-native cases without skips, plus 77 related. It reads four explicit absolute paths from trusted identity using real Win32 `GENERIC_READ`, `SHARE_READ` only, `OPEN_EXISTING`, and `OPEN_REPARSE_POINT`; final handles must be disk, non-directory, non-reparse. It applies manifest ≤4096 bytes, artifacts ≤1 MiB, exact read plus EOF, pre/post identity checks, held-handle manifest parse and trusted-hash equality for three artifacts. All closes confirm before return; unknown cleanup yields no snapshot/no retry with fatal priority | Snapshot is `FILES_READ_AND_HASHED`, `ACL_AND_PARENT_NAMESPACE_UNVERIFIED`, `parent_namespace_anchoring_verified=False`, `acl_verified=False`, and `activation_allowed=False`. No raw artifact/path/handle is returned; `BoundFileIdentity` is internal, not a public receipt. Reparse screening is not TOCTOU anchoring; trailing-dot/space and DOS normalization are not independently eliminated. This is not a trusted filesystem or authorization proof |
| Handle-bound deployment trust gate | Scoped source + native evidence PASS | Local `9bfba573` adds the ACE-pointer-bounds-corrected ACL module; local `1124136ae9f182de1101d470b17afae4d5997125` integrates deployment-file verification. The accepted loader holds the entire ancestor chain drive→root and validates each before/after; direct children are same-volume single-hardlink. Supported `QueryDosDevice` mapping is stable, filesystem is NTFS-only, and root expected volume/index are provisioned. Held raw files require stable identity/bytes/hash before confirmed close; owner DACL has explicit writer SIDs. Parent reran 99 related pytest cases (one existing platform skip; actual native cases no skip). Native evidence covers blocked child writes/deletes/renames with recovery, refused junction with intact targets, and fatal/no-retry unknown cleanup | It establishes NTFS deployment namespace plus owner/DACL integrity at load time only, yielding private immutable wrapper provenance with no ongoing lease after handles close. An unexpected root DELETE-access open was resolved by same object volume/file ID; same-object handle rename is denied while held and restores after cleanup. Parent-chain screening alone is untrusted; the accepted whole-chain hold/validation is the evidence. It does not establish epoch platform ACL/VFS/durability capability, credential confidentiality, transport/BODY/permit, runtime activation, or general authorization |
| Provisioned v2 peer custody | Scoped source PASS | Local upstream `8beb455e2807e21268d5b039d458c88aa516c16b` adds accepted `v2_startup.py` custody/tests after verified filesystem `1124136a`. `provision_v2_peer` requires exact private verified-deployment filesystem provenance, trusted expected role/install/hub/config hash, key ID, and exactly 32 external preprovisioned role-PSK bytes. Context is limited to snapshot identity, mod/config/binding hashes and protocol v2. A private zeroizable PSK `bytearray` under `RLock` closes so all crypto rejects and `is_provisioned=False`; there is no public construct/copy/replace/pickle path, getter, repr, or secret logging. Parent reran 229 related pytest cases (14 focused including actual Windows NTFS cases with no feature skip) | At that custody slice, `AuthenticatedTranscript` was caller-trusted data, not socket authentication; fixed HubProof/PEER, READY, and session-KDF reused existing routines only. That slice alone minted no pending consumption or authenticated-handshake/current-role lease, and claimed no credential-file ACL/DPAPI/secret-manager proof, key generation/default keys, v1 `AllowedPeer` bypass, socket, or activation |
| v2 pending-handshake integration | Scoped source PASS | Local upstream `50104002e249eb852f61368988c1aeca182b7677` adds `begin_v2`/`consume_v2` to the existing `PendingHandshakes` map: shared global capacity, one per connection, monotonic TTL, prune, rollback, and one-use semantics. It accepts only exact private `ProvisionedV2Peer` context/credential binding; HubProof computation succeeds before pending publication, raw PSK is neither pending-cloned nor exposed, lock order is credential `RLock` then pending lock, and callbacks/clocks run before critical sections. Consume atomically pops the exact connection before PEER proof; wrong connection leaves the rightful pending entry. v1 consume rejects/consumes v2-bound pending, while v2 rejects v1 | Only verified PEER proof returns consume-minted full-handshake provenance. Public `ReadyProof`/`AuthenticatedTranscript` are not authority, and READY generation is not a current-role lease or socket assembly. Parent reran 259 related pytest cases (33 focused auth/startup cases, including actual native NTFS for both roles). That historical slice alone had no Java or v2 network-loopback evidence and no BODY route/candidate, coordinator fence, epoch, permit, actuation, or G3 claim |
| Production v2 transport | Scoped source PASS | Local upstream `0286b31b` assembles private `ProvisionedV2Peer`, actual pending `begin_v2`/`consume_v2`, exact current-role ownership/lease, READY-before-promotion, and `PostAuthHubSession` OPEN/ACK/HEARTBEAT. Parent recorded 245 related pytest passes; safe-clock coverage uses maximum `((1<<42)-5)*1000-1` | At that historical transport slice, default behavior denied all BODY/business behavior. Current bounded notice/candidate routing is separately recorded; all other business behavior remains denied. No public callback, coordinator, listener registration, auto-provisioning, memory fallback, production Java connector/startup, or activation claim |
| Real disabled v2 loopback gate | Scoped transport PASS | Public Sandbox `c70b4b4` records an isolated owned-Uvicorn test app, actual temporary NTFS deployments, dummy external PSKs, and a Java test-only JDK `HttpClient` WebSocket bridge around the production transport, not a production Java connector. Ignored local artifact `.gradle-user-home/interop/v2-loopback-20261024-reviewed.json` is `PASS`, `scope=TEST_V2_LOOPBACK`, `actuation=DISABLED`: 24 runner unit tests passed; forced offline/no-daemon focused JUnit ran exactly two roles with zero failures/errors/skips, SERVER and CLIENT each `2303 ms` (required ≥2300), JUnit SHA-256 `9708667d5bf54a4268092d65097076eeeac5c86e3dd3696687c506388745be74`, runtime completed 2/pending 0/roles `[]`/app frames 0, and Java/Python exited 0 with `ORDERLY_APP_SHUTDOWN_AND_PROCESS_EXITED` | The reviewed report executed at agent head `0286b31b` before untracked support was committed as `da013ad5`; `da013ad5` subsequently added the same hashed support bytes and does not make the report post-commit execution evidence. Orderly token-plus-fresh-nonce receipt cleanup after sessions drained closed both peers (2 attempts, 0 failures; temp cleanup true); fallback terminate/kill never passes. Source closure records 21 paths/12 loaded Python module identities including `src/adapter/minecraft/transport.py`, not strong in-memory bytecode attestation. The ignored local report is not a remotely committed artifact. No filesystem-parity proof, public route/listener registration, real credential provisioning, BODY route/candidate, coordinator, permit, activation, or full G3 claim |
| Shared v2 currentness and coordinator-local fence | Scoped source PASS | Local upstream `ece27da0` makes `current_roles` the sole authoritative owner shared by v2 transport and the existing binding coordinator; alternate coordinator role maps/register/disconnect paths are removed. Exact consumed handshake plus server/controller lease identity and generation govern owner-held, one-use withdrawal receipts (one per role, capacity 2), minted only after authenticated lease removal. Exact role removal synchronously clears coordinator-local active/ingress/pending authority and quarantines generation before external unbind, revoke, socket close, or await. Stale/copy/reconstruction and same-connection/new-generation inputs cannot fence a replacement; reentrant/throwing ACTIVE unbind leaves local authority absent without claiming external unbind success. Parent recorded core 51 PASS and combined 232 PASS with no exclusions; writer 242 is a different set and is not merged | Historical combined evidence retained 1 failure/222 passes from 10 ms action-retention versus real-deadline coupling. The test-only retention seam independently passed and does not change production UNKNOWN. This is source/unit evidence, not network ACTIVE/PREPARE/COMMIT or BODY evidence. Both leases, coordinator generation, and active/pending object snapshots are rechecked after candidate/CAS/send/bind boundaries; stale operations reject. Tests cover no replacement fence and a CAS gap without send, but do not claim a production epoch or network fence ordering. Epoch remains `UNAVAILABLE`/`verified=False` |
| Currentness limited network gate | Scoped transport PASS | Public Sandbox `42aab98` records the two-file runner dependency closure; 24 runner unit tests passed separately. Ignored local report `.gradle-user-home/interop/v2-currentness-loopback-20261024-final.json` covers one Uvicorn and one JVM, exactly two roles, `TEST_V2_LOOPBACK`/`actuation=DISABLED`: SERVER `2327 ms`, CLIENT `2302 ms` (each ≥2300), zero failures/errors/skips, JUnit SHA-256 `8ec254d04333c27a428e52784a116b71783e10a7561e5dbd423c70b721c4792a`, Java/Python exit 0, completed 2/pending 0/roles `[]`/app frames 0, and orderly receipt cleanup (2 peers, 0 failures, temp clean) with `ORDERLY_APP_SHUTDOWN_AND_PROCESS_EXITED` | Report execution used source head `da013ad5` plus then-uncommitted source; `ece27da0` commits the same reviewed bytes afterward, not post-commit execution evidence. Closure is 25 paths/16 loaded Python modules, including `wire`, `typed_ingress`, `epoch_store`, and `control_binding`; it is execution closure, not a hash claim for all eight upstream modified source/test files, and paths/on-disk hashes are not bytecode attestation. Synthetic `src.domain.agent` and `src.stage.WorldFactSink` are import-time business/type dependencies only. No binding coordinator, typed ingress, observation scope, or coordinator ACTIVE was constructed; this does not prove network fence ordering, real domain/stage dependencies, BODY, route, or COMMIT |
| Bounded v2 BODY notice/route/candidate | Scoped source PASS | Local upstream `e6d1b8d` adds `v2_body_routes` and integrates it with post-auth session/transport. Global route capacity is exactly 2 across `ROUTED`/`CONSUMED`/`INVALIDATED` tombstones until each original non-refreshing 5000 ms deadline; generation/reentrancy, single UUID call, capacity/identity/lease rechecks apply. A six-key raw notice produces ordered authenticated `BODY_BIND_ROUTE`; exact route matching and canonical bounded candidate metadata produce immutable non-authoritative DTOs. Exact withdrawal invalidates routes before coordinator fence/revoke/socket awaits; cleanup failure cannot skip the fence and propagates `V2TransportError` after best effort (fatal > cancel > ordinary cleanup) | No coordinator prepare/admit-prepare/store worker, epoch CAS, binding, permit, or world authority. Candidate copies are not authority; no false pass on unknown cleanup. Parent recorded 248 related pytest PASS with no exclusions; writer 227 is a different set. Independent source/security review PASS |
| Archived bounded-route network gates | Scoped transport PASS | Public Sandbox `4886e0d` commits five harness/test source files; each accepted run retains an independent ignored local JUnit XML archive. Parent recorded 34 harness unit PASS. Gate A report `.gradle-user-home/interop/v2-routes-heartbeat-20261024-reviewed-archived-final.json` records exactly two roles, 0 failures/errors/skips, heartbeat SERVER `2317 ms`/CLIENT `2330 ms`, archived JUnit SHA-256 `8cebf2d12d7c459a77e0add4bd737a74cc68032137ccc6c9a2e0ba96c223be36`, closure 26/17. Gate B report `.gradle-user-home/interop/v2-body-route-20261024-reviewed-archived-final.json` records exactly `actualNoticeRouteAndMacValidSyntheticCandidate()`, 0 failures/errors/skips, `NOTICE_ROUTE_SYNTHETIC_CANDIDATE`, candidate 1/raw payload match then post-disconnect candidate 0/coordinator absent, archived JUnit SHA-256 `3183006ec6a09f49af7966a09d6d3038a73f8384907bf9a8519aa07790ddcd3e`/closure 29/17. Both are one owned Uvicorn/one JVM, Java/Python exit 0, orderly receipt shutdown, peer close 2/0 failed, temp clean | Both ignored reports ran at `ece27da0` plus then-uncommitted reviewed route bytes; `e6d1b8d` commits same bytes afterward, not post-commit execution evidence. Archives validate raw bytes/fsync/atomic no-replace/link-collision failure/readback SHA and persist across overwritten Gradle XML, but remain ignored local artifacts with no immutable-filesystem claim. Java JDK WebSocket bridge and MAC candidate are test-only; no Java LP11/verified game/body proof, production connector/startup/FS parity, coordinator ACTIVE/PREPARE/store worker, endpoint registration, or runtime authority claim |
| Dedicated epoch-store worker | Scoped source + native evidence PASS | Local upstream `0b8545c` adds `epoch_worker.py` and unit/native tests. One dedicated non-daemon thread owns actual `EpochStore` open, explicit caller provision, candidate read, READY CAS, readback, native exclusive lock, and close; the owner asyncio loop holds only registry/futures plus immutable completion/terminal events. No default executor or `to_thread` is used. Outstanding capacity 2 covers queued/running/completed-unconsumed/delivered preflight leases and obsolete work until completion; cancellation does not cancel a disk job or release early. Native gate passed on Windows without skip: worker 1 provisions/preflights/CAS/readbacks epoch 1/gen 1; concurrent worker 2 reaches native acquire and fails while worker 1 holds the real lock; worker 3 reacquires after confirmed close and reads persisted state. Parent recorded focused 20 PASS; related 102 PASS with 1 existing platform skip; native worker/store/platform/ACL group 61 PASS with 1 existing platform skip | Collections are distinct and not summed. Test platform is `LOCK_ONLY_NOT_PRODUCTION_CAPABILITY`; production `WindowsEpochPlatform.capability(root).verified=False` remains asserted. No ACL/owner-DACL, power-loss/durability, production epoch availability, coordinator/routes/BODY proof/PREPARE/READY/COMMIT dispatch/ACTIVE/permit, or network claim. UNKNOWN cleanup is terminal/no-retry and cannot claim replacement or CLOSED before confirmed cleanup, thread death, settled waiters, and emptied references |
| Epoch worker preflight identity API | Scoped source/test-validity PASS | Local upstream `ca12c72f` requires canonical nonzero `provision_id` and `body_id` in `EpochPreflightRequest`. Worker read-candidate matches store identity before handoff and fails closed on mismatch without CAS. Owner-loop/thread-first `validates_preflight` does no I/O, await, user callback, or consumption; it requires exact AVAILABLE+DELIVERED and worker/store/job/result object identity with correlations. Its bounded scan is 2 and uses `candidate.result is snapshot` before caller-private identity access, rejecting copies, subclasses, unhashables, and forged snapshots without attacker hash/equality/attribute invocation. `submit_commit` reuses the same predicate and preserves atomic conversion; delivered snapshot capacity remains held until commit/discard | No fallback/provision or route-authority upgrade; epoch stays `UNAVAILABLE`/`verified=False`. Parent recorded related 106 PASS with 1 existing platform skip; writer 122 is a different set and not summed. Writer reported focused worker/native 24 PASS. Historical regression (1 failed/104 passed/1 skip) was a test assumption that one sleep implied OS thread death; its bounded terminal-and-thread-dead wait was corrected without changing UNKNOWN/CLOSED or retry semantics. Independent source/test-validity PASS |
| Java verified BODY-proof candidate bridge | Scoped source/test-validity PASS | Sandbox `cd87b04` adds `BodyProofV2CandidateBridge` and integrates the exact game connection plus verifier proof through channel handler/post-auth session. The bridge is one server peer-session/generation with capacity 2 identity map: retained proof precedes strict six-tuple route match; it is removed before original-monotonic-deadline one-use ingress acceptance. Narrow server-only candidate send uses actual queue/sequence/MAC; queue-full, synchronous/async send failure, or cancellation consume proof without restore/retry. Exact disconnect removes all matches before external invalidation; observer fatal survives cleanup errors. Parent observed full XML 272 tests, 0 failures/errors, 6 explicit opt-in/environment skips; independent source/test-validity PASS | Explicitly unregistered: no Forge channel/EventBus/runtime assembler, production connector, or filesystem parity. This is not real Minecraft networking, body-binding authority, coordinator redemption/PREPARE/COMMIT/ACTIVE, permit, or actuation |
| Observed verified BODY-proof test gate | Scoped test-only transport PASS | Ignored report `.gradle-user-home/interop/v2-verified-body-proof-20261024-observed-final.json` records exact `verifiedLp11NoticeRouteCandidate()` with 1 test and 0 failures/errors/skips; archive `v2-verified-body-proof-junit-62c2d22ce14afa8f4f0419420a301b6b.xml` SHA-256 `a3a3e1d98b620ee50255420d037f9acaddd5250f6b7594f55f3d1beaa5923786`, closure 40/17, Java/Python exit 0, and orderly receipt shutdown (2 peers, 0 failures, temp clean). Actual LP11/HMAC/codec/handler/verifier/bridge flow emits one non-authoritative Hub candidate then disconnect yields zero; observed JSON metrics derive from runtime values. Parent recorded 38 combined runner unit PASS (a distinct set) | Run recorded head `0b8545c` plus then-uncommitted support/Java bytes; later `18f55b36`/`cd87b04` commit same reviewed bytes, not post-commit execution evidence. Paths/on-disk hashes are not memory-bytecode attestation; JSON/XML archives are ignored local, not committed. Test connection is not Minecraft network and test candidate is not production authority. Compatibility report `v2-body-route-20261024-compatibility-final.json` preserves the old synthetic raw semantics (1 pass; archive SHA-256 `12a5c7e36cb7e43a8337cedf030c095247cf509ef004a381eb5791ed8f9cde3a`) |
| Headless private-admission component | Scoped source/test-validity and test-only transport PASS | Local upstream `09687d48` accepts the approved headless component only. An exact owner-held, one-use private route receipt—not a public candidate DTO—redeems through real worker `validates_preflight`; route prevalidation runs external clocks before final callback-free redemption. Full coordinator generation, active/pending-object, and both lease guards fence redemption. An immutable opaque dispatch capability/ticket is atomically published; its private command is stored on the ticket, not on mutable public DTOs. It dispatches one generation-scoped `PREPARE` with no retry, retains at most one early `READY` input per ticket, and rejects unauthorized, duplicate, conflicting, undispatched, legacy-ready, and legacy activation of v2-bound pending without CAS. The explicit global admission-task registry has capacity 2 including `cancel_requested` work that is not done; task identity, server/controller leases, and receipt identity are checked. Withdrawal captures identity before fallible cleanup, invalidates route, synchronously fences coordinator, then cancels/drains before socket await; old completion cannot remove a replacement. DELIVERED preflight retains a global slot until conversion/discard and obsolete running work retains it until actual completion. With an absent or unavailable worker, the accepted candidate remains observational: no admission job/task, PREPARE, pending state, or ticket is created. Existing pending capacity 8 is distinct from worker and route capacity 2; production platform remains `UNAVAILABLE`, `verified=False`, with no fallback or auto-provision | No READY CAS, `COMMIT_IO_PENDING`, COMMIT, ACTIVE, binding, permit, production epoch capability, connector/filesystem parity, channel registration, game/EULA/provider/human/food/permit, or G3 claim. `COMMIT_IO_PENDING` is not ticket/wire COMMIT or durable CAS success. Parent recorded a broader independent 22-file set with 416 PASS and 1 existing conditional skip; source/test-validity gates are accepted, not an all-repository test claim. Historical 332 passed/1 skipped/1 failed Windows rename-helper evidence remains superseded: its test-only `FILE_RENAME_INFO` buffer used an explicit UTF-16 zero tail excluded from the length, natural alignment, code-point NUL check, and `xĀ` structural edge; the original native held-rename denial and same-handle exact-basename/file-ID restoration remain recorded. This does not claim Windows APIs universally require a NUL tail. No production deployment-reader change is claimed. Public candidate DTOs remain non-authoritative |
| Headless private-admission test-only gate | Scoped test-only transport PASS | Ignored combined report `.gradle-user-home/interop/v2-headless-admission-20261024-final.json` SHA-256 `9b7494094bc0ef388798902d36588e9a766acb01510fb756267ae51f30405737`; parent recorded 39 harness-unit PASS. One owned Uvicorn and one JVM ran each mode sequentially. The Java JDK test socket bridge exercised actual LP11 on the exact test game-connection object, not Minecraft/Forge runtime. Mode A is observational with coordinator `None`: verified/consumed/raw-match/original-expiry true, candidate 1 then disconnect 0, no worker/PREPARE. Mode B is headless: actual production owner/routes/coordinator/worker with real NTFS/SQLite and native lock-only test platform; candidate/admission/pending/ticket/PREPARE/worker-outstanding each 1, worker DELIVERED, phase PREPARE, committed false, active/ingress/stage scope false, and no binding commit. PREPARE uses a test-only command sink and introduces no wire frame. Both modes had Java/Python exit 0, `ORDERLY_APP_SHUTDOWN_AND_PROCESS_EXITED`, drained receipt sessions, peer-close 2/0 failed, and clean temporary state; Mode B worker closed with dead thread/outstanding 0. A new actual open of the same installation reports expected last 0/generation 0/next 1 without CAS. Mode A archive `cb57048abef1b57dc67232412310f4c4` SHA-256 `aba5e138398e2da1d509cb665d7e5ef8667d830e1a96f772a3e9649b1056cf3c`; Mode B archive `3be8f0438f1830d4ee4ee2b81c3e9ee3` SHA-256 `220e810411c67348eabc9649f62fa8a64f4d2de10643227cf43ca89e2eda8d7b`; each has exactly 1 test, 0 failures/errors/skips, and exact `verifiedLp11NoticeRouteCandidate()` | Runs used reviewed bytes before `09687d48` committed the same bytes, so they are not post-commit execution evidence. Closure records 49 source paths, 19 loaded modules per mode, and seven separate frozen hashes; paths/on-disk hashes are not bytecode attestation. Archives/reports are ignored local artifacts, not committed. This proves no Minecraft/Forge runtime, production activation, COMMIT/ACTIVE, or production platform capability |
| Java-only old-authority revocation prerequisite | Scoped source PASS | Public Sandbox `fcc46aa` changes only `ControlledPacketGuard`, `ControlledIdentityRegistry`, and `ServerControlCoordinator`, with their three paired tests; independent source gate PASS after the latest-generation-publication remedy. Guard ownership is thread-first and exact on player, identity, connection token, and full `PermitAuthority.Binding`. Registry removal/pending cleanup/generation receipt invalidation precede actual `fenceControlLoss`, which marks executing immutable `UNKNOWN`/quarantine, waiting `NO_WORK`, clears local permitted request, then revokes captured expected permits. Cleanup is mandatory despite ordinary/fatal notifier failure, with fatal precedence and no false success receipt. The private exact-owner receipt has capacity 1, fixed 5000 ms lifetime/no refresh, and sole status `OLD_AUTHORITY_REVOKED`: 4999 ms is valid and 5000 ms expired; bind, transfer, offline, and further fence invalidate it. Offline matching uses the exact player reference, not an equal wrapper with identity/token. Reentrant adapter callbacks capture body/state/generation and recheck before publication; non-cleanup protect/bind/normal-transfer/quarantine-transfer reserve generation after callbacks and full-state checks. `MAX-1`→`MAX` is the final unique mutation; terminal exhaustion rejects all subsequent non-cleanup authority publication, while mandatory revocation/cleanup still runs at `MAX` without wrap or new receipt | Writer reported focused Registry 23, Guard 9, Coordinator 17: 49 PASS, 0 failures/errors/skips, and build/reobf success. Parent independently checked XML full-suite evidence: 286 tests/49 suites, 0 failures/errors and 6 existing opt-in network skips. The six skips are `ControlTransportLoopbackTest` 2, `PostAuthV2BodyRouteLoopbackTest` 1, `PostAuthV2LoopbackTest` 2, and `PostAuthV2VerifiedBodyProofLoopbackTest` 1—not network PASS. An intermediate focused pending-cleanup offline replacement failure was fixed semantically; final passes do not claim no failure ever. No fresh network run was required for this unregistered revocation-only slice. No Java WebSocket connector/startup/filesystem mirror, Forge EventBus registration, Python/CAS change, world/game runtime, or READY authority is established |
| Binding-quiescence local observation | Scoped source/evidence PASS-with-residuals | Sandbox `61dc228`: Java-only LOCAL INCOMPLETE, exact-body/fence provenance and one-use resampling. Focused 5 suites/87 tests/0 failures/errors/skips; full 52 suites/324 tests/0 failures/errors/6 opt-in skips. See [decision](decisions/binding-quiescence-observation.md) | Four scopes UNAVAILABLE, production complete=false; package trust and runtime Mixin residual; no READY/authority/delivery/G3 |
| Deferred-work original-byte boundaries | Scoped offline structural PASS-with-residuals | Sandbox `4ee697f`: 6 original classes, 56 reported methods (not a fixed whitelist), 5 listener-local helper call sites; focused 2 suites/4 tests/0 failures/errors/skips; 46/46 production hashes unchanged. No full-build lane. See [decision](decisions/deferred-work-observation-boundaries.md) | Original-byte structure only, not producer admission, execution/completion, runtime injection or whole-scope closure |
| Mod-owned container-close accounting | Scoped source/evidence PASS-with-residuals | Sandbox `38f59cd`: capacity-8 owner-confined accounting at the one mod submit site; capture-failure P1 closed after remediation. Focused 4 suites/56 tests/0 failures/errors/skips; full build/reobf 54 suites/353 tests/0 failures/errors/6 existing opt-in skips; prior 347-test evidence superseded. See [decision](decisions/mod-container-close-work-accounting.md) | Mixed ledger unit/real-registry deterministic seam and limited ASM parity, not actual Forge event/path-complete CFG/runtime Mixin evidence; no queue/shutdown closure or READY authority. Six skips remain ControlTransportLoopbackTest 2, PostAuthV2BodyRouteLoopbackTest 1, PostAuthV2LoopbackTest 2, PostAuthV2VerifiedBodyProofLoopbackTest 1—not network PASS |

| Frozen v2 outer-key compatibility correction | Scoped source/local-review PASS-with-residuals, parent accepted | Accepted Sandbox `28bc7d9` codec/harness bytes; clean local upstream `57e46850c56079a4e95c645234b696eebbaaaf4f`, no push/PR. Java/Python decode requires the exact set of 14 distinct outer members, not serialization order. Encoder order, duplicate/value constraints, 18-semantic-field MAC/raw payload and intentionally ordered 23-key manifest unchanged. Java focused 1 suite/10 tests/0 failures/errors/skips; historical full 54 suites/355 tests/0 failures/errors/6 existing loopback skips. Python focused 95 PASS, adjacent 86 PASS | Frozen-spec compliance, not protocol extension or activation. Six skips remain ControlTransportLoopbackTest 2, PostAuthV2BodyRouteLoopbackTest 1, PostAuthV2LoopbackTest 2, PostAuthV2VerifiedBodyProofLoopbackTest 1—not network PASS. Java archive `Temp/opencode/frame-keyset-20261024-203906`; Python archive `Temp/opencode/python-postauth-order-20261024-204002-b0a4a0f316bd4d749f87397f1fb572fb` |
| Strict pinned codec interop harness | Scoped evidence PASS-with-residuals, parent accepted; input-ownership P1 closed | `Temp/opencode/postauth-exclusive-20261024-210538`: exact upstream `57e46850` pin, whole upstream clean, four approved raw hashes, snapshot execution and pre/post identities; exclusive input creation/identity cleanup and alias rejection. Units 22 passed + 12 subtests (XML 34 results), 0 failures/errors/skips. Fresh forced JUnit: 1 test/0 failures/errors/skips, 32.379 s, actually executed 18 cases (9 positive/9 negative), not early return. 31 artifact hashes + 27 fixtures verified | External comparison JSON is `MATCHED_UNATTESTED`, separate from explicit commands/log/XML/new-output/class-hash observed evidence. No new outer-permutation interop cases; local tests cover those. Historical `upstream_head_mismatch` BLOCK (0 executed) and prior output-ownership BLOCK are superseded, not retroactive success. Trusts Git config/attributes/filters/tools and exclusive workspace; identity-check/unlink is not atomic against ABA; fstat failure can leave an UNKNOWN file; no dedicated close-failure injection. 13-source inventory is LIMITED, not full transitive Java closure or bytecode attestation |

Interop harness script SHA-256 `2320B6E298DF1F8AD19693CA9663D3C17E654E985CA484C8CF861EE07BF8C268`, test `3B34133D25D3DAF854C4B40C3C1ED17CD16880A93177956E3BDEA0165BD173E1`; limited 13-source manifest `F6FA40ACEBB4F7325F22D144EDEAFD2962003392B41E6DFDA4A897BC35C6312F`, final report `BE61F14C9A047626FC779CB53CD243502D6968115ED4722BCF120508A60559E8`. These counts are recorded accepted evidence, not reruns after this docs-only update.

### Accepted staged connector/session prerequisite

Parent accepted mom-1 scoped architecture PASS_WITH_RESIDUALS and ora18 session source/evidence
PASS-with-residuals. The original six design BLOCK findings are resolved at design level; actual
ports/ingress/completion/cleanup and full mailbox integration remain production implementation gates.
The session prerequisite (`2684526`) and separate unwired three-cell mailbox are accepted;
full connector/production adapter assembly is not authorized. Accepted session/test hashes are
`2CFAA43726530E98F9C092A0AD69AF7C9ADE526177C48E6E120650D7E2C485C1` /
`6C45A98246C81A143C9A3A15EED85A1B6DAE1566949EBB9562FB524109E52AF8`; no future commit hash is asserted.
Absolute OPEN preserves the supplied deadline and caller key ownership until successful return;
all new throwing-constructor paths leave keys unclosed and perform no fence/physical close.
Legacy constructor/failure semantics remain. Heartbeat uses one real beforeOperation read,
watchdog first: before due nothing; due..due+499 one checked next += 500; a full missed slot closes
cadence_missed with no burst/reanchor, potentially before 2000 ms. This is approved local availability
policy, not a frozen mandate or runtime activation.

Archive `Temp/opencode/session-timing-20261024-212043`: focused 1 suite/24 tests/0 failures/errors/skips;
full 54 suites/363 tests/0 failures/errors/6 existing loopback skips (ControlTransportLoopbackTest 2,
PostAuthV2BodyRouteLoopbackTest 1, PostAuthV2LoopbackTest 2, PostAuthV2VerifiedBodyProofLoopbackTest 1),
compile/test/build/reobf success; 3/3 source, 57/57 artifacts, 27/27 static fixtures verified.
Source manifest `27F33A221B6F200C0C0925F827C6C059EDB24D88F0455C021EB94A27EB23DAE5`;
artifact manifest `F49EC25DD14A15213AF47D0E073DF0AC9C0DEAF880E0B4CCFA70F0B6A476E811`;
frozen-spec hash unchanged `1E2780004BABEA1C6119DC21D3A39A7C9AE19FABA5BB3225949C6047BC85B374`.
No new cross-repo interop ran: the earlier 18-case gate remains distinct accepted history; the
355-test codec full run above is historical, not the current full count (codec/harness accepted in
Sandbox `28bc7d9`). Gate Error lacks every special-path injection; reflection overflow is not runtime
proof; no actual connector/mailbox/timer integration is exercised. Four UNAVAILABLE scopes,
complete=false, epoch/filesystem-custody/runtime-Mixin/G3 and READY/CAS/COMMIT/ACTIVE gates remain unchanged.

### Accepted unwired three-cell owner mailbox

Ora18/parent scoped source/evidence PASS-with-residuals closes P1A/P1B; retain the earlier P1 BLOCK
and 385-test run as historical, superseded by the final 391-test run. Only send/timer/terminal cells
are implemented, at most two ordinary plus one reserved terminal execution per turn. Error attempts
terminal work before rethrow with a valid owner; infrastructure failure permits one explicit terminal
handoff, never automatic retry. First reason/notification once; STOPPED/bad gate means cleanup UNKNOWN.
Same Runnable coalesces; different completion is SEND_CELL_FULL. The real session's immediately-completed
next-send limitation remains, not arbitrary-future compatibility. Preacceptance execution fails closed
as an availability tradeoff; actual port liveness/non-inline/STOPPED behavior remains unverified.
Production search found no external instantiation. This is not 13 cells, byte ingress, authentication,
socket currentness, cleanup quiescence or a full connector. The next stage remains unauthorized until
a separate bounded plan/review; no new cross-repo interop or capability upgrade follows.

Archive `Temp/opencode/v2-mailbox-remediation-20261024-215109`: focused 2 suites/52 tests,
0 failures/errors/skips, 41.992 s; full 55 suites/391 tests, 0 failures/errors/6 existing loopback
skips (same 2/1/2/1 suites above), 54.179 s; actual build/reobf success. Verified 3/3 source,
59/59 artifacts and 105 existing unchanged identities. Mailbox/test SHA-256:
`A5506D21881835237E096F0F85789D054B61D63A7D8CBC4B4ABAAA5BE58F9DDB` /
`A929CFC904A026828095F7C63D4167B83A20971DFAFD6E828218BBEC7435830C`.
Source manifest `5608749229E953A12FADFFB67CB5BA7ACDAA364F6D76FCAB2A684365FF84D193`;
artifact manifest `0A87BD6A38BDDD06166DA18A7C5747A329A6E37667EB5CA04A9FA38F6074656C`.
Session bytes remain unchanged at the hash above; its 363-test acceptance and prior interop remain
distinct history. Four UNAVAILABLE scopes, complete=false, epoch/custody/runtime Mixin/G3/READY gates
and local-only upstream `57e46850` are unchanged; no future commit hash is guessed.

### Accepted unwired session-send/completion adapter

Ora18/parent scoped source/evidence PASS-with-residuals closes OwnerAbort P1. The reviewed new
adapter/test bytes are pending commit; no future hash is guessed. Single physical flight/one cloned
frame/fixed causeless failure/once-only containment use the same mailbox owner ports. OwnerAbort
always delegates to the arbiter even if invalid; false may schedule one explicit recovery handoff.
Real crypto/session physical-failure tests establish owner-only terminal cleanup, no foreign cleanup
and unchanged once-only containment. OwnerEnvelope/production ports remain externally trusted,
not an implemented production cleanup transaction. Logical future completion does not establish
physical settlement; an unknown/unsettled frame may persist. Immediately completed next sends can
yield SEND_CELL_FULL; no extra queue or arbitrary-future compatibility is claimed. This is unwired,
not authentication/transcript provenance, socket currentness, resource quiescence or 13-cell ingress.
Actual connector/JDK socket/shared HELLO-PROOF-session-close arbiter/ingress/production ports remain
absent. Full connector and next-stage work remain unauthorized pending a separate bounded plan/review.

Archive `Temp/opencode/v2-send-abort-remediation-20261024-222042`: focused 3 suites/77 tests,
0 failures/errors/skips, 53.208 s; full 56 suites/416 tests, 0 failures/errors and 6 existing loopback
skips (same 2/1/2/1 suites), 72.443 s; actual build/reobf success. Verified 4/4 source, 61/61 artifacts,
107 existing unchanged identities. Adapter/test SHA-256:
`DED865A8946C1369C3FC64E45E36202D8EA521F37880F858D56759DFA786B450` /
`456D720955CCD7DD04F0C24EF256D4597224D6C6D590957EE176D0B110171447`.
Source manifest `090362289B937036B5E661C74024A21463207219016AB3CCA0A421400E8997C7`;
artifact manifest `E44E84D35B5E4FB5887F51A735DBA193663CB6A66963AF1E106134F4F3BD148C`.
Prior 413-test/OwnerAbort BLOCK remains historical and superseded, not erased. Mailbox `c2769cc`/391-test
and session `2684526`/363-test anchors remain distinct; no new cross-repo/network execution is claimed.
Frozen v2, four UNAVAILABLE scopes, complete=false, epoch/custody/runtime Mixin/G3/READY gates and
local-only upstream `57e46850` remain unchanged.

### Current accepted default-disabled UNWIRED deterministic connector core

Ora18/parent scoped source/evidence PASS-with-residuals closes cleanup/currentness/nonzero-nonce P1s.
Six reviewed source/test bytes remain pending commit, without a guessed future hash. This replaces
earlier core-absent/unauthorized statements only for the deterministic core, not production JDK
network/startup/port assembly. The same mailbox now has 13 cells, round-robin at most two nonterminal
plus one terminal per turn; two fixed lanes total 24576 primitive backing bytes, not total heap.
Actual crypto HELLO/CHALLENGE/PROOF/auth READY yields private verified provenance, real absolute-OPEN
session, MAC-valid OPEN and sequence-0 ACK; early READY/OPEN retention is exercised. Adapter unsettled
query is owner-only, not quiescence. `cleanupSnapshot` owner UNKNOWN is sticky independently of API
input/output closure. External-reentry post-boundary checks/local secret/session ownership are covered.
Nonzero peer/hub nonce shape does not prove production randomness. Immediately completed next send
can still yield SEND_CELL_FULL without a hidden queue. No external production caller/JDK adapter/real
owner ports/BODY/admission/store/permit/binding READY is provided.

Archive `Temp/opencode/v2-connector-remediation-20261024-231224`: focused 4 suites/121 tests,
0 failures/errors/skips, 47.0855566 s; full 57 suites/460 tests, 0 failures/errors/6 existing loopback
skips (ControlTransportLoopbackTest 2, PostAuthV2BodyRouteLoopbackTest 1, PostAuthV2LoopbackTest 2,
PostAuthV2VerifiedBodyProofLoopbackTest 1), 55.0754280 s; exit 0, actual build/reobf success.
Verified 6/6 current files, 111/111 all-source identities and 98/98 artifacts. Final six-file manifest
`26EE030207F0B82C6D21223C9BEB07E402D94744DE17929B92A4C105B1DE5FE5`; all-source manifest
`601B021F8304EC50781E48F2670394D3C8EECD2C10B8855616E07A78B185D378`; aggregate manifest
`C4DAE204E672229CD9C015EA641B205DAF476124882EE17D2526DF5FB98BF699`.
Earlier 110/449 evidence/missing-handoff/internal-review BLOCK remains truthful superseded history;
the prior archive lacked an aggregate manifest, the latest has one. Prior 416/391/363 stage counts are
historical, not the latest full count. No new interop/network/runtime/exhaustive-interleaving/formatter
claim. Next stage requires a bounded plan/review; no activation authorization. Four UNAVAILABLE,
complete=false, epoch/Java filesystem custody/runtime Mixin/G3/READY gates and local-only upstream
`57e46850` remain unchanged.

### Current accepted UNWIRED JDK WebSocket adapter source

Ora18/parent scoped source/evidence PASS-with-residuals accepts the two new adapter/test files,
pending commit without a guessed future hash. Earlier JDK-adapter-absent records describe historical
slices; source now exists, but has no external caller or production wiring. Inert external HttpClient/
supplier, exact socket identity/cancel/late-socket containment, explicit request credits/no default
demand/no explicit Pong, strict bounded UTF-8 send/no JSON rewrite and a private input/output close
gate are covered. onClose gate remains pending until successful explicit close/abort, not cleanup
quiescence. Actual core/crypto/session bootstrap uses a fake JDK surface, not network/runtime or
authentication activation. External client proxy/authenticator/executor policy and owner ports remain
unverified; automatic Pong/Close wire behavior is untested. Core 24576 primitive backing bytes exclude
String/JDK total heap. No loopback/network/startup/activation authorization follows; next stage needs
a bounded plan/review. Four UNAVAILABLE/complete=false/epoch/Java custody/runtime Mixin/G3/binding
READY/delivery gates and local-only upstream `57e46850` remain unchanged.

Archive `Temp/opencode/v2-jdk-adapter-20261024-233142`: final evidence only `focused-verified`,
`full-verified`, `verified-jars`; earlier compilation failure/stale `*-final` directories remain
historical, not final. Focused 3 suites/73 tests/0 failures/errors/skips, 35.8166049 s; full
58 suites/476 tests/0 failures/errors/6 existing loopback skips (same named 2/1/2/1 suites),
42.7547901 s, actual test/build/reobf exit 0. JDK Eclipse Adoptium 17.0.20.1/runtime 17.0.20.1+1,
separate from API research. Before 111/111 unchanged, final 113/113 current sources, aggregate
106/106 artifacts verified. Before manifest `601B021F8304EC50781E48F2670394D3C8EECD2C10B8855616E07A78B185D378`;
final `D457C718EB524DAC33C2F993413E4CA1B8C57D5105F818711C70BFC73D3917D1`;
aggregate `6FE87B499197144C772687D76C687FF7D898D12880052E087DEE40401733751A`.
Adapter/test SHA-256 `687A62099C7CEBC6C704FCBA9990A469B7B2FA394DCC094E2D434092A1AEA55D` /
`4A3A402B5CD06BDDBB9BEB304A25FE845FE8D692B981B4A7DFE756F501219C98`.
Core `d514529`/460-test acceptance is separate history; latest full count is 476. Skips are not
network PASS; no new cross-repo interop or complete-cleanup claim.

### Current loopback harness source/mock acceptance (not network/native PASS)

Ora18/parent scoped source/mock PASS-with-residuals closes native-ownership P1. Reviewed bounded
output/exact trace/independent finally cleanup do not establish native containment. Native cleanup
UNKNOWN, root assigned and exit confirmed remain separate outcomes. Prior BLOCKs are preserved as
source/mock-remediated history, not retroactive native PASS. Three new harness bytes are pending
commit, no future hash asserted: runner `8AB21596B1933C551C4F1C42755E4247091D3205D311CD70673E7280AE3C3AD6`,
test `63BC2471C6A51E1F69ED266995704A1A76CACB5A3F221FAC9737866381DC9630`, JUnit source
`EF00DF4BCF8D78ADAE91A01F136A73D65AFE74BFE3A6FEEAEBCF3140A2FB83C4`.
Archive `Temp/opencode/v2-native-ownership-20261025-002558`: 22 Python tests/0 failures/errors/skips,
4.7215235 s/exit 0; 3/3 new files, 148/148 sources, 14/14 artifacts verified. Final-source manifest
`CC712E2318D3C56882F4FEF4B6885D934A54AC56969D428941D767448730519D`, three-file manifest
`F959D2F5450BB67D1E73D9FABE7C7F11C3D8EA8C2220208496395D30696F09AB`, artifact manifest
`A592A416B166BE927BB6461E7D44ADE0982F8CA1D92E8D4ED6282E4516141032`.
Reused unchanged Java archive `Temp/opencode/v2-harness-safety-20261025-001207`: 2 helpers,
60 suites/481 tests/0 failures/errors/9 skips (3 new network opt-ins plus the existing four suites
with 2/1/2/1 skips named above), not new Python coverage or network acceptance.
At that source/mock stage: **NETWORK_NOT_RUN / NATIVE_CONTAINMENT_UNVERIFIED**. The four-case
native acceptance below supersedes the blanket native label only within its recorded scope;
NETWORK_NOT_RUN remains, without loopback runner PASS.
Option A Hub counters NOT_EXPOSED/application jobs NOT_MEASURED; source worker absence is not runtime
measurement. Exact local-only upstream `57e46850`, committed-clean prerequisite before future actual
network, exclusive workspace/tool trust and readiness-attribution limits remain. Production unwired;
four UNAVAILABLE/complete=false/epoch/custody/runtime Mixin/G3/binding READY gates unchanged.

### Accepted LOCAL NON-NETWORK four-case native smoke

Ora18/parent scoped PASS-with-residuals: four actual runner OwnedTree/LoggedProcess/WinAPI cases on
Windows 10.0.29695 AMD64/Anaconda Python 3.10.21 64-bit passed: natural root, child Job termination,
spaced .bat and real 2 MiB quota. Explicit native command exit 0, 1.9103160 s wall/0.63 s pytest;
default invocation skips four. Tree/quota forceKilled=true is expected negative-run behavior, never
runner PASS. Recorded root/tree confirmed, active count 0/readers ended/cleanupUnknown=false is not
global OS quiescence. Handles are runner-close attempts, not global audit; child PID is owned-root
reported/read-only-handle waited, without independent creation-time/membership proof. All nested-job/
quoting/permission-failure modes remain unproven; temporary evidence retained. Historical 22 mock
tests and Java 481/9 skips were reused, not reexecuted native coverage. No network acceptance.
Archive `Temp/opencode/v2-native-smoke-20261025-003541`: 28/28 artifacts, 3/3 frozen, 1/1 new verified.
Native-test SHA-256 `95A596B1C6E5DAC850466A356B50C72C020AE35E0B569398137B23E5D62278F9`;
frozen manifest `2585BECCA02A604904B479CA1D431C164D65181547A26E287ED1A29A519016F6`;
new manifest `024E65EAFB32F496DCF470CF341330A140ACEDC647EC63FCF5D266841856DB1E`;
aggregate `83C4ACF14A9361F3423EC914F5D45DC41DA3BE11B57315192DDBBE6DD5813C5B`.
Four new reviewed files pending commit, no future hash guessed. NETWORK_NOT_RUN remains; dummy
loopback requires committed-clean/strict pins/separate parent execution authorization. Prior BLOCKs
remain historical; G3/epoch/Java custody/runtime Mixin/binding READY/delivery/four UNAVAILABLE/
complete=false gates remain unchanged.

### Latest probe metadata/diagnostic source/mock amendment

Ora18/parent accepted scoped source/mock PASS-with-residuals for the two Python files, pending commit
without a guessed future hash. Python/uvicorn metadata is required and strict; optional metadata maps
only PackageNotFoundError to NOT_INSTALLED. AUTO backend selectionEvidence is NOT_OBSERVED: package
metadata is not actual import/backend evidence. No installation or backend change was made.
Probe failure preserves a bounded, fully redacted combined receipt with identity, numeric exit or
UNAVAILABLE, size/hash and native cleanup; copy failure leaves private evidence UNKNOWN. Exclusive
workspace/tool trust remains required; raw private logs are sensitive.

The first actual ONE attempt, `Temp/opencode/v2-actual-run-20261025-004540`, remains BLOCKED /
LOCAL_PROBE_FAILED, networkExecuted=false, 0 cases. Report SHA-256
`E54F46BA2B9DD6D69E8EEAD495D08C6B848F355981A445F53A65E6EEA24A4DC7`;
PID 20808 exit confirmed, no force or UNKNOWN cleanup. Missing wsproto is a LIKELY, NOT PROVEN cause:
original stderr was deleted. This amendment does not retroactively pass that attempt.
Subsequent network has NOT RUN; it requires reviewed committed-clean bytes and separate parent authorization.

Offline archive `Temp/opencode/v2-probe-receipt-20261025-005102`: 28 tests/0 failures/errors/skips,
3.8852325 command seconds, exit 0; 2/2 source, 149/149 all-source, 13/13 artifacts verified.
Runner/test SHA-256 `E2B72DD2B0E1608FAA3CC694DC99B2206103F218C329DD91D80B490B24A96731` /
`E40F4BAF119FB7A97EF64008BCAAD3E28353017757AEA0229718DC192FFE57F3`.
Final all-source manifest `94C3D5B130C5A709864D73CF8B6D475D366AC18D5A383F42B01D05024DAEAF89`;
two-file manifest `95C0A76B65F2F9D4B458C2FE8C95401479C6BC63111439091BD8D299352C7EAF`;
aggregate `77C70C7030ECC900D2A3B41B1D7D6BB9E754C7BF575567DDE4A32C5769CF3A70`.
No new actual version probe, native run or Java build: prior native four-case and Java 481/9 skips
remain distinct history, not new Python coverage. Harness/source/native acceptance anchor is
`3c714f1`. Upstream strict pin `57e46850` remains local-only; four UNAVAILABLE/complete=false/epoch/
filesystem custody/runtime Mixin/G3/binding READY gates remain unchanged. No capability/activation upgrade.

### Latest accepted observational localhost loopback (not production activation)

Ora18/parent scoped PASS-with-residuals accepts exactly ONE authorized dummy OBSERVATIONAL 127.0.0.1
attempt at Sandbox `5170f1d` / local-only upstream `57e46850`, both clean pre/post. Earlier NETWORK_NOT_RUN
labels are historical for this exact scope; production network/startup activation remains unauthorized,
without another-run permission. First LOCAL_PROBE_FAILED/network=false/0 cases remains historical failure.
Runner exit 0/85.0316440 s, forced Gradle 7 tasks executed, fresh JUnit 3 cases/0 failures/errors/skips:
SERVER normal 2.732 s/continuous OPEN 2300 ms/5 outbound authenticated-frame attempts;
CLIENT normal 2.391 s/2300 ms/5 attempts; wrong PSK 5.048 s/NEVER_OPEN/0 attempts.
Normal cases: FENCE before CORE_CLOSE, cleanup attempted=true/ownerUnknown=false/inputClosed=true/
outputClosed=true/physicalCloseFailed=false. Negative inputClosed=false/outputClosed=true plus ABORT
does not prove both API sides closed; fence/credential-once is source assertions, not counter receipts.
Hub OBSERVATIONAL roles=[]/pending=0/completed=3/coordinator absent; worker absence source-backed,
application/worker jobs NOT_MEASURED. Hub PID 14196/Gradle root 34616 exact Job assignment/root/tree exit
confirmed, cleanupUnknown=false/forceKilled=false/force attempts 0. Natural Hub exit 0/matching nonce-auth
shutdown receipt/sessions drained/temp cleanup/peer-close 2 failed 0/no errors. Reader/active-count checks
are code-enforced but not serialized, not OS audit.

Archives `Temp/opencode/v2-second-run-20261025-010145` and `v2-second-preflight-20261025-010145`:
report `3A3CFE907265A3CECC60442ACBCD2B0C907499567402B6E2C57DC535FC7FA71C`, XML
`124F4569426089B9E371C47A9E19ED69DC85A3FD23EE6B408BE87B4185919907`, runner aggregate
`53D993F8A5EB7FF214383B571AD7522A56CC81A190A960C287D29363E16E2FF2`, combined
`DFC5D247DA4DD7550D5EA597ECF0CDFAE3FA1B87F690BC2955F781D6EECCE7E0`.
Independent 34 declared raw-source/10 runner-artifact/492 combined/465 build artifact-class-JAR
identities matched. JAR copies are not fresh full build; executed classfiles archived. Raw Hub loaded-status
responses were not retained: original paths/hashes cannot independently be reconstructed; reviewed code
enforced crosschecks/report hashes retained, not complete attestation. Python 3.10.21/uvicorn 0.50.1/
websockets 12/wsproto NOT_INSTALLED/AUTO selection NOT_OBSERVED; Adoptium JDK 17.0.20.1/runtime +1.
Five Unsupported-upgrade warnings retained without attribution/backend inference. Ping 0/NOT_OBSERVED,
no auto-Pong wire proof; APPREADY/ACK/heartbeat NOT_EXPOSED. Closure LIMITED, not all JDK/Python deps or
resource-quiescence proof. No production startup/Minecraft/BODY/admission/binding/permit/epoch claim;
four UNAVAILABLE/complete=false/epoch/Java custody/runtime Mixin/overall G3/binding READY/delivery unchanged.
No future doc commit hash or whole-project completion asserted.

### Latest dormant epoch custody source/SQLite-semantic acceptance

Ora18/parent scoped PASS-with-residuals accepts the explicit OPEN-only actual-store API in exactly
epoch_platform/store plus paired unit tests, committed clean local-only upstream
`aa522dc8259515bf0bb454bcb99a4ebcbb54bd4a`, no push/PR. This supersedes `57e46850` only as the latest
source anchor, not historical execution identity. Receipt cleanup-window/operation-reentry P1s closed:
registration closed before cleanup/RELEASED has no retained refs; single-use private claim/strong
UNKNOWN caller quarantine; after transfer store owns future UNKNOWN. Exact operation token rejects
nested read/commit without connection/slot replacement/outer close; internal readback shares ownership.
Transaction UNKNOWN differs from cleanup UNKNOWN; close once/no retry/first-fatal identity retained.
Backend check-before-acquire/register-before-next-fallible-boundary is trusted contract, not malicious
backend/GC proof. SID role sets not flattened. Unsupported Windows acquisition makes zero native calls;
production verified=false/epoch UNAVAILABLE, no new provision path or worker startup selection.
Structural policy fixtures and temporary SQLite semantics are not real root/SID/ACL/journal/native
custody/VFS durability evidence; EXTRA still gives no Windows directory-durability guarantee.

Archive `Temp/opencode/epoch-reentry-20261025-015302`: 73 collected/70 executed PASS, 0 failures/errors/skips;
3 DESELECTED (`test_platform_is_fail_closed_without_acl_descriptor_verification`,
`test_windows_no_share_lock_rejects_second_handle_and_releases`, `test_non_windows_lock_is_unavailable`).
Wall 6.8734622 s/exit 0/lint passed; final source 7/7 including unchanged worker/deployment,
aggregate 268/268 verified. Before manifest `AA7CB934A2F594F5155D598C312AC378B01D59AD251AD28DE7F7477A3BD2A76A`;
final `0F1E34990CFFAC62794AFDABE3DB2252A90703748DEB76287E1250772584D5A7`;
aggregate `5CC525E8DC4E1925D086C05846E9FE0091D2A77300172BF931155D3A10E6654D`.
Exact four source/test hashes are recorded in [custody decision](decisions/epoch-open-lifetime-custody.md).
Old 58-PASS/review BLOCK remains superseded history, not retroactive PASS. Existing strict interop/
loopback pins still expect `57e46850`: reruns now BLOCK against new HEAD until separately reviewed
profile refresh; gates are not changed or new closure approved here. The accepted observational run
at `5170f1d`/`57e46850` remains historical, not post-custody execution. Future worker/native/deployment
stages need separate authorization. Four UNAVAILABLE/complete=false/epoch UNAVAILABLE/Java custody/
runtime Mixin/G3/binding READY/delivery unchanged; no future Sandbox commit hash guessed.

### Latest explicit worker-owned custody source/SQLite-semantic acceptance

Ora18/parent scoped PASS-with-residuals at clean local-only upstream
`294a12c76a47ea5c8bdd4fcdf298c25dc2a82be5`, no push/PR, supersedes the API-only worker
absence limitation at `aa522dc8`, not production startup. OPEN_WITH_CUSTODY explicitly uses actual
store/receipt on the store thread; pretransfer UNKNOWN receipt/posttransfer STORE retain strong refs
after thread death. Independent-lock terminal publication precedes notification; accepted != executed,
loop-failure polling remains. Preterminal snapshot retention is NOT_YET_REPORTED, closing the P1
without off-thread resource evidence. Public direct lifecycle rethrows same first fatal after drain/
cancellation precedence; internal tasks sanitized/later cleanup fatal does not mask first. Fatal
cleanup_evidence means UNKNOWN+strong STORE even after traceback clear/GC. Caller retains UNKNOWN
worker for containing lifetime; no retry-close/reset/restart/global-other-worker exclusion/quiescence.
Legacy-compatible config has no fallback/autoprovision; native acquisition zero-call unsupported,
production capability/epoch UNAVAILABLE. This is worker API seam, not startup root/admission activation.

Archive `Temp/opencode/epoch-worker-snapshot-20261025-021659`: 38 collected/38 executed PASS,
0 failures/errors/skips/deselection, command 7.0819641 s/exit0; final source 6/6, artifacts134/134 verified.
Worker/test SHA-256 `3E8880EB9E1833A99A8A177F036E3523C91A73EB7058CF7EE097FE1FDD017357` /
`E757278FAAE80824AA5A49AA0B0C88457F934DA194FE82B15B3538272369931E`.
Before `D476D0DC33A81EFBD02D0C40F0A14DD81D8E754AC60DD94DB9904A5B27DCBCD3`;
final `154AC6FE4ED3E27BD6905CA71DA13803C4BC14A74EA8B536452E22766D693972`;
aggregate `25862DD991722206B3929125F624A2AE20BB68A88C67752912065189827B510F`.
Old33 PASS/snapshot BLOCK preserved superseded, not retroactive PASS. Store aa522dc8 70 safe PASS/3
deselected and network5170f1d/57e46850 remain separate history. Existing gate pins57e46850 remain stale,
no automatic refresh/rerun. Real deployment root/SIDs/journal ACL/VFS/native durability unavailable;
four UNAVAILABLE/complete=false/Java custody/runtime Mixin/G3/binding READY/delivery unchanged.
No future Sandbox hash or production/deployment consent.

### Latest restricted Windows epoch custody source/mock acceptance

Parent accepted oracle final scoped source/mock PASS-with-residuals, clean local-only upstream
`6fe9e9dd8a16b9f6f6ee815aa7ded22d7ae04491`, no push/PR. Five-file slice implements restricted
WindowsEpochCustodyPlatform and optional PUBLIC DescriptorAllocationReceipt ownership seam;
store/worker/deployment_files unchanged. Descriptor/token/composite UNKNOWN stays terminal-monotonic;
temporary output storage preowned; opaque query failure not freed/queried/retried. Legacy default ACL
API preserved, legacy WindowsEpochPlatform.acquire zero-call unsupported/capability=false.
Fixture fixed bounded ResourceRecord session owner retains UNKNOWN handles/allocations/connections
after traceback clear/GC; cleanup UNKNOWN is FAIL, not ancestry SKIP; first fatal/independent closes,
exact journal owner/ACE multiset and sharing-violation-only expected lock failure are enforced in source.
Source/mock is not native ABI/ACL/share/journal/durability proof. Native fixture default OFF/NOT RUN;
existing ancestry unsupported SKIP is not PASS, never mutate existing ancestor ACLs. Real root/SID
policy absent; actual native execution requires separate authorization/native gates; EXTRA no Windows
directory-durability guarantee, no default activation.

Archive `Temp/opencode/epoch-fixture-priority-20261025-032009`: 104 collected/98 PASS/1 default-native
SKIP/5 DESELECTED/0 failures/errors, 7.5278769 s wall/exit0; 8/8 source and13/13 artifacts verified.
Final manifest `BE772444A89B9E80487B4B09D8AD6919E3BF9B8B2778F86E3A155C4587D5C364`;
aggregate `8CDAAB619D6C83A55B6A52951EF2182547B76F89B146C56DDA758458DC77F6C7`.
Five exact hashes in custody decision. Partial BLOCKs/92 subset remain superseded history, not retroactive
PASS. Worker294a12c7/storeaa522dc8 are distinct history. Strict network/interop57e46850 pins remain stale
and unchanged; actual5170f1d/57e46850 localhost history is not latest-HEAD execution. Four UNAVAILABLE/
complete=false/epochUNAVAILABLE/Java custody/runtime Mixin/G3/bindingREADY unchanged; no future Sandbox hash.

### Latest native attempt: BLOCKED_NATIVE_PREREQUISITE, not PASS

Ora18 independent evidence verdict/parent disposition for exactly ONE authorized opt-in invocation
at Sandbox `f8365bb` / clean local-only upstream `6fe9e9dd8a16b9f6f6ee815aa7ded22d7ae04491`:
1 collected/0 passed/0 failures/errors/1 SKIP, exit0, wall7.7335105 s/pytest0.81 s.
Exact `UNSUPPORTED_EXISTING_ANCESTRY: restricted owner/explicit-ALLOW/fixed-NTFS policy`.
Handled skip exit0 is not PASS. Historical NATIVE_NOT_RUN is superseded only by this read-only
prerequisite attempt, not positive native-backend validation. Reached TokenOwner bootstrap/read-only
ancestry assessment; helper skips only if owner cleanup not UNKNOWN, no cleanup UNKNOWN reported
on that path, not OS handle proof. Test-root creation/ACL setup/SQLite custody OPEN/DB sharing/
journal validation/lock child/CAS/reopen NOT_REACHED; no child branch/retry/alternate path/profile
change/ancestor ACL modification. No archived directory enumeration or OS directory audit.

Archive `Temp/opencode/epoch-native-once-20261025-032847`:8/8 source current-match/before-after same,
15/15 artifacts. Before=after `8A99C173F0B3B4C733EDE2238ED994BAE75B70014E17FE53FEDBA99DFC69A327`;
XML `C44D5E2D4FBE9B0DC8DA2C2559675908D68CBB7247FB3547185AF2A58D184CB1`;
aggregate `6066FA3239F6A164C264FD253E5E539E7A10B5F7DB23715386A5665DB56EBFDB`.
Retained environment only Python3.10.21/win32/pytest9.1.1; Windows build/bitness/SQLite PRAGMAs/
exact ancestor/subreason/volume-file ID/cleanup ownership snapshot NOT_RETAINED, no historical fill-in.
Prior98 mockPASS/1 defaultskip/5 deselected remains source/mock, not native validation. Parent preserves
host/profile UNSUPPORTED, no diagnostic/source expansion/rerun. Positive native needs compatible
approved environment or separately reviewed profile, NEVER ancestor ACL repair. No new run authorization.
Capabilityfalse/legacyunsupported/fourUNAVAILABLE/completefalse/epochUNAVAILABLE/Java custody/runtime
Mixin/G3/bindingREADY unchanged; stale57e46850 network pins untouched/upstream no pushPR.

### Latest Java deployment/credential custody SOURCE/MOCK acceptance

Parent accepted ora18 scoped PASS-with-residuals for four NEW transport loader/backend/test files,
reviewed bytes pending commit, no future hash guessed. Java filesystem loader now exists in source/mock
scope, production custody UNVERIFIED/unwired. Receipt sole lock/exact owner-slot record identity and
whole-operation borrow protect all live/pending records through calls/buffer consumption. Active
release seals UNKNOWN with no releasers/wait; late outputs stay original strong owner/no READY.
No numeric retry/global quarantine/destructor ownership. Actual tests cover policy defensive arrays,
short-read EOF/manifest verify/raw32/slot claim-ack-close. Publication requires all native closes
confirmed; secret wipe best-effort, not GC/paging/provider secure erasure. Inert NativeSurface exercises
same actual NativeApi mapping/finalpath/descriptor/identity/read code without actual JNA allocations/init.

Archive `Temp/opencode/java-seam-final-20261025-045625`: focused4 suites/55 tests/0 failures/errors/skips,
32.0478508 s; full62 suites/501 tests/0 failures/errors/9 skips,40.9054143 s/exit0 actual build/reobf.
Nine skips=3 connector network+6 old named2/1/2/1, not network PASS. Verified4/4 current118/118 all90/90
artifacts/frozen manifest stable. Final-four `CC4BB96A04665D3A8FEDE4C0F350F2B2A07E523B749783D2BE6CDDB7582FF814`;
final-all `9C5113C3F605AF4DEEDA783A5F1824C1649D4D20314188487A0E8C9A1EFAFA0B`;
aggregate `F12C4556C57E5A98423725AD3EBC77426E9AA6B22FB4FBA846807DC12B98E1CC`.
Exact four hashes in [decision](decisions/java-deployment-credential-custody.md). Prior partial BLOCKs/
497–498 history superseded, not retroactive PASS. No formatter/native/runtime/interop claim.
Real JNA ABI/Forge dedicated classloader/GetSecurityInfo LocalSize+restricted self-relative descriptor/
held path-share-mapping/real deployment SID-reader-mutator policy remain gates. Native/newnetwork/
startup/capabilitytrue unauthorized. Epoch host unsupported/epochUNAVAILABLE/fourUNAVAILABLE/
completefalse/runtime Mixin/G3/bindingREADY unchanged; upstream6fe9e9dd localonly/old pins stale untouched.

### Latest Java mechanism harness SOURCE/MOCK acceptance, not native execution

Ora18/parent scoped PASS-with-residuals accepts three NEW runner/test files, pending commit/no future
hash. Exact hashes in [Java custody decision §10](decisions/java-deployment-credential-custody.md).
First-error/independent cleanup, UNKNOWN strong caller ownership/class poison and failed-constructor
native_cleanup PID evidence reviewed. Manifest-before-final-report atomic no-replace publication;
exact classification permits unsupported descriptor ONLY -> BLOCKED_PROFILE, not custody PASS.
Native opt-in OFF before NativeApi; future heldIdentityAndRead/descriptorRepresentation/
compatibleReadSharing scenarios claim no incompatible-write/delete or proven Win32-error behavior.
No execution permission: committed-clean exact HEAD and separate parent authorization required.

Python `Temp/opencode/java-native-unit-gaps-20261025-053354`:15passed4subtests/19XML results,
0 failures/errors/skips/wall1.8062362s/exit0,16/16 artifacts; XML
`5FA4A3FB448327608FBC18F8784ECD2C127CA0823B7174D2FA221F86E8FC9093`, aggregate
`7428935937846ED9D5F41CC0B5370E2DF13D0DF1D2BECB02CB1DDB7EDE894C9A`.
FABRICATED complete identity fixture/local approved JAR copies are not native/classloading proof;
wrong declared/actual hashes rejected, real reparse branch before launch/evidence overwrite.
Unchanged Java archive `Temp/opencode/java-mechanism-final-20261025-052654`:64suites507tests0fail/error
12skips/40.9535175s exit0;85/85 aggregate `162FCDCA086D8CC7917746EE6D99B69CE369C2BBD6A02161E9C5F957DF87777C`.
12skips3newnative+9existing NOTnative/networkPASS. Java reused not rerun/newPythoncoverage; old base
three-source manifest Python entry SUPERSEDED/old13Python historical, not all-current attestation.
Historical BLOCKs remain superseded source/mock, not retroactive native PASS.
NATIVE_NOT_RUN/JAVA_CUSTODY_NOT_VALIDATED/dedicatedForgeClassloader NOT_ESTABLISHED/startupoff.
Actual JNA/class/runtime receipts authored only. Upstream6fe9e9ddlocalonly/hostunsupported/epochUNAVAILABLE/
fourUNAVAILABLE completefalse/runtimeMixin/G3/bindingREADY/stale network pins unchanged.

### Latest actual Java mechanism result: BLOCKED_PROFILE, not overall PASS

Ora18 adjudication/parent accepts ONE authorized LOCAL NON-NETWORK run at clean pre/post
`317f602f434018b2223eeee5ab2d4d09f8031363`:runner exit2/wall33.3551014s, fresh3JUnit0fail/error/skip,
Gradle7tasksSUCCESS but aggregateBLOCKED_PROFILE/JAVA_CUSTODY_NOT_VALIDATED/dedicatedForgeNOT_ESTABLISHED.
heldIdentityAndRead OBSERVED .293s/raw32+EOF; descriptorRepresentation unsupported .013s/querysuccess/
extent1024/revision1/control32772=0x8004/selfRelativetrue/candidatefalse; compatibleReadSharing OBSERVED
.019s/twoREADopens sameidentity, not incompatiblewrite/delete/conflictcode proof. Candidate0x9004 differs
by SE_DACL_PROTECTED0x1000 absent on unmodified dummy file; not absoluteDS/ABIbroken/allWindowsunsupported
or protectedflag-only full-parser proof. No conversion/relaxation/retry/ACL modification. Parent preserves
block; another fixture needs separate plan/auth, no newrun permission. Historical NATIVE_NOT_RUN superseded
only for this observed mechanism attempt, productioncustodyUNVERIFIED.
All3cleanupRELEASED retained0 unknownfalse/rawdummyfilesdeleted evidenceDIRSretained,notOSresourceproof.
Gradleroot18796 Jobassigned/root-treeexitconfirmed cleanupUnknownfalse forceKilledfalse attempts0/log2283bytes;
readerjoin/activecount code-enforced not serialized audit. RuntimeAdoptium17.0.20.1+1 Windows11/os.version10.0/
amd64/JVM64/pointer8; fullbuildNOT_RETAINED, DWORD4/legacyinfo52 SOURCECONSTANTS, Structure.sizeNOT_EXPOSED.
JNAcoreUSED5.12.1/1866196bytes, platformAVAILABLE_RESOURCE_NOT_NATIVE_PLATFORM_BINDING/1356627bytes;
approved hashes and5executedclasscopies verified, no oldmodJARfreshclaim.
Evidence `Temp/opencode/java-native-run-20261025-054105` + `java-native-preflight-20261025-054105`:
report `A7170E5C86DDD0495FADA6BBA50CCB144C78B509671B1C5581795834BA76C4FD`, XML
`246D849405131079BB9C1F4189F6BDF6FBE2B622E636FB778AD69E7846F67725`, runneraggregate
`D905BF9F9955E126DB09A9449C87C8ABB0F0F50FB99B2F140B86A22D35292BBB`, combined
`7CB44D9EBAB07D3784864FAFCA482F0DCDBB8A8B5BF74386A7B2C85137E5599C`.
Independent11source/9runnerartifacts/26combined/5classes+2JNA matched; nonceXML/runtime/report match,
priorXMLrecorded notdeleted. LIMITED closure not fullJVM/nativeattestation. Prior507Java/15+4Python history
notnativepositive. FourUNAVAILABLE completefalse epochhostunsupported/epochUNAVAILABLE Java custody/runtime
Mixin/G3/bindingREADY unchanged/upstream6fe9e9ddlocalonly/networkpinsstale;no furthernative/activation auth.

### Latest protected-dummy amendment SOURCE/MOCK acceptance, native setter NOT RUN

Ora18/parent scoped PASS-with-residuals accepts revised test fixture only, pending commit/no future hash.
Handle-bound SetSecurityInfo targets newly created DUMMY; preowned WRITE_DAC handle has held-read
identity, one explicit ALLOW0x00120089/copied validated fixture-owner SID (not deployment trust),
DACL/protection only/owner unchanged. Identity-owner-protection checks precede original-byte readback
and actual production assessment. No production parser/profile/API/build/ancestor ACL change; other
two case bodies unchanged/READ-sharing notwrite-delete denial. UNKNOWN poison/firstError strong-owner
receipt unchanged. Setup/postcondition failure=>runner BLOCKED/failed JUnit, not independent FAILED;
unsupported=>BLOCKED_PROFILE; exact PROTECTED_DUMMY_DESCRIPTOR_ACCEPTED marker not full custody.
Revised setter/share/parser compatibility untested/NATIVE_NOT_RUN; separate auth after cleancommit/
exactHEAD required. Old unprotected0x8004/317f602 BLOCKED_PROFILE historical not retroPASS.
Archive `Temp/opencode/java-protected-fixture-20261025-055649`:Python17+4subtests/21XML0fail/error/skip,
1.8587046s exit0; Javafocused1suite5safety0fail/error/skip29.9806921s;full64suites509tests0fail/error
12skips38.2790210s exit0/buildreobf;12=3native+9old NOTnative/networkPASS.3/3source91/91artifacts verified.
Finalthree `D84A0CB12EBBE5116F2543F003828E5607F2A9D8C92835B765068153C6C80ED5`, aggregate
`B861CD05979DDF3C2B614EFD6966C5C07F41E6FD79F6F83730BEF5D7A2E39EDB`; exact hashes decision§12.
No Javaformatter/native claim; custodyUNVERIFIED/dedicatedForgeNOT_ESTABLISHED/epochhostunsupported+
UNAVAILABLE/fourUNAVAILABLE completefalse/runtimeMixin/G3/bindingREADY/upstream6fe9e9ddlocalonly/stalepins unchanged.

### Latest protected-dummy actual invocation: BLOCKED_PROFILE, not aggregate PASS

Ora18/parent evidence verdict for ONE authorized LOCAL NON-NETWORK run at clean pre/post
14a2ae788e120a0f6ec7744a77e8d2f6549ed212:runner exit2/wall33.3594182s,Gradle7tasksSUCCESS,
fresh3JUnit0fail/error/skip nonceconsistent. heldIdentityAndRead OBSERVED.285s; descriptorRepresentation
DESCRIPTOR_PROFILE_UNSUPPORTED.030s; compatibleReadSharing OBSERVED.006s. Source backs successful
new-dummy-only handle SetSecurityInfo, heldidentity/ownerunchanged/protected checks then ORIGINAL
readback actual productionassess; catchesONLYUnavailable, not setup/cleanupfailure. Markers
NEW_DUMMY_PROTECTED_DACL/productionProfileChangedfalse/testOwnerPolicyFIXTURE_ONLY/
originalReadbackUnmodifiedtrue. Exact protected revision/control/extent/offset/rejectionSUBREASON
NOT_RETAINED: no layout/padding/ACE/control-cause/protected-missing inference. Earlier317f6020x8004
block separate unchanged history. Parent preservesblock/no parserrelaxation/rerun/retry/conversion/
sourcefix/ancestorACLchange; actualACLchange ONLYnewdummy. No newexecutionpermission.
All3receiptRELEASED retained0 unknownfalse/rawdummyfilesdeleted evidenceDIRSretained notOSquiescence.
Gradleroot25392 assigned/root-treeconfirmed cleanupUnknownfalse forceKilledfalse attempts0/log2283;
activecount/readerjoin sourceenforced notserializedOSaudit. READsharingONLY notwrite-delete denial.
Volume708319062/index6192449489643383/DeviceHarddiskVolume3/raw32EOF are bounded mechanismfacts notpolicy.
ActualJNAcoreUSED5.12.1/platformAVAILABLEonly,JDK17.0.20.1+1 Windows11os.version10.0amd64/JVM64pointer8;
fullWindowsbuildNOT_RETAINED/Structure.sizeNOT_EXPOSED/DWORD4legacy52SOURCEconstants.
Evidence `Temp/opencode/java-protected-native-run-20261025-060835`+`java-protected-native-preflight-20261025-060835`:
report `817F7BB1E17A7B53177A9F8F4B30D49B37EB74E507A5B693F96270BBC6AB316C`, XML
`A1BE5FC33A854D3008BD0A1B43492D8879E04BE9E784DF51035232FA59C52772`, runneraggregate
`64531BB6C11764AFA8FAEEC7B592CAF3C45B8DEE190C316AC5C3BCC72D5FC74B`, combined
`07B80F3896B9B669469FE26D2976E96280C4D777FE35C8CCEB74D8CD48C412D6`.
11/11sources9/9artifacts26/26combined5classcopies2JNA identities verified/sourceunchanged; LIMITED notfullJVM/native.
Revisedfixture NATIVE_NOT_RUN now historical, actual BLOCKED_PROFILE/JAVA_CUSTODY_NOT_VALIDATED/
dedicatedForgeNOT_ESTABLISHED. Prior17+4Python/509Java source/mock history notnativepositive.
FourUNAVAILABLE completefalse epochhostunsupported/epochUNAVAILABLE Java custody/runtimeMixin/G3/
bindingREADY unchanged/upstream6fe9e9ddlocalonly/pinsstale/no futurehash.

### Latest descriptor diagnostic SOURCE/MOCK acceptance, no native rerun

Ora18/parent scoped PASS-with-residuals and independent predicate-equivalence review: ONE parser/
bounded FIRST rejection enum, existing assess/Unavailable wrapper, baseline predicates/short-circuit/
helperbounds/order unchanged inclcontextnull/index/owner-2/exact0x9004/masks/layout. Private classified
signal ONLY caught, no Runtime/Error/Unavailable swallowing. First combined predicate not unique
rootcause; ACL_REVISION includes contiguouslayout. Fixture SAMEproductionreason entry/fixed enum+
length-revision-control facts/noSID-pointer-rawDS-freeexceptiontext; strict schema/statusreason/
canonicalints, independentcleanupUNKNOWN/classpoison. No native/setter/query/ACL/parserrelaxation;
14a2ae7 actualBLOCKED_PROFILE cause/layoutNOT_RETAINED remains unknown, no retrofill/PASS.
Evidence `Temp/opencode/java-assessment-reasons-20261025-061952`:Python18+4subtests XML22/0fail-error-skip
wall2.0516287s;Javafocused3suites27tests0fail-error-skip31.3943649s;full64suites511tests0fail-error12skips
41.0778240s all exit0actualbuildreobf,12=3mechanismnative+9old NOTnative/networkPASS. Finalbytes frozen
beforetests/BlackPython notJavaformatter. finalfive `3D629A434F9CC0D8E84670527D48E0EB2F9AF46B1BF0EBF3F70CDB61719840BD`;
160/160 all inventory hash `4240F14410B4F31B2C95CD6F98DA3846178CE7DA06FED5DC9E7A8ACB887FCAE5`;
predicateaudit `5E03F5B0FF2A18981F6D54A5CF7FC2B6897A46DAB99F1BD18F1E5DFEF49DE34C`;
aggregate `F035E800892657C1EA262A08AE43D83E3417998CDB75FC75D877F51D5A6A225C`95/95artifacts.
Baseline backend10A5C0AC…B1C1F49 archived/reviewed, not formalproof/testagreementalone. Exact5hashes
decision§14; pendingcommit/no guessedhash. Future native separateauth+cleanexactHEAD/freshclassJNAidentity,
notauthorizedhere. CustodyUNVERIFIED/dedicatedForgeNOT_ESTABLISHED/epochhostunsupportedUNAVAILABLE/
fourUNAVAILABLE completefalse/runtimeMixin/G3/bindingREADY/upstream6fe9e9ddlocalonly/stalepins unchanged.

### Latest diagnostic native attempt: BLOCKED_PROFILE preserved

Oracle/parent evidence adjudication NOTPASS for ONE authorized protected-dummy LOCAL NON-NETWORK
attempt at clean pre/post f1e21729ff8f49a2e8a0747b50254dc2e8dd70c0:runnerexit2/wall32.0222766s,
fresh3JUnit0fail/error/skip/sevenGradletasksSUCCESS. heldIdentityAndReadOBSERVED.329s,
descriptorRepresentationDESCRIPTOR_PROFILE_UNSUPPORTED.031s,compatibleReadSharingOBSERVED.008s.
SAMEproductionparser assessmentDESCRIPTOR_CONTROL descriptorBytes1024 revision1 control37892/0x9404
vsrequired0x9004; difference0x0400=SE_DACL_AUTO_INHERITED SOURCE semantic explanation, notnewnativefact.
Control enough to reject, FIRSTcombinedpredicate alsoreserved/groupSACL; otherterms/laterowner-layout-ACE
NOTESTABLISHED, no unique-cause/allow-bit/remove-bit inference. Earlier14a2ae7cause/layoutNOT_RETAINED
remainsunknown;317f6020x8004 distincthistory. Originalreadback unchanged/fixture-onlyowner/newdummy/
protectedsetup source-backed markers. Parent preservesblock/nosourcechange/rerun; readonly review
does not authorize implementation/execution.
Evidence `Temp/opencode/java-diagnostic-native-run-20261025-063241`+`java-diagnostic-native-preflight-20261025-063241`:
report `069BF99E5410D80AF17201C2D3DBB8DB67D2381133AA7C0DDFFED5CFFE99FDC1`,XML
`F07A7FD19AFD58EC0B208255B26EF9FD675CC57F36F235D2AAA83A1953295A36`,runneraggregate
`BD0212F9D623ABD9C9C935454D287FFCA7EA16E82CF097316421F60F37BB3286`,combined
`FC6AAE76FF56ADE80FE474AFC079C9CFEE71F7E713EDEC61D94AB7EC6F02A0AF`.
11/11sources9/9runner26/26combined5classes2JNA verified. All3receiptRELEASED retained0 unknownfalse,
dummyrawdeleted evidenceddirsretained notOSquiescence. Root860assigned/root-treeconfirmed noforce/
unknown/containment/log2283;readerjoin-activecount reviewedcode notserializedmetrics. Adoptium17.0.20.1+1/
Windows11version10.0amd64/JVM64ptr8/fullbuildNOT_RETAINED/Structure.sizeNOT_EXPOSED,sourcewidthconstantsdistinct;
JNAcore5.12.1USED/platformavailableonly. READsharing notwritedenial. No network/Hub/game/realcredential/
fullloader/conversion/parserrelaxation, LIMITEDnotfullnativeattestation. JavaCustodyNOT_VALIDATED/
dedicatedForgeNOT_ESTABLISHED/epochhostunsupportedUNAVAILABLE/fourUNAVAILABLE completefalse/runtimeMixin/
G3/bindingREADY/upstream6fe9e9ddlocalonly/stalepins unchanged/no futurehash.

Historical loopback passes with different test-support hashes remain historical evidence and are not treated as equivalent to the current frozen source snapshot. Earlier `prewire CONFIGURED` reports document configuration only; the independent message probe above documents observed protocol behavior.

At accepted movement snapshot `7c4f68a`, movement observation is accepted with runtime Mixin residuals; the local binding-quiescence slice has parent-accepted ora-18 final scoped source/evidence PASS-with-residuals, Java-only LOCAL INCOMPLETE and NOT complete; the public Java-only revocation prerequisite is accepted, with no release/tag. Agent-Luo latest accepted local-only anchor is `57e46850c56079a4e95c645234b696eebbaaaf4f`, clean with no push/PR/release/tag claim; `09687d48` admission history remains distinct. Earlier worker, transport/currentness, route, LP11, and headless-admission network records remain distinct historical evidence with their original scopes. The v2 document remains **SPEC_FROZEN / NORMATIVE CONTRACT**; production activation remains prohibited.

## Confirmed remediation history

- Confirmed and fixed: Java heartbeat watchdog compared nanoseconds with milliseconds; fixed in `c58c0e4`
- Confirmed and fixed: Python outbound heartbeats could starve behind receive traffic; fixed upstream in `2078976a`
- Confirmed and fixed: epoch-store/native-lock close uncertainty could lose cleanup ownership or retry ambiguously; fixed upstream in `70dcafb1` with terminal UNKNOWN/no-retry semantics
- Rejected hypotheses: Python cleanup starvation and connector-owned Java `HttpClient` strong-reference retention were investigated and not adopted
- Rejected implementation approach: synthetic lambda injection failed annotation processing and was rolled back; it is not implemented
- Container-close accounting P1: capture failure could skip original defense; closed in `38f59cd`. Capture RuntimeException permanently makes accounting UNKNOWN, then original fence/execute failures retain priority; capture Error attempts original fence and, only if it returns (false included), execute, then always rethrows the same capture Error. Fence failure never submits. No fatal-VM recovery guarantee

## Current safety state

- Existing runtime endpoints remain unregistered. An explicit unregistered headless constructor can, only with a test-available worker, redeem the accepted private route, admit, and record one `PREPARE`; default production worker availability remains `UNAVAILABLE`. It grants no READY authority/CAS, COMMIT, ACTIVE, binding, permit, input, provider, account, EULA, world, or public endpoint
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
- Historical declared bidirectional routes remain uncalled: S2C uses ID `1` with a distinct class, C2S uses ID `0`, and both retain the same profile/version. The accepted unregistered Java bridge now consumes test-exact local LP11 proof into a non-authoritative candidate envelope, but no production game connector/startup, authoritative BODY_BOUND join, binding, permit, or input exists
- The client logout adapter is explicit and unregistered. It uses only the event-carried exact connection and captured handle; null/stale/wrong-thread events do nothing, while cleanup failure retains the route and key owner. It does not claim coverage of every network-error path
- Verified proof provenance keeps the original Java-local monotonic deadline; it never refreshes TTL or compares an absolute Java monotonic value with a Python clock. Disconnect, permanent ingress close, expiry, replacement challenge and observer/generation failure terminally invalidate retained proofs
- The frozen controller rule remains unchanged: controller replacement or loss invalidates an unfinished notice/proof join. No grant-before-disconnect redemption rule is implemented
- The accepted Python v2 transport, unregistered Java proof bridge, coordinator-local currentness fence, and disabled loopbacks provide the bounded headless private-route redemption/admission/dispatch-ticket component only. They do not provide a Java filesystem mirror, production Java connector/startup, automatic Forge EventBus/channel registration, assembled BODY join, READY authority/CAS, COMMIT, ACTIVE, permit, input activation, or G3 evidence
- The public Java revocation receipt proves only old authority was revoked under its exact owner/generation conditions. It does not prove a vanilla accepted packet mutation finished, player position stopped, client input released, world-tick physics/hunger/damage halted, or Java-WebSocket session-token linkage
- Epoch capability remains `UNAVAILABLE`/`verified=False`: there is no auto-provisioning or memory fallback. Existing coordinator/store work remains reviewed; upstream `70dcafb1` repairs store cleanup ownership only

## Finite next work graph

1. Headless private admission and Java-only old-authority revocation are accepted bounded components only. They must not perform READY CAS, COMMIT, ACTIVE, permit re-enable, or startup registration; preserve the sole coordinator with no second role map or business scheduler
2. Bounded lookup completed without establishing an actual READY producer: `ControlRoutePolicy` `BINDING_READY` is directional declaration only, and caller-DTO `ServerControlCoordinator.QuiescenceProof(nonblank authorityEventId)` is neither quiescence authority nor consumed by the `OLD_AUTHORITY_REVOKED` receipt. No READY producer/delivery/local-IPC contract is frozen; no new `BINDING_READY` v2 wire kind or public `mint_ready(fields)` exists
3. Movement observation ACCEPTED (Sandbox `7c4f68a`, independent review PASS-with-residuals; supersedes the pending `ceb76ab` entry). Only the `handleMovePlayer` normal-return protected scope emits `PROTECTED_MOVEMENT_HANDLER_DRAINED`, via a bounded LIFO stack of depth 8 that fails closed on mismatch, overflow, wrong owner, empty-stack return, or exception. It is observation only: NOT READY, NOT quiescence, NOT rebind authority. The package-private read-only accessor `observeProtectedMovementDrained` checks owner thread first, then exact server/listener/player/binding, a real `OLD_AUTHORITY_REVOKED` receipt plus `validatesBindingPrepareFence`, and the original 5000 ms deadline
   - Mixin: movement RETURN injector is `require=1` and unbounded (prior `allow=1` was wrong; the target has 2 normal returns). Entry injector unchanged
   - Offline ASM target evidence on `forge-1.20.1-47.4.28_mapped_official_1.20.1`: class SHA-256 `7ad8af7bb6f18f3b1a1d99959b4826e5f475e424c177031f5ca0279332226e79`, length 100813, `handleMovePlayer` normalReturns 2, ensureRunningOnSameThread 1, ATHROW 0, try-catch 0, handlers 0, 631 insns. Evidence JSON is in the ignored `.gradle-user-home/interop/evidence/`
   - Review history: ora-11 BLOCK (`allow=1`, no cardinality evidence, direct `authorizeLast` in the positive test) → remediated → PASS-with-residuals
   - Verification: parent-independent offline `build reobfJar` ran 51 suites / 296 tests / 0 failures / 0 errors / 6 pre-existing opt-in loopback skips (ControlTransportLoopbackTest 2, PostAuthV2BodyRouteLoopbackTest 1, PostAuthV2LoopbackTest 2, PostAuthV2VerifiedBodyProofLoopbackTest 1). This is not network PASS; `reobfJar` is compile/remap only
   - Explicitly UNPROVEN residuals: transformed bytecode and runtime Mixin application (offline MixinService has no host without ModLauncher `Launcher.INSTANCE`). A real Forge boot must confirm both return sites are injected, the off-thread handoff path, and real reentrant order
   - Binding-quiescence observation: ora-18 final scoped source/evidence PASS-with-residuals, parent accepted exact reviewed Java bytes. Java-only LOCAL INCOMPLETE: focused 5 suites/87 tests/0 failures/errors/skips; full 52 suites/324 tests/0 failures/errors/6 existing opt-in skips (not network PASS), successful compile/test/build/reobf logs and 59 artifact hashes verified. The pre-doc-follow-up 10-entry source manifest SHA-256 is `7A1F415DFF2CD16AE93EBB0144B20874FF818196008687C828FB96C8F0829288`; evidence archive and detailed boundary are in [the decision](decisions/binding-quiescence-observation.md). Factory minting is package trust, not compiler-enforced bridge-only access. Exact-body sampling, original 5000 ms deadline, one-use consumption even on rejection, resampling plus post-fence consistency, and final volatile-read linearization apply; that read does not exclude future uninstall. Four scopes remain UNAVAILABLE, so production complete() is false; runtime Mixin residual remains. No READY/CAS/COMMIT/ACTIVE/re-enable/delivery or G3 acceptance. Future delivery needs a separately reviewed protocol extension; no approved v3 is claimed
   - Still open: no complete-quiescence/READY producer or delivery/IPC; local one-use observation provenance does not supply READY authority; READY CAS/COMMIT/ACTIVE stop gate; production connector/registration/credentials absent; full G3, EULA, real game/provider/persistence, and human acceptance unperformed
   - The `38f59cd` ledger is narrow mod-owned accounting, not a replacement observer for CHAT_DEFERRED, SCHEDULED_SERVER_TASKS, PERMIT_QUEUE_WATCHDOG or CLIENT_PERMIT_CONSUMPTION; all four remain UNAVAILABLE and complete=false
4. Next technical lane is bounded startup/preflight **design pending an independent plan**, not implemented production preflight or merely adding more observers. G3 branches remain: explicit production v2 startup and Java connector missing; Java verified filesystem/custody mirror absent (current parser accepts bytes, not verified filesystem custody); Windows epoch capability UNAVAILABLE/verified=False with owner/DACL unimplemented; authoritative READY producer and separately reviewed delivery missing. Frozen post-auth v2 kinds have no PREPARE/READY/COMMIT/ACTIVE business frames (handshake READY is distinct); no v3 is approved. Preserve the existing sole Python coordinator/current-role owner/worker, with no second authority map, auto-provisioning, registration or activation
   - `docs/decisions/java-v2-disabled-connector.md` staged architecture has parent-accepted mom-1 scoped PASS_WITH_RESIDUALS; the original six design BLOCK findings are resolved at design level, not implementation evidence. The separate session timing prerequisite now has ora18/parent scoped source/evidence PASS-with-residuals; full connector/production adapter work remains unauthorized. Next eligible stage is bounded production adapter primitives plus tests only after a separate bounded plan/review/authorization, not a test-only replica or the full four-file connector. Ports/mailbox/ingress/completion/cleanup evidence remains open
5. BODY join, permit/client input, Minecraft/EULA/provider/human/food persistence acceptance, and G3 remain unpassed

Future acceptance requires credible automated checks and the applicable Oracle gates. Sandbox validated stage/push to `origin/main` is authorized; releases and force-tags are not. Agent-Luo remains local-only with no push or PR authorization.
