# Java bounded self-relative descriptor layout — proposal

**Staged DESIGN scoped PASS_WITH_RESIDUALS — 2026-10-25; parent accepted after
independent momus review, conditional on the final matrix below before writer.**
Parent now accepts ora18 SOURCE/MOCK scoped PASS-with-residuals for the five-file
implementation recorded in §7; native/deployment NOT accepted, NATIVE NOT RUN.
This is acceptance-language EXPANSION, not
equivalence correction or default change. Documentation only.
Baseline Sandbox `a3b714a056df100e4fda52e5285d1688d04743cc`; existing source is
frozen. No future commit hash or new runtime result is asserted.

## 1. Motivation without retroactive diagnosis

Parent selected consideration of a separate local versioned layout
`BOUNDED_SELF_RELATIVE_LAYOUT_V1`, an explicit **acceptance-language EXPANSION**,
not equivalence correction or replacement of existing STRICT/control-candidate
profiles. Current tight packing is an application restriction, not a statement
that every other Win32 self-relative representation is invalid.

The actual candidate attempt at `dd6e917` remains **BLOCKED_PROFILE** with first
reason ACL_EXTENT, copied descriptor length1024/revision1/control0x9404. Exact
ACL fields/reject branch were NOT_RETAINED. Padding is not established as cause;
all historical blocks remain unchanged, not retroassessed under this proposal.
See [custody decision §§16–18](java-deployment-credential-custody.md).

Actual inspected source under `bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/transport/`:

- `WindowsDeploymentCustody.java:185-207`: AssessmentProfile controls exact
  control membership; old assess/assessReason defaults STRICT; profiled overload
  requires non-null profile and catches only private Rejection.
- `:211-226`: envelope<=4096, descriptor revision/control/reserved/group/SACL,
  SID/owner policy, ACL header/range/revision/count; ownerOffset20, DACL immediately
  after owner, ACL end equals copied allocation end are current restrictions.
- `:228-244`: EXACT declared AceCount, standard ALLOW/zero flags, SID grammar,
  generic mapping/file0x40 and category policies; final cursor==ACLend today.
- `:253-260`: bounded SID helper/current range checks. New mode needs interval-
  specific SID bounds, not merely a check against all copied bytes.

This docs lane changes no source; accepted implementation evidence is in §7.

## 2. Separate local control and layout dimensions

Proposed package-private `AssessmentLayout` enum:

- `TIGHT_PACKED_V1`: exact current layout behavior.
- `BOUNDED_SELF_RELATIVE_LAYOUT_V1`: the bounded intervals below.

Keep existing AssessmentProfile values independently:
STRICT_SNAPSHOT_V1 controls `{0x9004}`;
PROTECTED_AUTO_INHERITED_SNAPSHOT_V1 controls `{0x9004,0x9404}`.
Do not multiply concatenated profile IDs when two explicit typed dimensions suffice.

Frozen proposed new six-argument signature:

```java
static AssessmentReason assessReason(byte[] raw,
    DeploymentCredentialLoader.TrustedDeploymentPolicy policy,
    boolean directory, int owner, AssessmentProfile profile, AssessmentLayout layout);
```

Existing overloads delegate `TIGHT_PACKED_V1`, preserving current profile defaults,
exact legacy rejection order/results and caller-error behavior. ONLY the new
overload checks `Objects.requireNonNull(profile)` then
`Objects.requireNonNull(layout)` before parse, without fallback. No arbitrary
context prevalidation may change old caller behavior. Loader remains STRICT+tight;
no manifest/wire/Context/startup selection change. Explicit trusted local selection
only, never host/readback/failure-based auto-selection, normalization or retry.
These contracts now have scoped source/mock acceptance, not native approval.
Accepted five-file scope is exactly WindowsDeploymentCustody.java, its paired test,
WindowsDeploymentMechanismNativeTest.java, verify_java_custody_native_smoke.py and
its paired Python test. Exact allowlisted runner layout option defaults tight when
omitted; malformed values reject before Native initialization. No loader/manifest/
Context/wire/native-binding change.

Future evidence requires both profileId and layoutId plus source/executed-class
identity; requested/emitted/runtime/report values must agree, with status/reason
consistent. Old evidence retains its original schema/tight meaning; no relabeling
of a historical block or silent new default. No new secret-bearing receipt fields.

## 3. Bounded-layout grammar and rejection sequence

Input is the **confirmed copied native allocation**, at most4096 bytes, header
exactly20 bytes. Allocation provenance/complete extent must already be established
before native dereference. Logical descriptor length, LocalSize allocation bound,
AclSize capacity and ACE bytes in use are distinct quantities. Do not trim bytes,
reorder components, rebuild a descriptor, zero unknown tails or add
GetSecurityDescriptorLength/IsValid pointer probes to force acceptance.

Exact bounded-mode first-failure matrix (legacy tight branch remains exact):

1. Existing envelope/revision/selected-control combined predicate unchanged,
   including reserved byte0/groupOffset0/SACLoffset0 and existing reasons.
2. Unsigned owner offset: complete8-byte SID header after20-byte descriptor header
   and inside allocation; failure `LAYOUT_BOUNDS`, before reading SID contents.
3. Owner offset DWORD alignment; failure `LAYOUT_ALIGNMENT`.
4. Owner SID revision/length/full allocation extent; failure `SID_ENCODING`.
5. Unsigned DACL offset: complete8-byte ACL header after descriptor header and
   inside allocation; failure `LAYOUT_BOUNDS`.
6. DACL DWORD alignment; failure `LAYOUT_ALIGNMENT`.
7. Existing ACL revision/reserved checks; failure `ACL_REVISION`.
8. AclSize>=8/full ACL interval inside allocation; failure `ACL_EXTENT`.
9. AceCount<=128; failure `ACE_COUNT`.
10. Established owner-SID/ACL half-open intervals must not overlap; failure
    `LAYOUT_OVERLAP`. Header coverage was already rejected in steps2/5.
11. Original owner security-mutator/expected-owner policy; failure `OWNER_POLICY`.
12. EACH declared ACE: bound header first, read size, check start/size DWORD
    alignment, then existing type/flags/min-size/body-range grammar. Header/body
    bounds or grammar fail `ACE_GRAMMAR`; alignment fails `LAYOUT_ALIGNMENT`.
13. Principal SID grammar and full SID extent INSIDE THAT ACE fail `SID_ENCODING`,
    even when bytes would fit the outer allocation. No read precedes its bounds.
14. Existing principal-size/substitution/mask/content/security/namespace/reader
    checks retain order/reasons; never skip any declared ACE semantic check.
15. Final cursor<=ACLend; failure `ACL_EXTENT`.

Offsets are unsigned DWORD values in a wider representation. Prove against the
<=4096 allocation BEFORE narrowing to int; endpoints only after overflow-safe
subtraction proofs `offset <= bound && size <= bound-offset`. No native pointer
probe, wrapped addition, reads before bounds or implicit negative-index coercion.
Owner/DACL may swap or have gaps/allocator tails. Bounded mode deliberately allows
NONZERO gaps/trailing allocator capacity/unused ACL capacity, UNINTERPRETED: those
bytes are not declared components/ACEs and carry no authorization. No trimming,
zeroing, reordering/reconstruction/native conversion. This is an explicit policy
assumption, not proof that arbitrary ignored bytes are universally harmless.

Bounded order intentionally differs from old tight owner-policy-before-DACL
ordering. OLD tight predicates/helpers/reason precedence stay unchanged; sharing
ACE semantic code must not route tight through new bounds/order. New stable
sanitized categories are `LAYOUT_BOUNDS`, `LAYOUT_ALIGNMENT`, `LAYOUT_OVERLAP`;
no SID/pointer/raw descriptor/free exception details. Multi-invalid tests freeze
this precedence, not a unique-root-cause promise.
First failure remains a combined-predicate classification, not unique root cause.
No Runtime/Error/Unavailable swallowing or public authority factory.

## 4. Authorization, budgets and native ownership do not expand

Control profile still requires protected, non-null DACL under exact declared
control membership. Reserved/group/SACL remain zero; explicit standard ALLOW
only, flags EXACTLY0 (including rejecting inherited ACE flags). Canonical SID,
owner security-mutator/expected-owner, content/security/namespace/credential-reader
sets and cross-category checks unchanged; no added principals or implicit
Administrator/SYSTEM trust. Generic-right expansion/mask rejection and regular-
file0x40 unsupported remain. AceCount<=128, copied bytes<=4096, all existing
resource/secret budgets, snapshot semantics and cleanup ownership stay unchanged.
Layout expansion must not bypass a single declared ACE's semantic policy checks.
IMPORTANT integration coupling: snapshot validate currently uses Arrays.equals on
the complete copied descriptor. KEEP that unchanged. Ignoring free bytes for
assessment does not establish semantic-descriptor equality or normalize bytes.
Changing unused bytes can still make a future loader fail closed; that is a
separate integration residual. Loader does NOT select bounded mode at this stage.

Native output-storage preownership/LocalSize provenance, opaque ambiguous-output
UNKNOWN, whole-operation borrowing, strong retained ownership and close-once/no
retry are not weakened. This layout is about parsing already confirmed copied
bytes, not new native APIs, allocation introspection or complete Windows validity.
No total-heap/native ABI/resource-quiescence/deployment custody proof follows.

## 5. Supplied official research basis, not new observation

Microsoft current-document research supplied for **2026-10-25** supports the
representation distinctions; no new fetch or runtime experiment occurred here:

- [LocalSize](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-localsize):
  allocation may exceed requested bytes, not logical security-descriptor length.
- [GetSecurityDescriptorLength](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-getsecuritydescriptorlength):
  assumes a valid security descriptor, not an arbitrary-pointer memory validator;
  cited for semantics only, not a proposed native call.
- [Self-relative security descriptors](https://learn.microsoft.com/en-us/windows/win32/secauthz/absolute-and-self-relative-security-descriptors):
  components may be in any order; offsets/validity still require bounded checks.
- [ACL](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-acl):
  AclSize includes unused ACL space, distinct from declared ACE usage.
- [GetAclInformation](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-getaclinformation):
  usage information is not a memory validator or permission to dereference unknown outputs.

These facts justify considering a versioned app-language expansion, not evidence
that dd6e917 failed because of padding or that a candidate native run would pass.
Missing exact native ACL fields remain NOT_RETAINED.

## 6. Pure-fixture contract and evidence boundary

This docs lane reran no tests. Accepted pure-fixture checks cover four dimensions:
memory bounds, structural grammar, packing expansion and authorization. Preserve all legacy default
vectors and exhaustive control-profile tests separately. Known accepted tight
fixtures also accept bounded mode. Isolate positive owner/DACL swap, gaps,
trailing allocation and unused ACL capacity cases (including nonzero ignored
space if independently accepted). Reject header coverage/overlap/overflow/
misalignment/out-of-bounds/huge unsigned offsets/ACE past ACL/SID past ACE/invalid
count/final cursor and multi-invalid first-reason cases. Mutate unsafe
SID/ACE flags/rights/credential-reader/owner on EACH new positive and reject.
Raw bytes/policy immutable, no trimming/auto-selection/fallback; null new enums
error explicitly. Test first-rejection order and profile/layout receipt identity.
No counter/parser replica in place of actual assessment.

Parent/momus selected ignored nonzero gap/free/tail semantics, exact matrix and
orthogonal API/selector/evidence contract above. Both profileId/layoutId must match
requested=facts=runtime=report in new schema; old XML stays historical. Actual
production parser tests, not a copied test baseline, plus source audit are required;
neither exhaustive control fixtures nor audit proves all possible byte inputs.
Pure fixtures suffice to decide a coherent policy; no native attempt is needed
for that decision. Source/mock acceptance is limited to §7; any
later native execution needs separate parent authorization, new clean commit and
fresh source/class/JNA identities. Additional native blocks remain individually
reviewed, never blind successive loosening.

This document changes no implementation. Epoch host/profile unsupported/epoch
UNAVAILABLE/four UNAVAILABLE/complete=false/Java filesystem custody UNVERIFIED/
dedicatedForge NOT_ESTABLISHED/runtime Mixin/G3/binding READY remain unchanged.
Upstream6fe9e9dd local-only/network pins stale untouched. No implementation,
native/deployment/activation approval or future commit hash is asserted.

## 7. Accepted bounded-layout SOURCE/MOCK, native NOT RUN

Parent accepts ora18 scoped PASS-with-residuals for explicit
BOUNDED_SELF_RELATIVE_LAYOUT_V1, structural-language EXPANSION, not equivalence
correction. Reviewed bytes pending commit/no guessed future hash. Baseline STRICT/
profiled TIGHT_PACKED_V1 behavior, context errors/reason precedence preserved by
independent diff review; LOADER remains STRICT+tight. New branch uses bounded
unsigned/aligned/nonoverlapping owner/DACL intervals, EXACT AceCount and body/SID
inside each ACE, retaining all owner/SID/mask/flags0/reader+three-mutator checks.
Nonzero gaps/tails/ACL-unused capacity are uninterpreted, not authorization;
complete copied-descriptor Arrays.equals continuity stays unchanged, no normalization.
Explicit profile/layout selectors default tight and match requested=facts=runtime=
report; no host/error/readback fallback/retry. New native NOT RUN; dd6e917 ACL_EXTENT
does not establish padding cause or permit retroassessment. No ABI/classloader/
actual deployment custody acceptance.

Archive `Temp/opencode/java-bounded-layout-20261025-071441`: Python21 primary+
4 subtests/25 XML results,0 failures/errors/skips,wall2.0741217s; Java focused3
suites/31 tests,0 failures/errors/skips,30.5763837s; full64 suites/515 tests,
0 failures/errors/12 skips,38.4253685s,exit0/build/reobf. Twelve skips=3 mechanism
native+9 old, not native/network PASS. Verified5/5 source,160/160 inventory,96/96
artifacts. Final-five `5ACEC41A60F024E01A46F1C0E986827B595B667E9151E5E5636BF9C01A9BBA54`;
audit `7465C93AE4066DB651773644309BD57DC878E8B396AB31923A6A1F30AFF06FB1`;
aggregate `BBE057FE53EF45C36E7825EAC5E789CDFED1B015D6E196017D7B8E70FA9C869D`.
Finite fixture corpus+source audit is not formal all-bytes proof. Previous513 counts
and every historical source/native verdict remain distinct, not retroactive PASS.

| Reviewed file | SHA-256 |
|---|---|
| WindowsDeploymentCustody.java | `363034F04D623AD2B6E3D42BF6DA429507D0B40F9A071F8EED33C884BB55960C` |
| WindowsDeploymentCustodyTest.java | `58A5EC30362B7F13B129C3325F3A5F1742937BEA6C71916F01FEF9850F9C8CFD` |
| WindowsDeploymentMechanismNativeTest.java | `C7AD56739B3745192B8BC8DFC6B932ECFD8F0E3E0BEB426EA7A6B5958C7BA10B` |
| verify_java_custody_native_smoke.py | `2B29ADFE50739011CC0F9F7E2F29795CD98F8DE3C255B3AFB3AC3B6D844610C0` |
| test_java_custody_native_smoke.py | `0B081BF96F80244A2634B997CA1EE9FE631C016228E42972A30C6DAA1AE1ED29` |

Future native requires separate parent authorization/new committed-clean exact
HEAD/fresh identities, not granted by this acceptance. Four UNAVAILABLE/
complete=false/epoch host unsupported+UNAVAILABLE/Java custody unvalidated/
dedicatedForge NOT_ESTABLISHED/runtime Mixin/G3/binding READY unchanged;
upstream6fe9e9dd local-only/stale network pins untouched.
