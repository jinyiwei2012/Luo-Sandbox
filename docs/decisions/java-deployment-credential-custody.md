# Java deployment + credential custody — snapshot design draft

**Staged design scoped PASS_WITH_RESIDUALS — 2026-10-25; parent accepted after
independent momus review.** Prior four design BLOCKs are resolved at DESIGN level
only; history is retained, not retroactive implementation acceptance. Parent
authorized the exact four NEW transport files below for implementation + MOCK
validation only. Parent now accepts ora18 final cohesive four-file SOURCE/MOCK
PASS-with-residuals (§9), not native/deployment or production wiring acceptance.
Native execution, actual runtime/classloading, startup, dependency changes and
deployment remain unauthorized. This lane edits documentation only.
Sandbox baseline `4f6e4e283729173089eea3ef277ef8467c4eb13c`; no future commit
hash is asserted. Parallel offline classpath verification is independent work:
its supplied recorded results are scoped in §3, not native/runtime acceptance.
Parallel four-file writer changes are expected; no global clean/source-stability
claim is made here; accepted recorded counts below are not reruns by this docs lane.

## 1. Selected semantics and existing source contract

Parent selected **SNAPSHOT**, not lifetime file monitoring: explicit trusted
policy → caller-preowned receipt → held-handle bounded inspection/read → existing
manifest parse and verifyArtifacts → identity/security/namespace recheck → **all
native resources confirmed closed** → private provisioning snapshot/secret owner.
Later file changes, reload and revocation require separate contracts; this snapshot
neither watches files nor authenticates a socket. Native cleanup UNKNOWN forbids
publication, retains opaque ownership in the caller's receipt and attempts best-
effort wiping of application-owned secret buffers.

Read-only inspected anchors under `bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/`:

| Source | Existing contract, not new authority |
|---|---|
| `security/ControlDeploymentManifest.java:13-27,52-108` | 4096-byte cap, exact canonical 23-key ASCII manifest, ordered keys and one final LF |
| `security/ControlDeploymentManifest.java:111-138,154-172` | verifyArtifacts compares trusted role/install/config and three raw artifact digests; public caller-constructible ArtifactMatch is byte equality only |
| `security/ControlAuth.java:28-43,52-60,80-83` | Public Transcript clones input arrays, not authentication provenance; 32-byte key shape and actual HMAC routines |
| `transport/V2PeerConnector.java:18-61,76-77,131-133,193` | Package-private core/Context/CredentialOwner, disposable credential.copy and close; private verifier provenance is created by handshake, not a public transcript |
| [Frozen v2 §2.1](minecraft-control-postauth-v2-draft.md#21-role-scoped-deployment-manifest) | Config hash binds raw deployment manifest; explicit trusted absolute artifact paths, fail inactive on invalid files, no secret in manifest or auto-provision |

The four public path inputs are deployment manifest, mod manifest, raw policy
config and binding manifest. The manifest contains **digests**, not relative
path fields. A fifth, separately configured absolute credential path supplies
raw PSK bytes; it is not a manifest field. Key ID is external trusted config,
not an invented 24th manifest key. Existing parser/core/build stay frozen.

## 2. Explicit trusted policy and dormant interface

Proposed immutable `TrustedDeploymentPolicy`, inside DeploymentCredentialLoader,
contains all five original raw absolute paths, one explicit root, DWORD volume
serial and nonzero 64-bit root file index; expected role SERVER_AUTHORITY or
CLIENT_CONTROLLER, peer/hub install IDs, key ID and exact 32-byte config hash;
explicit expected owners as canonical SID bytes; distinct content-writer,
security-mutator and namespace-mutator SID sets, and a **separate credential
reader set**. Arrays are defensively copied; each SID tuple has 1..16 unique
canonical binary SIDs <=68 bytes. No expected value is learned from the owner,
token or hash just observed. No default Administrator/SYSTEM or current-user trust.
Missing actual root/SIDs/policy blocks deployment, not dummy mock tests.

All five paths must be distinct direct children of one explicit trusted root;
spanning roots rejects, never infers more roots. Artifact integrity
and credential confidentiality differ: file-data read grants must be limited to
the credential reader set, while security mutators can grant future reads and
therefore must be explicitly trusted. Owner's implicit security-control authority
must be covered too. Cross-category ALLOWs must satisfy every applicable policy
set; do not flatten writer/security/namespace/readers into one allowlist. Unknown
ACE semantics fail closed, not effective-permission approximation. Root/fixed-file
expected owners are explicit; ancestor owners must be security mutators. All held
objects require protected NONNULL DACL, standard EXPLICIT ALLOW flags0 only.
Reject DENY/object/callback/conditional/inherited/substitution principals/unknown
flags/masks; ACL <=4096 bytes/128 ACEs. Owner/security mutators are explicitly
trusted for credential confidentiality: reader filtering cannot defend against
their rights changes or trusted privileged bypass. No TokenOwner query is needed.

Expand GENERIC_READ `0x80000000` to `0x00120089`, WRITE `0x40000000` to
`0x00120116`, EXECUTE `0x20000000` to `0x001200A0`, ALL `0x10000000` to
`0x001F01FF` before object-specific classification. File `0x2/0x4` write/append
is content; directory aliases add-file/add-subdirectory are namespace. WRITE_EA
`0x10`/WRITE_ATTRIBUTES `0x100` are content metadata; DELETE `0x10000` and directory
DELETE_CHILD `0x40` namespace; WRITE_DAC `0x40000`/WRITE_OWNER `0x80000` security.
Credential FILE_READ_DATA `0x1` requires reader membership. Directory listing `0x1`,
READ_EA `0x8`, execute/traverse `0x20`, READ_ATTRIBUTES `0x80`, READ_CONTROL
`0x20000`, SYNCHRONIZE `0x100000` are nonmutation, not credential-data-read rights.
Reject other bits. Regular-file `0x40` rejects including GENERIC_ALL/direct
`0x001F01FF`, NEVER strip; strict availability policy, not meaningful file
child-delete authority. Cross-category grant meets EVERY relevant SID set.
Common full control/ancestry may be unsupported; never modify existing ACLs or relax for PASS.

Proposed explicit loader entry takes `(policy, nativePort, receipt, handoffSlot)`;
all references are required and construction is inert. No classload/native library
load, credential/file access, clock, registration or connection occurs in constructors.
No fallback from unavailable Windows custody to Files.readAllBytes or public
ArtifactMatch. Receipt/immutable snapshot/secret-owner types stay inside the loader.
There is no public `verified(fields)` factory, public Transcript authority factory,
secret getter or boolean availability capability. Only successful private loader
construction can mint its provisioning value, and that is provisioning provenance,
not handshake/binding READY or authority.

## 3. Public JNA seam and bounded offline classpath evidence

Candidate technology is **public JNA core 5.12.1**, explicit W/StdCall Win32
mapping. Pure JDK 17 APIs do not establish same-handle SID/DACL, desired access/
sharing and identity inspection for this contract. No sun API, reflection,
private-handle extraction, new dependency declaration or auto-download is approved.
Cached candidate identities supplied to this draft:

- core SHA-256 `91A814AC4F40D60DEE91D842E1A8AD874C62197984403D0E3C30D39E55CF53B3`
- platform SHA-256 `8CE969116CAC95BD61B07A8D5E07174B352E63301473CAAC72C395E3C08488D2`

Archive `Temp/opencode/jna-offline-20261025-034725`: resolved compile114/testCompile120/
runtime117/testRuntime124 entries all include core+platform5.12.1; jarJar1 Jackson
only. Cached Mojang server-bundler static embeds match supplied hashes, not actual
Forge47.4.28 dedicated classloader proof. Offline Gradle exit0/wall15.5221889 s,
Adoptium17.0.20.1/runtime17.0.20.1+1; aggregate
`38CC6CD457EA527B7005F0DBE41B9552AB29D758B541D130731FD42A42E0FAF7`.
javap without Native init confirms public Memory/Pointer/Structure/StdCall support.
Platform misses legacy GetFileInformationByHandle/BY_HANDLE_FILE_INFORMATION,
GetVolumeInformationByHandleW/GetFinalPathNameByHandleW/GetAclInformation: no
invented platform API or 128-bit identity substitution. Choose explicit core
W/StdCall mappings, no DEFAULT_OPTIONS/w32.ascii. Native.load only at explicit
native-operation boundary, never static INSTANCE/init or constructor Memory
allocation. Inert mock ports never touch binding holder. Missing JNA/linkage is
unavailable, no JDK fallback. Native ABI/dedicated runtime remain unverified;
no dependency/build amendment authorized without separate review.

Supplied official research basis: Oracle JDK 17 and current Microsoft docs retrieved
2026-10-25, plus JNA **5.17** documentation. That research is not local 5.12.1
compatibility, ABI or runtime attestation; no new external research ran here.

- [JNA public documentation](https://javadoc.io/doc/net.java.dev.jna/jna/5.17.0/)
- [JDK 17 Files](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html)
- [CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)
- [GetSecurityInfo](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-getsecurityinfo)
- [GetFileInformationByHandle](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getfileinformationbyhandle)
- [ReadFile](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-readfile)
- [LocalFree](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-localfree)
- [CloseHandle](https://learn.microsoft.com/en-us/windows/win32/api/handleapi/nf-handleapi-closehandle)

No token-owner query is needed for a read-only snapshot that creates nothing;
avoid introducing unnecessary token resources. Policy SIDs are not guessed from
TokenUser/TokenOwner. ABI structures, ownership on error and BOOL/HANDLE/pointer/
DWORD widths need exact local-version mapping tests before native authorization.

## 4. Bounded held-handle acquisition and reading

Validate original raw strings **before resolve/normalization/native calls**.
Proposed restricted bounds: at most 259 UTF-16 code units excluding terminator,
embedded NUL rejected, at most 32 components excluding drive root (33 directory
slots) and 5 fixed-file slots. Supplementary characters count as two units.
Require canonical drive-rooted local fixed NTFS; reject UNC/device/extended paths,
ADS, dot/dotdot, reserved/trailing-dot/space names, SUBST/noncanonical DOS mappings,
reparse points, multilink files, wrong object types or delete-pending objects.
Mapping must correlate with the held volume, original ancestor chain and expected
root serial/file ID. Use DWORD volume serial plus `(indexHigh << 32) | indexLow`,
not truncation/hash of a 128-bit FILE_ID_INFO. Unsupported identities fail closed.

Proposed static-file opens: GENERIC_READ `0x80000000` | READ_CONTROL `0x00020000`
| FILE_READ_ATTRIBUTES `0x80`, FILE_SHARE_READ `1` only, OPEN_EXISTING `3`,
OPEN_REPARSE_POINT `0x00200000`, non-inheritable. Proposed directories: READ_CONTROL
| FILE_READ_ATTRIBUTES, READ|WRITE share (`3`, no DELETE), BACKUP_SEMANTICS
`0x02000000` | OPEN_REPARSE_POINT. Enable no privilege. Exact masks and restricted
ACL profile remain review gates, not compatibility guarantees.

Hold ancestors/root and all files through reads/recheck. OPEN_REPARSE_POINT is
final-component behavior, not atomic all-component nofollow. Share denial does
not freeze DACL/content/DOS mappings and does not defeat ABA or privileged hostile
mutators. Explicit trusted security/namespace/content mutators must exclude
prohibited changes during the snapshot operation. Recheck held identity, volume,
mapping, size and descriptors before success; transient malicious mutation is not
proved absent by before/after sampling.

Read through held handles only, bounded native output buffers, exact short-read
loops and confirmed EOF. Manifest limit is 4096 bytes (read at most 4097 to detect
overflow). Proposed artifact cap is 1 MiB **each**, independently enforced before
parser cloning; existing verifyArtifacts itself has no such cap. Exact artifact
cap is the parent-selected local loader budget, not a frozen protocol
change. Credential is **exact raw 32 bytes**, read at most 33 with confirmed EOF:
short read is not EOF, 31/33 bytes reject. No String, trim, base64 or JSON secret.
No secret in error/log/toString, manifest or serialization. Caller-owned arrays
are never wiped; wipe only owned copies. Arrays.fill/Memory.clear are best-effort
application-buffer hygiene, not certified erasure of GC/JNI/provider/paging copies.

Use existing ControlDeploymentManifest.parse then verifyArtifacts on actual raw
bytes with trusted expectations. Do not rewrite JSON or rehash a normalized
manifest. Recheck all custody evidence, then close all native resources; publication
is forbidden until every close/release is confirmed. No snapshot can claim later
file changes are monitored, nor that provisioning equals socket authentication.

## 5. Receipt, native outputs and cleanup ownership

Caller preowns single-use CustodyAttemptReceipt before any native action: 38 held
handle slots (33 directories +5 files), one descriptor-allocation slot and four
query Memory slots <=4096 bytes each, plus one 520-byte WCHAR query buffer.
One descriptor allocation at a time, sequential slot reuse only after confirmed
release; no unbounded history/resize. Descriptor parsing <=4096 bytes/128 ACEs,
unknown extent opaque. Artifact storage <=3MiB plus overlapping verifyArtifacts
clones <=3MiB, explicitly not total heap. Secret buffer33 plus one retainedraw32
owner and later core disposable clone; no unaccounted copies/secret String.
Any native descriptor's **entire extent** must be proven before owner-SID/ACE
offset dereference: ACL bounds alone do not prove descriptor extent. Populated
ambiguous output stays opaque UNKNOWN with no dereference. JNA Memory is owned
immediately after allocation, before the next fallible work. Clear-before-free
applies to secret buffers; wipe failure cannot skip independent release, and
publication requires known cleanup. Claim/reentry guards
precede ports. Own native output storage **before** a query, register each returned
handle immediately, and retain partial resources strongly before fallible parsing.
Known successful output has exactly one correct releaser: kernel handles use
CloseHandle, GetSecurityInfo allocations use LocalFree; caller-owned JNA Memory
has its own lifecycle. Never substitute one releaser for another. A failed query
with populated/ambiguous output is opaque UNKNOWN: no guessed free, follow-up
query or close retry. Clear owned native secret buffers BEFORE native free attempts
and wipe Java buffers on failure; never parse/dereference ambiguous query output.

Attempt independent reverse cleanup despite earlier errors; preserve first fatal
identity, later fatal over earlier ordinary error. Terminal UNKNOWN is monotonic.
No destructor/GC/traceback ownership, automatic retry or global quarantine registry.
On UNKNOWN, caller retains the receipt for its containing lifetime, with opaque
ownership records/no public handles; best-effort wipe owned secret buffers and
publish no provisioning snapshot. Confirmed cleanup clears owned native refs.
Receipt evidence is bounded/sanitized, not exception attributes carrying secrets.

All resources confirmed released is necessary before publication, but not proof
of global OS quiescence, device durability or absence of all provider copies.

## 6. Private provisioning/secret ownership and package boundary

Caller creates a single-use HandoffSlot and CustodyAttemptReceipt BEFORE load.
Slot states EMPTY -> READY -> CLAIMED, with distinct terminal CLOSED_WIPED and
CLOSED_TRANSFERRED outcomes. EMPTY/READY/CLAIMED may close to CLOSED_WIPED;
only successful acknowledgment from CLAIMED becomes CLOSED_TRANSFERRED.
Loader privately publishes READY under the slot lock ONLY after real manifest
parse/verification, final custody recheck and ALL native resources confirmed
closed. Transfer one private secret owner, invoke NO external callback. No
arbitrary accept callback can retain a secret then throw. Slot must still be EMPTY;
if concurrently closed, publication rejects and loader wipes its owned buffer.

The private CredentialOwner close is idempotent; claim does not release ownership.
Trusted assembly claims private provisioning once (READY->CLAIMED). Claim is NOT
release: slot retains cleanup ownership until explicit noncallback acknowledgment
of successful connector construction. On constructor failure slot remains owner;
close wipes/closes and prevents subsequent copy/use. Success acknowledgment under
the same lock requires CLAIMED and credential not closed, and records connector
cleanup ownership, clearing slot ownership as CLOSED_TRANSFERRED, not wiped.
Close before ack wins: wipe/invalidate; subsequent ack rejects. Ack before close
wins: subsequent slot close never wipes the connector secret and connector owns close duty. Repeated
claim/ack rejects; repeated close does not repeat resource cleanup. Guards precede
any wiping/owner methods, no external callback under transfer bookkeeping.
If a connector object exists after close-won/ack-failed assembly must not start it;
discard/close under existing contract. These races prevent ownership loss, not
concurrent secret-use safety: trusted single-owner assembly must serialize
construction/copy/ack/close. Loader stage never constructs or starts a connector.
No concurrent copy-versus-wipe safety is promised. Failed acknowledgment or slot
close forbids connector start, even if construction returned an object.

No raw PSK getter, serialization, repr/log or general public capability escape.
The intended connector-facing contract stays existing CredentialOwner.copy()
(one disposable clone) and close(), not a new raw-secret accessor. Existing HMAC
and KDF internals may copy bytes; do not claim provider-secure erasure.

Both new classes and policy/provisioning/credential interfaces are package-private
in `transport`, enabling existing package-private V2PeerConnector.CredentialOwner
without a visibility change or bridge framework. Use public manifest parser;
no public raw-key/provenance factory or existing-core edit. Draft
loader mocks must exercise real manifest verification and actual secret ownership,
not leave an orphan receipt or pretend a boolean adapter authenticates a peer.

## 7. Proposed cohesive implementation envelope and tests

The authorized implementation + MOCK-only envelope is exactly four NEW files under `bridge/forge/src/`:

1. `main/java/io/github/jinyiwei2012/luosandbox/transport/DeploymentCredentialLoader.java`
2. `main/java/io/github/jinyiwei2012/luosandbox/transport/WindowsDeploymentCustody.java`
3. `test/java/io/github/jinyiwei2012/luosandbox/transport/DeploymentCredentialLoaderTest.java`
4. `test/java/io/github/jinyiwei2012/luosandbox/transport/WindowsDeploymentCustodyTest.java`

Policy/receipt/private provisioning live inside loader, not extra framework files.
Actual manifest and secret ownership form one cohesive load transaction. Existing
manifest/core/build are frozen; a necessary dependency amendment blocks
implementation until separately approved, not an invitation to expand scope.

Required mock matrix (authorized in the separate writer lane, no results claimed here): inert constructors
with zero native/credential effects; raw path rejection before ports; 4096/4097
manifest and bounded artifact reads; raw32/31/33 credential/short-read EOF;
artifact hash/role/ID/config and owner/SID/identity mismatch; partial acquisition
and query-output fatal cleanup; UNKNOWN strong retention and no publication before
all closes; one-use handoff/construction failure; no secrets in logs/errors;
caller arrays unchanged/not erased. Use actual parser/verifyArtifacts, not a replica.
Bind reads to purpose/path-slot identity and reject case-insensitive duplicate paths
and duplicate held file identities across slots. Short read is not EOF; no-progress
reads fail, and each artifact's extra overflow-detection byte remains bounded.
Approximate 3MiB loader +3MiB verifier overlap is not total-heap accounting; PSK
buffers and best-effort wipes do not establish secure erasure of every copy.

Later native evidence needs separate authorization: isolated temporary dummy files/
ACLs only, explicit opt-in, exact masks/ABI/output allocation/credential confidentiality
and handle sharing tests. Unsupported ancestry is BLOCK/SKIP, never PASS; never
alter existing ancestor ACLs to force success. No native test or real credential
access is approved by this draft.

## 8. Reconciled design choices and remaining evidence gates

Parent selected package placement, callback-free handoff, restricted ACL language,
budgets and explicit core mappings above to address the four design BLOCKs;
independent momus rereview and parent design acceptance are complete. Only the
four-file implementation + MOCK validation has scoped source acceptance below.
Remaining gates, not undecided package/handoff/classpath facts:

1. Source/mock review is complete only for the exact four-file bytes below; native/deployment remains unaccepted.
2. Native ABI/error-output behavior and actual dedicated Forge classloader are
   unverified; offline resolved classpaths are narrower evidence, not native PASS.
3. Any needed build/dependency amendment requires separate authorization.
4. Supply actual deployment root/identities/SIDs externally before deployment;
   dummy policy tests do not approve a real installation or credentials.

No startup/network, epoch capability, binding READY, frozen-wire change or new
deployment consent. Four UNAVAILABLE scopes, complete=false, epoch UNAVAILABLE,
Java filesystem custody unverified, runtime Mixin/G3/binding READY gates remain.
Epoch host/profile remains unsupported; missing production root/SID policy does
not block mocks but does block deployment. Native.load/INSTANCE/static binding
and constructor Memory allocation remain forbidden before explicit native operation;
inert mock ports must never touch the real binding holder. Actual JNA offline
classes are known, not actual Forge dedicated/runtime/native ABI acceptance.
This draft claims only the recorded offline classpath facts, not runtime completeness, and never plans/launches the
next network/native run. Parent must independently accept any broader implementation and
separately authorize any further stage; no source/test-result counts are invented.

## 9. Accepted cohesive four-file SOURCE/MOCK evidence, not native custody

Parent accepted ora18 scoped SOURCE/MOCK PASS-with-residuals for the actual loader,
backend and paired transport tests. Reviewed bytes remain pending commit; no future
Sandbox commit hash is guessed. Receipt uses its sole lock and exact owner/slot
record identity. A whole-operation borrow protects all live/pending records through
native-port calls and buffer consumption. Active release seals UNKNOWN without
releasers or waiting; late outputs remain with the original strong owner and cannot
publish READY. No numeric retry/global quarantine/GC-destructor ownership.
Actual tests cover private policy defensive arrays, short-read/EOF, existing
manifest verification/raw32 credential and slot claim/ack/close. Snapshot publication
requires all native resources confirmed closed first; wiping remains best-effort,
not secure erasure of every GC/paging/provider copy.

Inert low-level NativeSurface tests exercise the SAME actual NativeApi mapping,
final-path/descriptor/identity/read algorithms without real JNA allocation or
native initialization. Actual Windows backend source exists; compatibility with
real Win32 is NOT proven. No native/runtime/classloader/interop or formatter claim.

Archive `Temp/opencode/java-seam-final-20261025-045625`: focused 4 suites/55 tests,
0 failures/errors/skips, 32.0478508 s; full 62 suites/501 tests, 0 failures/errors,
9 skips, 40.9054143 s, exit0/actual build/reobf success. Nine skips are three connector
network opt-ins plus ControlTransportLoopbackTest2, PostAuthV2BodyRouteLoopbackTest1,
PostAuthV2LoopbackTest2, PostAuthV2VerifiedBodyProofLoopbackTest1, not network PASS.
Verified 4/4 current files, 118/118 all-source and 90/90 artifacts; frozen manifest
before/after stable. Final-four manifest
`CC4BB96A04665D3A8FEDE4C0F350F2B2A07E523B749783D2BE6CDDB7582FF814`;
final-all `9C5113C3F605AF4DEEDA783A5F1824C1649D4D20314188487A0E8C9A1EFAFA0B`;
aggregate `F12C4556C57E5A98423725AD3EBC77426E9AA6B22FB4FBA846807DC12B98E1CC`.

| Accepted file | SHA-256 |
|---|---|
| DeploymentCredentialLoader.java | `8B49682ECBC9453889FC94907E271906999B7A714DADC54C2533E3FE20ABFD86` |
| WindowsDeploymentCustody.java | `10A5C0AC2348B56C43358323321D08FA0DFB3C29CC55109DD6BA53A84B1C1F49` |
| DeploymentCredentialLoaderTest.java | `5C7C476567505C37880531008B170071CCEF7908BD471D0F2262A79E93EE6901` |
| WindowsDeploymentCustodyTest.java | `9ACF3BAB487F622051004ED3AA9EC67CAB5E23A394F015396DB1110A0F3C7324` |

Prior partial BLOCKs/497–498 counts remain genuine superseded history, not retroactive
PASS. Remaining gates include actual JNA5.12.1 Win32 ABI, dedicated Forge classloader,
GetSecurityInfo LocalSize/restricted self-relative-format compatibility, held-path/
share/mapping behavior and real root/SID reader-mutator policy. Java filesystem
custody is now implemented in source/mock scope, **production custody UNVERIFIED**.
No native execution/new network/startup/capability=true authorization. Epoch host
unsupported/epoch UNAVAILABLE/four UNAVAILABLE/complete=false/runtime Mixin/G3/
binding READY unchanged; upstream `6fe9e9dd` local-only untouched and old network
pins stay stale. Subsequent stages require separate review and authorization.

## 10. Accepted Java mechanism harness SOURCE/MOCK, native NOT RUN

Parent accepted ora18 scoped SOURCE/MOCK PASS-with-residuals for three new test/
runner files, reviewed bytes pending commit without a guessed future hash:

| File | SHA-256 |
|---|---|
| WindowsDeploymentMechanismNativeTest.java | `B6501CBD7A687BB50EE5DC93500F49BB5CB6F3E173B571C6FED1671C4A2E58FB` |
| verify_java_custody_native_smoke.py | `F649AF01B4FC799610DB0413CF0E261473B214966454E1C6B4321EBC454F4435` |
| test_java_custody_native_smoke.py | `ED2937F2FA960B3C8CBE25D51E07E6A20E8121FC07959911E0DE252F1E7AF8D7` |

Source/mock review covers first-error/independent cleanup, UNKNOWN strong caller
ownership/class poison; failed process-constructor native_cleanup retains PID
evidence. Evidence publishes manifest before final report using atomic no-replace.
Exact cases/classification restrict DESCRIPTOR_PROFILE_UNSUPPORTED to descriptor
case only -> BLOCKED_PROFILE, never custody PASS. Native default OFF is checked
before NativeApi. Three future scenarios are heldIdentityAndRead,
descriptorRepresentation, compatibleReadSharing: no incompatible-write/delete
or proved Win32-error behavior is claimed. Actual execution requires committed-clean
exact HEAD and separate parent authorization; none is granted here.

Final Python archive `Temp/opencode/java-native-unit-gaps-20261025-053354`:
15 passed +4 subtests (19 XML results), 0 failures/errors/skips, wall1.8062362 s,
exit0;16/16 artifacts verified. XML
`5FA4A3FB448327608FBC18F8784ECD2C127CA0823B7174D2FA221F86E8FC9093`;
aggregate `7428935937846ED9D5F41CC0B5370E2DF13D0DF1D2BECB02CB1DDB7EDE894C9A`.
Complete runtime identity fixture is FABRICATED with local approved JAR copies,
not native/classloading evidence. Wrong declared/actual JAR hashes reject;
actual reparse validation branch rejects before process launch/evidence overwrite.
Old 13-test Python run and earlier failed mock attempt remain historical, not final PASS.

Unchanged Java evidence reused from `Temp/opencode/java-mechanism-final-20261025-052654`:
full64 suites/507 tests/0 failures/errors/12 skips,40.9535175 s/exit0; aggregate
85/85 identities, hash `162FCDCA086D8CC7917746EE6D99B69CE369C2BBD6A02161E9C5F957DF87777C`.
Twelve skips=3 new native +9 existing, not native/network PASS. Java was not rerun
for the Python amendment, not new Python coverage. The old base three-source
manifest's Python test entry is SUPERSEDED; it cannot attest all current sources.
Historical BLOCKs closed at source/mock level, not retroactive native success.

**NATIVE_NOT_RUN / JAVA_CUSTODY_NOT_VALIDATED / dedicatedForgeClassloader
NOT_ESTABLISHED**; production startup remains off. Runtime/JNA/class receipts
are authored validators only, not actual observations. No native/network evidence
or execution permission. Local-only upstream `6fe9e9dd`, host unsupported/epoch
UNAVAILABLE, four UNAVAILABLE/complete=false/runtime Mixin/G3/binding READY and
stale network pins remain unchanged.

## 11. One actual LOCAL NON-NETWORK mechanism run: BLOCKED_PROFILE

Ora18 evidence adjudication/parent acceptance is **BLOCKED_PROFILE, NOT overall
PASS** for exactly ONE authorized run at clean pre/post Sandbox
`317f602f434018b2223eeee5ab2d4d09f8031363`. Runner exit2/wall33.3551014 s;
fresh JUnit3 cases/0 failures/errors/skips; Gradle7 tasks SUCCESS. JUnit/build
success does not override aggregate BLOCKED_PROFILE/JAVA_CUSTODY_NOT_VALIDATED/
dedicatedForge NOT_ESTABLISHED. Earlier NATIVE_NOT_RUN is historical for this
mechanism scope only; production custody remains UNVERIFIED.

| Scenario | Actual observation |
|---|---|
| heldIdentityAndRead | OBSERVED, 0.293 s, dummy raw32 plus EOF |
| descriptorRepresentation | DESCRIPTOR_PROFILE_UNSUPPORTED, 0.013 s; query succeeded, extent1024/revision1/control32772 (`0x8004`), selfRelative=true/candidate=false |
| compatibleReadSharing | OBSERVED, 0.019 s; two READ opens/same identity, not write/delete denial or conflict-code proof |

Candidate `0x9004` differs by `0x1000` SE_DACL_PROTECTED, absent on the unmodified
dummy file. This does not prove absolute descriptor representation, broken ABI,
all Windows unsupported, or protected-flag-alone complete-parser compatibility.
No conversion/relaxation/retry/ACL modification. Parent preserves current profile
block; another fixture requires separate plan/authorization, no new run permission.
All three cleanup states RELEASED/retained0/unknown=false; native raw dummy files
deleted, evidence directories retained, not all-OS resource proof. Owned Gradle
root18796 Job assigned/root-tree exit confirmed/cleanupUnknown=false/forceKilled=false/
attempts0, log2283 bytes. Reader join/active-count checks are reviewed source, not
separately serialized OS audit.

Actual runtime Adoptium17.0.20.1+1, Windows11/os.version10.0/amd64/JVM64/pointer8.
Full Windows build NOT_RETAINED. DWORD4/legacy-info52 are SOURCE CONSTANTS;
Structure.size NOT_EXPOSED. JNA core USED5.12.1,1866196 bytes,
`91A814AC4F40D60DEE91D842E1A8AD874C62197984403D0E3C30D39E55CF53B3`;
platform AVAILABLE_RESOURCE_NOT_NATIVE_PLATFORM_BINDING,1356627 bytes,
`8CE969116CAC95BD61B07A8D5E07174B352E63301473CAAC72C395E3C08488D2`.
Five executed-class copies/hashes verified; no old mod-JAR fresh-build claim.

Archives `Temp/opencode/java-native-run-20261025-054105` and
`java-native-preflight-20261025-054105`: report
`A7170E5C86DDD0495FADA6BBA50CCB144C78B509671B1C5581795834BA76C4FD`;
XML `246D849405131079BB9C1F4189F6BDF6FBE2B622E636FB778AD69E7846F67725`;
runner aggregate `D905BF9F9955E126DB09A9449C87C8ABB0F0F50FB99B2F140B86A22D35292BBB`;
combined `7CB44D9EBAB07D3784864FAFCA482F0DCDBB8A8B5BF74386A7B2C85137E5599C`.
Independent11 declared source identities current-match/9 runner artifacts/26 combined/
five classes+two JNA identities verified. Nonce XML/runtime/report match; prior XML
recorded, not deleted. LIMITED closure, not full JVM/native attestation. Historical
507 Java/source-mock and15+4 Python remain distinct, not native-positive proof.
No further native/production activation authorization. Four UNAVAILABLE/complete=false/
epoch host-profile unsupported/epoch UNAVAILABLE/Java custody/runtime Mixin/G3/
binding READY unchanged; upstream6fe9e9dd local-only untouched/network pins stale.
No future commit hash asserted.

## 12. Protected-dummy amendment SOURCE/MOCK accepted; setter NOT RUN

Parent accepts ora18 scoped SOURCE/MOCK PASS-with-residuals for the test-only
protected-dummy amendment, reviewed bytes pending commit/no guessed future hash.
The new fixture uses handle-bound SetSecurityInfo on a newly created DUMMY only:
preowned WRITE_DAC handle identity matches held read handle; one explicit ALLOW
mask `0x00120089` copies the validated fixture owner SID, not deployment trust.
Only DACL/protection changes, owner unchanged. Identity/owner/protection checks
precede original-byte readback through actual production assessment. No production
parser/profile/API/build or ancestor ACL changes. Other two case bodies unchanged;
compatible READ sharing still does not establish write/delete denial.
Class UNKNOWN poison/first-Error receipt strong ownership unchanged. Setup or
postcondition failures are actual runner BLOCKED/failed-JUnit paths, not a new
independent FAILED status. Unsupported profile remains BLOCKED_PROFILE; parser
accepts only exact `PROTECTED_DUMMY_DESCRIPTOR_ACCEPTED` fixture marker, never
full custody. Native setter/share compatibility/parser format are untested.
**NATIVE_NOT_RUN applies to this revised fixture**; execution needs separate
authorization after clean commit/exact HEAD. Earlier unprotected control0x8004
BLOCKED_PROFILE at `317f602` remains unchanged history, not retroactive PASS.

Archive `Temp/opencode/java-protected-fixture-20261025-055649`: Python17 passed+
4 subtests (21 XML results),0 failures/errors/skips,wall1.8587046s/exit0;
focused Java1 suite/5 safety tests,0 failures/errors/skips,29.9806921s; full64 suites/
509 tests,0 failures/errors/12 skips,38.2790210s,exit0/build/reobf. Twelve skips=
3 mechanism-native+9 old, not native/network PASS. No Java-formatter/native-run claim.
Independent3/3 source and91/91 artifacts verified. Final-three manifest
`D84A0CB12EBBE5116F2543F003828E5607F2A9D8C92835B765068153C6C80ED5`;
aggregate `B861CD05979DDF3C2B614EFD6966C5C07F41E6FD79F6F83730BEF5D7A2E39EDB`.

| Revised file | SHA-256 |
|---|---|
| WindowsDeploymentMechanismNativeTest.java | `F5512EE51C5558000635DDDBD5D28CD162031C607F6D8F878F84C40F47FA6434` |
| verify_java_custody_native_smoke.py | `25A24A611FAE2E30B6C7212099AD704E746B403E618291569D37383CBDD43B72` |
| test_java_custody_native_smoke.py | `7669C8E64E4EBD79734063FC42DC885CA764359416C1734CDC567D09D0605926` |

Java filesystem custody UNVERIFIED/dedicatedForge NOT_ESTABLISHED/epoch host
unsupported and UNAVAILABLE/four UNAVAILABLE/complete=false/runtime Mixin/G3/
binding READY remain. Upstream6fe9e9dd local-only untouched, old network pins stale.

## 13. Protected-dummy actual invocation: BLOCKED_PROFILE retained

Parent accepts independent ora18 evidence adjudication **BLOCKED_PROFILE, NOT
aggregate PASS** for ONE authorized LOCAL NON-NETWORK invocation at clean pre/post
`14a2ae788e120a0f6ec7744a77e8d2f6549ed212`. Runner exit2/wall33.3594182s;
Gradle7 tasks SUCCESS/fresh3JUnit0 failures/errors/skips, nonce consistent.
heldIdentityAndRead OBSERVED0.285s; descriptorRepresentation
DESCRIPTOR_PROFILE_UNSUPPORTED0.030s; compatibleReadSharing OBSERVED0.006s.
Historical NATIVE_NOT_RUN for this amendment is superseded only by this blocked
execution, not production custody acceptance. JAVA_CUSTODY_NOT_VALIDATED and
dedicatedForge NOT_ESTABLISHED remain.

Reviewed source backs successful handle-bound SetSecurityInfo ONLY on the new
dummy, then held identity/unchanged-owner/protected-bit checks and ORIGINAL
readback passed to production assess. assessFixture catches ONLY Unavailable:
unsupported classification is not setup/cleanup failure. Markers:
fixture=NEW_DUMMY_PROTECTED_DACL, productionProfileChanged=false,
testOwnerPolicy=FIXTURE_ONLY, originalReadbackUnmodified=true. Exact protected
revision/control/extent/offset/rejection SUBREASON are NOT_RETAINED. Do not infer
layout/padding/ACE/control cause or missing protected bit. Earlier unprotected
`317f602`/0x8004 block stays separate history. Parent preserves block, no parser
relaxation/rerun, retries/conversion/source fix or ancestor ACL changes. Authorized
ACL change was ONLY the new dummy; no new execution authorization follows.

All3 receipts RELEASED/retained0/unknown=false; raw dummy files deleted/evidence
directories retained, not OS quiescence. Owned Gradle root25392 Job assigned/
root-tree confirmed/cleanupUnknown=false/forceKilled=false/attempts0/log2283bytes.
Active-count/reader-join checks are reviewed code, not serialized details/OS audit.
READ-sharing ONLY, not write/delete denial. Bounded observed mechanism facts:
volume708319062/index6192449489643383/mapping DeviceHarddiskVolume3/raw32+EOF,
not deployment identity policy. JNA core USED5.12.1/platform AVAILABLE only;
JDK17.0.20.1+1/Windows11/os.version10.0/amd64/JVM64/pointer8; full Windows build
NOT_RETAINED, Structure.size NOT_EXPOSED, DWORD4/legacy52 SOURCE constants.

Archives `Temp/opencode/java-protected-native-run-20261025-060835` and
`java-protected-native-preflight-20261025-060835`: report
`817F7BB1E17A7B53177A9F8F4B30D49B37EB74E507A5B693F96270BBC6AB316C`;
XML `A1BE5FC33A854D3008BD0A1B43492D8879E04BE9E784DF51035232FA59C52772`;
runner aggregate `64531BB6C11764AFA8FAEEC7B592CAF3C45B8DEE190C316AC5C3BCC72D5FC74B`;
combined `07B80F3896B9B669469FE26D2976E96280C4D777FE35C8CCEB74D8CD48C412D6`.
Independent11/11 declared sources,9/9 runner artifacts,26/26 combined identities,
5 executed-class copies and2 JNA JAR identities verified; source bytes unchanged.
LIMITED closure, not full JVM/native attestation. Prior17+4Python/509Java evidence
remains source/mock history, not native-positive custody. Frozen harness hashes
remain those in §12. Four UNAVAILABLE/complete=false/epoch host unsupported/epoch
UNAVAILABLE/Java filesystem custody/runtime Mixin/G3/binding READY unchanged;
upstream6fe9e9dd local-only/network pins stale. No future commit hash guessed.

## 14. Descriptor diagnostic SOURCE/MOCK accepted; predicates unchanged

Parent accepts ora18 scoped SOURCE/MOCK PASS-with-residuals and independent
predicate-equivalence review. ONE parser supplies bounded first-rejection enum;
existing assess/Unavailable wrapper retains baseline accept/reject predicates,
short-circuit/helper bounds/order, including context-null/index/owner-minus-two,
exact `0x9004`, masks and layout. Only private classified signal is caught; no
RuntimeException/Error/Unavailable swallowing. A category names the FIRST combined
predicate, not unique root cause; ACL_REVISION also includes contiguous layout.
Fixture uses the SAME production reason entry, fixed enum/length/revision/control
facts, never SID/pointer/raw descriptor/free exception text. Strict schema/status-
reason/canonical integers enforced; cleanup UNKNOWN/class poison independent.
No new native/setter/query/ACL execution or parser relaxation. Historical protected
`14a2ae7` actual BLOCKED_PROFILE rejection/layout NOT_RETAINED remains unknown:
diagnostics cannot retroactively fill that run or convert it to PASS.

Archive `Temp/opencode/java-assessment-reasons-20261025-061952`: Python18 passed+
4 subtests/22 XML results,0 failures/errors/skips,wall2.0516287s; focused Java3
suites/27 tests,0 failures/errors/skips,31.3943649s; full64 suites/511 tests,
0 failures/errors/12 skips,41.0778240s,all exit0/actual build/reobf. Twelve skips=
3 mechanism native+9 old, not native/network PASS. Final bytes frozen before tests;
Python Black is not a Java-formatter claim. Final-five manifest
`3D629A434F9CC0D8E84670527D48E0EB2F9AF46B1BF0EBF3F70CDB61719840BD`;
final-all160/160, inventory `final.sha256` hash
`4240F14410B4F31B2C95CD6F98DA3846178CE7DA06FED5DC9E7A8ACB887FCAE5`;
predicate audit `5E03F5B0FF2A18981F6D54A5CF7FC2B6897A46DAB99F1BD18F1E5DFEF49DE34C`;
aggregate `F035E800892657C1EA262A08AE43D83E3417998CDB75FC75D877F51D5A6A225C`,95/95 artifacts.
Baseline backend `10A5C0AC2348B56C43358323321D08FA0DFB3C29CC55109DD6BA53A84B1C1F49`
archived/reviewed; review plus tests is not formal proof or test agreement alone.

| Frozen reviewed file | SHA-256 |
|---|---|
| WindowsDeploymentCustody.java | `DA84B37B9EE8DA45E1D19616E8B82F2885DCE7FD7F363838BFA8F1903B3CD808` |
| WindowsDeploymentCustodyTest.java | `A7AACCBA246B34C170541F31DD18AF194ECD90E64A3325EAE6C27673F18741BA` |
| WindowsDeploymentMechanismNativeTest.java | `E5B5C112EFBBAFB799A89B7672F526C9045DDB37A748C431A8BFAE328D053AEF` |
| verify_java_custody_native_smoke.py | `6CDF5C0A2908DD732B9C523B1F69B744584C905D70FBF49970D249AE91824B07` |
| test_java_custody_native_smoke.py | `B6B2920EBD35E7F8EAEF7162222A9AB287106993B6BA7F77398DE22B1A23A2C8` |

Reviewed bytes pending commit, no guessed future hash. Future native run requires
separate parent authorization/committed-clean exact HEAD/fresh class+JNA identity;
diagnostic acceptance authorizes none. Production custody UNVERIFIED/dedicated
Forge NOT_ESTABLISHED/epoch host unsupported+UNAVAILABLE/four UNAVAILABLE/
complete=false/runtime Mixin/G3/binding READY unchanged; upstream6fe9e9dd local-only
and stale network pins untouched. Historical BLOCKs remain, not retroactive PASS.

## 15. Diagnostic protected-dummy actual native result: BLOCKED_PROFILE

Parent accepts oracle evidence adjudication **BLOCKED_PROFILE, NOT PASS**, for
exactly ONE authorized diagnostic attempt at clean pre/post
`f1e21729ff8f49a2e8a0747b50254dc2e8dd70c0`. Runner exit2/wall32.0222766s;
fresh3JUnit0 failures/errors/skips, seven Gradle tasks SUCCESS, not aggregate PASS.
heldIdentityAndRead OBSERVED0.329s; descriptorRepresentation
DESCRIPTOR_PROFILE_UNSUPPORTED0.031s; compatibleReadSharing OBSERVED0.008s.
SAME production parser reports assessment DESCRIPTOR_CONTROL, descriptorBytes1024,
revision1/control37892=`0x9404`, required `0x9004`. Numeric difference `0x0400`
is SE_DACL_AUTO_INHERITED by source semantics, not a separate native observation.
Actual control alone suffices to reject, but the FIRST COMBINED predicate also
contains reserved/group/SACL conditions: other terms and later owner/layout/ACE
checks are NOT ESTABLISHED. No unique-cause inference or authorization to allow/
remove the bit. Earlier protected `14a2ae7` cause/layout NOT_RETAINED stays unknown;
unprotected `317f602`/0x8004 is distinct history, not retroactively explained.
Original-readback-unmodified/fixture-only owner/new-dummy/protected-setup markers
remain source-backed. Parent preserves block, no source change/rerun; read-only
official review may follow, not implementation/execution permission.

Archives `Temp/opencode/java-diagnostic-native-run-20261025-063241` and
`java-diagnostic-native-preflight-20261025-063241`: report
`069BF99E5410D80AF17201C2D3DBB8DB67D2381133AA7C0DDFFED5CFFE99FDC1`;
XML `F07A7FD19AFD58EC0B208255B26EF9FD675CC57F36F235D2AAA83A1953295A36`;
runner aggregate `BD0212F9D623ABD9C9C935454D287FFCA7EA16E82CF097316421F60F37BB3286`;
combined `FC6AAE76FF56ADE80FE474AFC079C9CFEE71F7E713EDEC61D94AB7EC6F02A0AF`.
Independent11/11 declared sources,9/9 runner artifacts,26/26 combined identities,
5 executed classes and2 JNA JARs verified. All3 receipts RELEASED/retained0/
unknown=false; dummy raw files deleted/evidence directories retained, not OS
quiescence. Owned root860 assigned/root-tree confirmed/no force/unknown/containment;
reader-join/active-count code enforced, detailed metrics not serialized, log2283bytes.
Runtime Adoptium17.0.20.1+1/Windows11 version10.0/amd64/JVM64/pointer8;
full Windows build NOT_RETAINED, Structure.size NOT_EXPOSED; source width
constants remain distinct. JNA core5.12.1 USED/platform available only.
Compatible READ only, not write-denial proof. No network/Hub/game/real credential/
full loader/source conversion/parser relaxation. LIMITED evidence, not full JVM/
native attestation. JAVA_CUSTODY_NOT_VALIDATED/dedicatedForge NOT_ESTABLISHED/
epoch host unsupported+UNAVAILABLE/four UNAVAILABLE/complete=false/runtime Mixin/
G3/binding READY unchanged; upstream6fe9e9dd local-only/network pins stale. No
future commit hash or new execution authorization.

## 16. Local versioned control profiles — staged DESIGN accepted, code pending

Parent accepted independent momus staged DESIGN **PASS_WITH_RESIDUALS** and
separately authorized the five-file implementation + MOCK stage below, now running.
Parent now accepts ora18 five-file **SOURCE/MOCK scoped PASS-with-residuals** (§17),
not native/deployment approval. This is an **acceptance-language
EXPANSION**, not an equivalence correction. Existing source/mock acceptances and
historical native BLOCKED_PROFILE verdicts remain unchanged. No native execution
authorization. This disjoint docs lane makes no global clean/source-stability claim.

Frozen LOCAL AssessmentProfile enum IDs, not manifest or wire fields:

| Local profile ID | Exact allowed unsigned 16-bit control values |
|---|---|
| `STRICT_SNAPSHOT_V1` | `{0x9004}` |
| `PROTECTED_AUTO_INHERITED_SNAPSHOT_V1` | `{0x9004, 0x9404}` |

STRICT stays default and all existing entrypoint/constructor behavior stays
unchanged. Authorized API is a package-private overload
`assessReason(raw, policy, directory, owner, AssessmentProfile profile)`; the
existing entries delegate STRICT and preserve legacy caller-error ordering.
ONLY the new profiled overload performs `Objects.requireNonNull(profile)` BEFORE
parse: null throws NPE, never falls back. One parser changes only control
membership, not other predicates. Selection belongs only to an explicit trusted
local assessment caller, never readback/host/error detection, retry/fallback,
manifest, wire, default Context or automatic loader/coordinator/startup choice.
The second profile implementation has scoped source/mock acceptance, not deployment
approved. No normalization to default for malformed selection.

Authorized edit scope is exactly WindowsDeploymentCustody.java, its paired test,
WindowsDeploymentMechanismNativeTest.java, verify_java_custody_native_smoke.py
and its paired Python test. No loader/core/build/native-binding change.
Runner exact `--assessment-profile` option defaults STRICT when omitted;
empty/unknown/malformed values fail BEFORE native/process launch. Dedicated env
contains exact enum, and native opt-in gate runs first. Candidate selection is
explicit local assessment only; loader/core/manifest/wire/startup unchanged.
Required profileId evidence matches requested==emitted, with consistent status/
reason; old XML stays historical, not new-schema evidence. Option authorization
does NOT authorize candidate native invocation or another native run.

Compare control by exact membership, never masking unknown bits. AUTO_INHERIT_REQ
`0x0100`, DACL_DEFAULTED `0x0008`, missing PROTECTED and every undeclared control
combination remain rejected. SE_DACL_AUTO_INHERITED control state is distinct
from an ACE's INHERITED_ACE flag: inherited ACE flags remain rejected, exactly
zero ACE flags required. No added principals or implicit Administrator/SYSTEM
trust. The proposed change permits only the additional exact control value;
ALL other predicates remain unchanged:

- Descriptor envelope/extent/revision and reserved byte, zero group/SACL offsets;
  owner security-mutator membership and expected-owner matching, including the
  existing context/null/index/owner-minus-two behavior rather than new coercions.
- Owner offset exactly20, DACL immediately after owner SID, ACL revision/reserved
  fields and bounded extent/count; contiguous ACE layout and exact allocation end.
- Standard explicit ALLOW grammar, SID/substitution restrictions, flags exactly0,
  generic expansion/masks, regular-file `0x40` unsupported even via full control.
- Separate content/security/namespace mutator sets and credential-reader policy;
  every touched category, snapshot custody rechecks, cleanup/UNKNOWN/secret ownership.

Source reference: `WindowsDeploymentCustody.java:190-231`, especially combined
DESCRIPTOR_CONTROL predicate at202, owner at205, ACL_REVISION+layout207-208 and
ACE/mask/policy checks215-231. The diagnostic still names the FIRST combined
failed predicate, not a unique root cause. Actual `0x9404` suffices for current
STRICT rejection; other combined terms and later checks are not thereby proven.
The earlier `f1e2172` run did not retain exact layout/ACE facts and cannot be
reconstructed or relabeled under the proposed profile.

Supplied Microsoft-current research, retrieved **2026-10-25**, describes legitimate
protected+AUTO_INHERITED control state with explicit ACEs. Control state does not
directly grant access, but this is not an unconditional harmlessness claim or
permission to ignore inheritance/security policy. GetSecurityInfo as getter is
not proven to have caused the observed bit; no retroactive causal explanation.
These are existing research references, not new retrieval or runtime experiments:

- [SECURITY_DESCRIPTOR_CONTROL](https://learn.microsoft.com/en-us/windows/win32/secauthz/security-descriptor-control)
- [ACE_HEADER](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-ace_header)
- [ConvertToAutoInheritPrivateObjectSecurity](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-converttoautoinheritprivateobjectsecurity)

Required MOCK-stage pure-fixture validation (accepted evidence in §17): start from a
known accepted synthetic raw descriptor; toggle ONLY `0x0400` to demonstrate
STRICT rejection/new-profile acceptance. Exhaust all 65536 control values on an
otherwise valid descriptor: each profile accepts only its declared exact set.
Combine AUTO_INHERITED with inherited ACE, unsafe SID rights, owner mismatch,
nonzero reserved/group/SACL, malformed layout: all must reject. Assert raw bytes/
policy immutable, explicit selection/no fallback and profileId/status consistency.
Future evidence schema must require profileId plus source hashes, without new
secret fields. Old historical evidence keeps original schema/STRICT meaning;
do not reinterpret old native results or silently apply a new profile.

A coherent policy decision can be tested with pure fixtures, without another
native attempt. Native execution always needs separate future parent authorization.
Any additional native layout/profile block must be preserved and independently
reviewed, never met with blind successive flag/layout loosening. Epoch host/profile
unsupported/epoch UNAVAILABLE/four UNAVAILABLE/complete=false/Java custody unvalidated/
dedicatedForge NOT_ESTABLISHED/runtime Mixin/G3/binding READY remain unchanged.
Upstream6fe9e9dd local-only and stale network pins untouched. Before source review:
retain the five-file implementation/mock evidence, exhaustive controls plus
safety-negative cases and independent STRICT diff review in §17. These are not
candidate native/deployment acceptance. Native compatibility/dedicated Forge/real deployment policy
remain gates, separate from accepted design and authorized mock stage.

## 17. Candidate profile SOURCE/MOCK accepted; candidate native NOT RUN

Parent accepts ora18 scoped PASS-with-residuals for exact five-file source/mock
bytes below, pending commit/no guessed future hash. STRICT_SNAPSHOT_V1 remains
default for old assess/assessReason and unchanged LOADER, exact `{0x9004}`;
explicit PROTECTED_AUTO_INHERITED_SNAPSHOT_V1 admits `{0x9004,0x9404}`. This is
acceptance-language EXPANSION, not equivalence correction. Single parser changes
only control membership for candidate; reserved/group/SACL, later owner/layout/
extent/ACE flags0/SID/masks/generic file0x40/trust sets unchanged. New-profile null
throws NPE/no fallback, existing invalid-caller ordering preserved. Independent
STRICT diff review supports old behavior, not merely same-parser test agreement.

Runner enum option omitted=>STRICT, empty/unknown rejects before process; env
validates before NativeApi. Requested profile=facts=runtime=report enforced.
No automatic host/readback/reason/retry selection or candidate loader switch.
productionProfileChanged=false means DEFAULT/loader unchanged, **not all production
source unchanged**. Prior f1e2172 STRICT BLOCKED_PROFILE remains original history,
not retroassessed. Candidate **NATIVE_NOT_RUN**, deployment NOT accepted; native
attempt requires new committed-clean exact HEAD/fresh identity/separate parent
authorization. Source/mock acceptance grants no execution permission.

Archive `Temp/opencode/java-assessment-profile-20261025-064451`: Python20 primary+
4 subtests/24 XML results,0 failures/errors/skips,wall2.0918738s; Java focused3
suites/29 tests,0 failures/errors/skips,31.0809286s; full64 suites/513 tests,
0 failures/errors/12 skips,39.2511941s,exit0/actual build/reobf. Twelve skips=
3 mechanism native+9 old, not native/network PASS. Exhaustive65536 control values
per profile on valid synthetic fixture accepts exact declared sets; unsafe ACE/
principal/rights/owner/reserved/group/layout negatives reject, inputs immutable.
Not proof of all descriptors/policy combinations. Verified5/5 source,160/160
inventory,96/96 artifacts. Final-five manifest
`DEB41CABB6C866B49DE25E1CA188E5994AD4320F141AAAFC54C3A705DE821B35`;
predicate audit `F7EC1EE03B322E99A0C915CFD512CF1E44ADB1553BF3C0F31E2B63783C8DB572`;
aggregate `415526082770F242D7CCAB703800096180C7CA4F8FD4FDFC88C2D53B824AB7EE`.

| Reviewed file | SHA-256 |
|---|---|
| WindowsDeploymentCustody.java | `1B291569A2F4F318C988721FFDC83FB36F8A417389EFF084A8BDFBC7948594BA` |
| WindowsDeploymentCustodyTest.java | `162CC67E986A468C885F0ACE8F0BC4B16F60B0D80F180D525A1AF7DDBD5D4C3C` |
| WindowsDeploymentMechanismNativeTest.java | `89793FB34613CBD8DE49EC34C8F3D036573B6F310A9DEA385BBFB428EFAE9B19` |
| verify_java_custody_native_smoke.py | `ADE4199DD63F9981E2EBEF71FEC5ACF81A5C0B943C55A6202DA16CA595B54085` |
| test_java_custody_native_smoke.py | `B30AC693D0EB2EE45B27847DD3D89FBAE69DCAFD6ADC3317E89DA53D80077ABA` |

All historical verdicts preserved; Java custody unvalidated/dedicatedForge
NOT_ESTABLISHED/epoch host unsupported+UNAVAILABLE/four UNAVAILABLE/complete=false/
runtime Mixin/G3/binding READY unchanged. Upstream6fe9e9dd local-only untouched,
old network pins stale. No native/production activation or future commit claim.

## 18. Explicit candidate actual native attempt: BLOCKED_PROFILE preserved

Parent accepts independently reviewed evidence verdict **BLOCKED_PROFILE, NOT
PASS** for ONE authorized LOCAL NON-NETWORK run at clean pre/post
`dd6e9176bcd7522d347bd403a9e63576f4beb2df`. Explicit selection
PROTECTED_AUTO_INHERITED_SNAPSHOT_V1 matched requested/runtime/descriptor/report
identities. Runner exit2/wall33.0218868s, fresh3JUnit0 failures/errors/skips,
seven Gradle tasks SUCCESS, not aggregate PASS. heldIdentityAndRead OBSERVED0.228s;
descriptorRepresentation DESCRIPTOR_PROFILE_UNSUPPORTED0.035s;
compatibleReadSharing OBSERVED0.007s.

Actual facts: assessment ACL_EXTENT, descriptorBytes1024/revision1/control37892
(`0x9404`). Candidate control and earlier checks were not rejected **by source
order**, not independently measured field validation. ACL_EXTENT may denote DACL
declared range, length<8, end-allocation equality or final ACE-cursor equality;
exact ACL fields/reject branch are NOT_RETAINED. Do not infer padding as cause or
that all ACE checks passed. Original readback UNMODIFIED; no normalization/retry/
API/parser change; STRICT loader default unchanged. Prior STRICT f1e2172 CONTROL
and older protected reason-unknown results stay distinct, never retroassessed.
Parent preserves block, no further native/policy relaxation or new execution authority.

Archives `Temp/opencode/java-candidate-native-run-20261025-065710` and
`java-candidate-native-preflight-20261025-065710`: report
`6D7CFD364FAC2C68443BDA287A9DF20819C103C4CADEB7013FD49DC1DBAC5FA8`;
XML `D39F2107E7EC6ACEC9E1B329D0CB19C7496151E5B93F36717F7AF9F5712ABA37`;
runner aggregate `AF38596590F622B305D02D2B240E4E44FFEFC413CC50D7C0327E8AB9F8687AA8`;
combined `3FBAB3805CE2E16B7056DC2F90564723379E4E5037A10D0DE80608A65380166A`.
Independent11/11 declared sources,9/9 runner artifacts,26/26 combined identities,
5 executed classes and2 approved JNA JARs matched. Three cleanup receipts RELEASED/
retained0/unknown=false; dummy raw deleted/evidence directories retained, not OS
quiescence. Root28212 assigned/root-tree confirmed/cleanupUnknown=false/
forceKilled=false/attempts0/log2283bytes; active-count/reader-join enforced in code,
not serialized detailed audit. Actual JDK17.0.20.1+1/Windows11 version10.0/amd64/
JVM64/pointer8; full build NOT_RETAINED/Structure.size NOT_EXPOSED/source-width
constants separate. Core JNA USED5.12.1/platform AVAILABLE only. READ sharing only,
not write-denial proof. JAVA_CUSTODY_NOT_VALIDATED/dedicatedForge NOT_ESTABLISHED.
No actual deployment/credential/full trusted-loader/network/game/startup/ancestor
ACL change. Source/mock candidate513 Java/12 skips and20+4 Python remains distinct
history, not native profile acceptance. Four UNAVAILABLE/complete=false/epoch host
unsupported+UNAVAILABLE/Java custody/runtime Mixin/G3/binding READY unchanged;
upstream6fe9e9dd local-only/network pins stale untouched. No future hash guessed.

## 19. Bounded-layout SOURCE/MOCK acceptance, not native compatibility

Parent accepts ora18 scoped PASS-with-residuals for the five-file explicit bounded
layout slice; full contract/hash table in [layout decision §7](java-bounded-descriptor-layout.md).
Structural language EXPANSION, not equivalence correction; old STRICT/profiled
tight behavior/context/reason order preserved, loader STRICT+tight unchanged.
Bounded unsigned/aligned/nonoverlap owner/DACL and exact AceCount/body/SID-in-ACE
retain every authorization check. Nonzero gaps/tail/ACL-unused capacity uninterpreted,
not authority; full copied bytes Arrays.equals unchanged, no normalization.
Explicit profile/layout IDs default tight/requested=facts=runtime=report, no automatic
host/error/readback fallback/retry. Native NOT RUN, actual custody/classloader/ABI
not accepted; dd6e917 ACL_EXTENT cause still unknown, never retroassessed.
Archive `Temp/opencode/java-bounded-layout-20261025-071441`:Python21+4subtests/25XML,
0fail/error/skip wall2.0741217s;focusedJava3suites31tests0fail/error/skip30.5763837s;
full64suites515tests0fail/error12skips38.4253685s exit0buildreobf;12=3native+9old
NOTnative/networkPASS.5/5source160/160inventory96/96artifacts;finalfive
`5ACEC41A60F024E01A46F1C0E986827B595B667E9151E5E5636BF9C01A9BBA54`,audit
`7465C93AE4066DB651773644309BD57DC878E8B396AB31923A6A1F30AFF06FB1`,aggregate
`BBE057FE53EF45C36E7825EAC5E789CDFED1B015D6E196017D7B8E70FA9C869D`.
Finite corpus/audit not formal all-bytes proof; prior513/history intact. Reviewed
bytes pending commit/no futurehash. Future native separateauth/cleanexactHEAD/freshidentity.
FourUNAVAILABLE completefalse epochhostunsupportedUNAVAILABLE Java custody/dedicatedForge/
runtimeMixin/G3/bindingREADY/upstream6fe9e9ddlocalonly/stalepins unchanged.

## 20. Selected bounded native mechanism: PASS-LIMITED, not full custody

Parent accepts oracle scoped PASS-LIMITED-with-residuals for ONE LOCAL NON-NETWORK
run at clean pre/post15bdc104df69ecf83b3cfd8d7cf079fc3dab1820, explicit
PROTECTED_AUTO_INHERITED_SNAPSHOT_V1+BOUNDED_SELF_RELATIVE_LAYOUT_V1 requested=
runtime=facts=report. Runnerexit0/MECHANISM_SMOKE_PASS/wall32.7074153s/fresh3JUnit
0fail/error/skip/Gradle7tasksSUCCESS. heldIdentityAndReadOBSERVED.294s,
descriptorRepresentationPROTECTED_DUMMY_DESCRIPTOR_ACCEPTED.034s,compatibleReadSharingOBSERVED.007s.
ActualACCEPTED descriptorBytes1024 revision1 control0x9404 originalreadbackUNMODIFIED/
testOwnerPolicyFIXTURE_ONLY; source executes all DECLARED ACE checks, raw fullDS not
archived so independent per-ACE replay unavailable. Not full five-file loader/
ancestor/deployment-policy/allhosts/dedicatedForge proof. LoaderSTRICTtight unchanged,
productionProfileChanged=false DEFAULTONLY, not all historicalsource unchanged.
Historical strict/tightblocks notretroassessed/no provenpaddingcause; READsharingONLY
notwrite-delete denial. NATIVE_NOT_RUN superseded ONLYexactselectedmechanism scope.
Evidence details [layout decision §8](java-bounded-descriptor-layout.md):
`Temp/opencode/java-bounded-native-run-20261025-072800` + `java-bounded-native-preflight-20261025-072800`;
report `441E8FF27EF49267E47DD21549366C28FD36FBF272A5B6979C6704B34A26A7CF`,XML
`91EB7F1183CB1ACA29777C1631921B726BAD09319371C684585D004DE98B456F`,runneraggregate
`251B4F423D487E4859E84EA6A68F0A6049AA973CE62DC9F5C356E36C15B2B9C8`,combined
`9B7296E967322C09CA99BB28AB23C4ED3ED498FD607DFCC06419A776D6285632`.
11/11source9/9runner26/26combined5executedclasses2JNA verified. All3RELEASED retained0
unknownfalse/rawdummydeleted/evidencedirsretained notOSquiescence/root8424assigned
root-treeconfirmed cleanupUnknownfalseforceKilledfalseattempts0. Readerjoin/activecount
sourceconditions notserializedmetrics. Adoptium17.0.20.1+1 Windows11version10.0amd64/
JVM64ptr8/fullbuildNOT_RETAINED/Structure.sizeNOT_EXPOSED/sourcewidthconstantsdistinct;
JNAcore5.12.1USED/platformAVAILABLE_RESOURCEONLY. JavaCustodyNOT_VALIDATED/
dedicatedForgeNOT_ESTABLISHED/fourUNAVAILABLE completefalse epochhostunsupportedUNAVAILABLE/
runtimeMixin/G3/bindingREADY/upstream6fe9e9ddlocalonly/stalepins unchanged.
Prior515Java/12skips21+4Python separatehistory, no newfullbuild/executionauth/futurehash.

## 21. Explicit loader assessment propagation — staged DESIGN accepted, source pending

Parent accepts independent momus staged DESIGN **PASS_WITH_RESIDUALS** and
separately authorized the four-file implementation + MOCK stage, now running.
Parent now accepts ora18 implementation SOURCE/MOCK scoped PASS-with-residuals
(§22), not native/deployment approval. This disjoint docs lane
asserts no global clean/source-stability claim. This is an explicit
loader acceptance-language extension, not equivalence correction, default-policy
change or native/deployment approval. Preserve all accepted source/mock and
limited native histories; no new implementation count or run is asserted.

### Actual source seam and proposed four-file scope

Inspected `DeploymentCredentialLoader.java:17-23` defines `Custody` (file/validate)
and **NativePort**, not a type named CustodyPort. The proposed revision applies to
the actual `NativePort.acquire` signature; inventing a parallel CustodyPort would
be unnecessary framework expansion. `:277-286` shows the port is loader constructor
state and the current load takes `(policy, receipt, slot)`; it is not a static
load with an extra port argument. `:241-249` calls the private-success value
**Provisioning**, not PrivateProvisioning. Preserve those actual names.

The authorized scope is exactly four existing source/test files under transport:
DeploymentCredentialLoader.java, WindowsDeploymentCustody.java and their paired
tests. Connector core, manifest, wire, Context, build, mechanism harness and native
bindings stay frozen. No startup caller or new native fixture belongs to this lane.

Loader-nested static record, package-private `CustodyAssessment(profile, layout)`, uses
existing AssessmentProfile/AssessmentLayout enum types, both non-null, with a
fixed immutable DEFAULT = STRICT_SNAPSHOT_V1 + TIGHT_PACKED_V1. No fifth framework
file. Enums are immutable; no host-derived backend constructor
selection or mutable profile field.

Keep instance `void load(policy, receipt, slot)` delegating DEFAULT while preserving
its existing result/caller-error behavior. Add instance
`void load(policy, assessment, receipt, slot)`. ONLY the new explicit overload
checks non-null assessment FIRST, then old receipt-null/slot-null checks, receipt
claim and policy-null validation INSIDE the cleanup envelope. Null assessment
throws NPE before ports, receipt stays FRESH/slot unchanged. Legacy entry delegates
DEFAULT with unchanged caller-error ordering; closed-slot late-publication failure
remains unchanged. No eager slot-availability check introduced by refactoring.

Consciously revise package-local `NativePort.acquire` to
`acquire(policy, assessment, receipt)`; update actual backend and future fakes in
the four-file review, no reflection/old-signature discovery/automatic fallback.
Public wire and outside-package APIs do not change. The package-local source
compatibility amendment must be explicitly reviewed, not mislabeled no API change.

### One final selection, uniform custody and byte continuity

`WindowsDeploymentCustody.java:35-38` currently invokes factory then Snapshot;
`:65-88,99-139,141-155` acquires every ancestor/root/five file and repeatedly
calls strict `assess` from inspect. Authorized design has Snapshot store one **final**
CustodyAssessment passed from load. Use exactly that selection for EVERY ancestor,
root and all five file descriptors during acquisition, initial validate, read
rechecks and final validate. No root/credential-only shortcut. A mock port must
not be treated as independent proof it honored this contract; actual backend
low-level fake tests are required. Backend null assessment rejects before factory.

Legacy/default loader remains STRICT+tight; no candidate activation hidden in
backend constructors, host/readback/reason inference, automatic fallback or retry.
Complete copied-descriptor `Arrays.equals` at `:154` stays unchanged even when
both old/new bytes individually assess accepted. Free-space interpretation for
assessment is not semantic-equality normalization: a harmless-looking tail change
can still fail continuity. Do not trim, reorder, reconstruct or convert readback.
All external expected owner/SID/root identity/readers+three mutator sets, paths,
role/IDs/config/artifact hashes/raw32 and error/resource budgets remain unchanged.
ALL native resources must be confirmed released BEFORE publication. UNKNOWN keeps
strong receipt ownership, no READY, best-effort owned-buffer wipe; first-fatal/
close-once/handoff retention remain unchanged.

### Private provisioning binds the selection

Extend actual private-constructor Provisioning with final CustodyAssessment beside
verified manifest/key ID/secret owner. A package-private immutable audit accessor
exposes only that typed selection, never raw PSK, native handles or a public
verified-result factory. Only successful loader construction binds it. Claim
cannot alter selection or forge a verified result; public input assessment alone
is not provenance. The audit records requested selection under the trusted-port
contract, not independent proof against an arbitrary port. HandoffSlot claim/ack/
close and ownership races stay unchanged.
The snapshot records which local policy produced it, not deployment approval,
socket authentication or binding READY. Caller/assembly cannot convert this into
new authority or automatically start a connector.

Actual root/SID deployment policy is still externally missing. Integrating selection
does not promise a positive native ancestry result: the host may remain UNSUPPORTED.
No auto ACL repair, alternate-path retry, default change or capability boolean.

### Future source/mock proof and independent gates

Required finite MOCK proofs (accepted recorded evidence in §22) use ACTUAL loader + ACTUAL Windows
backend with only low-level Api fake, not a replica parser/counter. Default rejects
otherwise valid9404 and padded descriptors SEPARATELY; explicit control candidate+bounded layout
loads all five files using real manifest/digests and private handoff. Observe
candidate/bounded-only descriptors at EACH ancestor/root/fixed object and repeated
inspection demonstrate uniform propagation, not just a recorded acquire argument;
unsafe owner/
principal/credential-reader rights at any held object reject. Mutate descriptor
bytes at final check while both separately assess accepted: Arrays.equals still
rejects. Test null-before-factory/acquire/claim, UNKNOWN no publication/strong refs,
wrong role/hash and31/33 credential rejection, immutable selection/private success
constructor and no old-signature fallback. Existing control exhaustive tests remain
regressions, not newly claimed coverage or a reason to alter frozen test harness.

Source/mock only: no new native fixture/execution/startup caller. A later full
native fixture would need a separate plan and parent authorization, dummy inputs
only with read-only existing-ancestry prerequisite. Unsupported ancestry BLOCK/SKIP
is not PASS; never modify existing ancestor ACLs or retry alternate roots to force
success. The limited native `15bdc104` run covered one selected protected-descriptor
case, not full loader custody or dedicated Forge. Historical strict blocks and
dd6e917 unknown rejection-branch facts are not retroassessed.

Design review fixes the nesting/signatures/error order and narrow NativePort
revision above. Completed source/mock evidence has scoped acceptance in §22,
including immutable Provisioning assessment and private-constructor regression.
Default production selection stays STRICT+
tight unless caller explicitly chooses new entry; no startup/Context/manifest/
wire/default switch. No native permission
is implied by deciding those interfaces. Old network pins remain stale; upstream
6fe9e9dd local-only untouched. Epoch host unsupported+UNAVAILABLE/four UNAVAILABLE/
complete=false/Java custody unvalidated/dedicatedForge NOT_ESTABLISHED/runtime Mixin/
G3/binding READY unchanged. No future commit hash or implementation results guessed.

## 22. Explicit loader assessment integration SOURCE/MOCK accepted

Parent accepts ora18 scoped PASS-with-residuals for four-file actual loader+
Windows backend integration, reviewed bytes pending commit/no guessed future hash.
Legacy load STRICT+tight/error order unchanged; explicit null selection rejects
before ports/receipt claim; NativePort signature explicit/no fallback. Snapshot
final assessment applies to EVERY held object/acquire/recheck. Private final
Provisioning assessment records requested selection under trusted-port contract,
not independent proof an arbitrary port honored it. Actual backend low-level
mocks exercise seven held positions (drive root/business root/five files) and
repeat inspection, not all native ancestry depths. Complete copied descriptor
Arrays.equals unchanged: accepted-to-accepted tail mutation still fails continuity.
Secret/receipt/handoff ownership and ALL confirmed native cleanup before publish
remain unchanged. No startup/core/Context/manifest/wire/native-binding changes.

Archive `Temp/opencode/java-loader-selection-20261025-074150`: focused4 suites/
67 tests,0 failures/errors/skips,46.1027864s; full64 suites/521 tests,0 failures/
errors/12 skips,62.3667772s,exit0/actual build/reobf. Twelve skips=3 mechanism
native+9 old, not native/network PASS. Verified4/4 source,160/160 inventory,
90/90 artifacts. Final-four manifest
`AAFED6CB29E5ADE3DA5769914D8FCA191D7160DDF6637934D49C4E9C53C93F6C`;
audit `415D7FFE9A5C557C020A1AC3A6282D35859718CD285E373B8C37DAA3E2AEBD43`;
aggregate `9251A101E572B76D970BE1F68E87B7B4368F71004BC969B496D3693D9369EDDF`.

| Reviewed file | SHA-256 |
|---|---|
| DeploymentCredentialLoader.java | `B225D136EEA000C451647FDA9C05F642B807065E739E4BCAD9B339F5A7DBCFA6` |
| WindowsDeploymentCustody.java | `E07893504BBFBD41D6433CC27C2E993DE213B854CE6596CE67434B9FC1FB15A5` |
| DeploymentCredentialLoaderTest.java | `67A4E42A6ED3D33E0DD42006FC703D670C01FA35EDBFE323C8E304AE711C76EC` |
| WindowsDeploymentCustodyTest.java | `4FA17326089F22CE3C5642D4E18ABA2A092BEB143E01980A5F312FAE1B7640E7` |

Finite fixture proof uses actual manifest/digests/raw32, unsafe owner/reader and
role/hash/31–33 negatives, not all policy combinations/native-host proof. No
formatter/new native/interop claim. Historical15bdc104 limited dummy assessment
is separate, not full loader-native coverage. Old source/model histories remain.
Actual ancestry/root SID reader policy/full-native loader/deployment/dedicated
Forge remain UNVALIDATED; epoch host unsupported+UNAVAILABLE/four UNAVAILABLE/
complete=false/runtime Mixin/G3/binding READY unchanged. Upstream6fe9e9dd local-only/
stale network pins untouched. Future native needs separate bounded plan and parent
authorization; this acceptance grants no execution permission.

## 23. Three-file full-loader harness SOURCE/MOCK accepted; native NOT RUN

Parent accepts ora18 independent scoped PASS-with-residuals for
DeploymentCredentialLoaderNativeTest.java, verify_java_loader_native.py and its
paired Python test. URL structural comparison accepts equivalent authority-free
Java/Python local-file spellings while retaining exact 22 declared class/build/JAR
provenance. This bounded class receipt is not whole-JVM attestation. Regressions
exercise actual Owner.bind/claim/copy/final-error helpers; process wait return or
throw observes XML once after cleanup and preserves primary failure. Evidence
write failure is sticky; atomic no-replace publication writes final report last.

Final archive `java-loader-url-20261025-140534`: fresh focused Python14 tests,
0 failures/errors/skips, pytest1.824s/wall4.5701316s/exit0; independently verified
3/3 final files,160/160 before+160/160 final frozen identities and12/12 artifacts.
Final-three manifest `DC01F1C106E14F86E79597C0169709B4A79AE4EA0F9DF40B9CD9AB993D8B7A61`;
artifacts manifest `163FFA21E67B0F1020490E51778FC74C27E9CA042EBAE4A6728C6EA7239401D9`.
Unchanged Java evidence is reused from `java-loader-path-regressions-20261025-135831`:
14 Java safety tests/0 failures/errors/skips; full66 suites/536 tests/0 failures/
errors/13 skips,wall38.0571901s/exit0/build/reobf. Final-three manifest
`FCAF89BF042416F05765F3F949BFB8C211AD8262D833F8B3122507140A8BC39C`;
artifacts manifest `305112629B435C085895BD78AE4D9B78F31BEA48A13E7F3E439CF53C3D9381FB`.
This is not a simultaneous fresh full run after the Python URL fix; reused Java
evidence is valid for unchanged Java, not new Python proof. Thirteen skips are
full-loader native+12 existing, not native PASS. Historical failures remain history.

**FULL_LOADER_NATIVE_NOT_RUN.** Fixture intentionally does not delete its tree:
fixtureCleanup=RETAINED_NOT_DELETED makes aggregate FAILED/exit2 even on loader
semantic success. Known retained tree is distinct from resource UNKNOWN. Creation
uses immediate JDK NOFOLLOW/non-null fileKey then held native bind under TRUSTED
EXCLUSIVE workspace, not atomic creation/anti-ABA. UNKNOWN retains strong owner,
no overwrite/retry/delete; no connector/ACK. No production custody/deployment/native
ABI/ancestry compatibility/dedicated Forge acceptance. Future native requires
reviewed committed-clean inputs and SEPARATE bounded parent authorization; none
is granted here. Four UNAVAILABLE/complete=false/epoch host unsupported+UNAVAILABLE/
Java custody/runtime Mixin/G3/binding READY unchanged; upstream6fe9e9dd local-only
untouched and stale network pins not refreshed.

| Accepted harness file | SHA-256 |
|---|---|
| DeploymentCredentialLoaderNativeTest.java | `B8AFC153C346D5B534127BB6E6C204BAF0D261A57AED432AB717500ECFFF3E10` |
| verify_java_loader_native.py | `8069D37A5455F31F4ABCF1BD6A00DD777623894EB527D8178F6CA213BA2F9B2E` |
| test_java_loader_native.py | `A028F47683F97AE5301BF152B73585AC8A0B3A34E3D59E697D864E16EB00B0CD` |
