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
