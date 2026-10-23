package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ControlDeploymentManifestTest {
  private static final byte[] MOD = bytes(1);
  private static final byte[] POLICY = bytes(2);
  private static final byte[] BINDING = bytes(3);

  @Test
  void bothPeerRolesParseCanonicalTwentyThreeKeyBytesAndHashOriginalInput() throws Exception {
    byte[][] roleArtifacts = {MOD, bytes(4)};
    int roleIndex = 0;
    for (String role : new String[] {"CLIENT_CONTROLLER", "SERVER_AUTHORITY"}) {
      byte[] raw = manifest(role, "peer-1", "hub-1", roleArtifacts[roleIndex++], POLICY, BINDING);
      ControlDeploymentManifest value = ControlDeploymentManifest.parse(raw);
      assertEquals(ControlAuth.Role.valueOf(role), value.peerRole());
      assertEquals("peer-1", value.peerInstallId());
      assertEquals("hub-1", value.hubInstallId());
      assertArrayEquals(raw, value.encode());
      assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(raw), value.configHash());
      assertFalse(value.toString().contains(HexFormat.of().formatHex(MOD)));
    }
    assertFalse(java.util.Arrays.equals(
        ControlDeploymentManifest.parse(manifest("CLIENT_CONTROLLER", "peer-1", "hub-1",
            MOD, POLICY, BINDING)).configHash(),
        ControlDeploymentManifest.parse(manifest("SERVER_AUTHORITY", "peer-1", "hub-1",
            MOD, POLICY, BINDING)).configHash()));
  }

  @Test
  void verifiesTrustedIdentityConfigAndThreeRawArtifactsWithoutAuthorityClaim() throws Exception {
    byte[] raw = manifest("CLIENT_CONTROLLER", "peer-1", "hub-1", MOD, POLICY, BINDING);
    ControlDeploymentManifest value = ControlDeploymentManifest.parse(raw);
    var match = value.verifyArtifacts(ControlAuth.Role.CLIENT_CONTROLLER, "peer-1", "hub-1",
        value.configHash(), MOD, POLICY, BINDING);
    assertArrayEquals(value.configHash(), match.configHash());
    assertEquals("ArtifactMatch[hashes=redacted]", match.toString());
    assertThrows(IllegalArgumentException.class, () -> value.verifyArtifacts(
        ControlAuth.Role.SERVER_AUTHORITY, "peer-1", "hub-1", value.configHash(),
        MOD, POLICY, BINDING));
    assertThrows(IllegalArgumentException.class, () -> value.verifyArtifacts(
        ControlAuth.Role.CLIENT_CONTROLLER, "other", "hub-1", value.configHash(),
        MOD, POLICY, BINDING));
    assertThrows(IllegalArgumentException.class, () -> value.verifyArtifacts(
        ControlAuth.Role.CLIENT_CONTROLLER, "peer-1", "other", value.configHash(),
        MOD, POLICY, BINDING));
    byte[] wrongConfig = value.configHash();
    wrongConfig[0] ^= 1;
    assertThrows(IllegalArgumentException.class, () -> value.verifyArtifacts(
        ControlAuth.Role.CLIENT_CONTROLLER, "peer-1", "hub-1", wrongConfig,
        MOD, POLICY, BINDING));
    for (int target = 0; target < 3; target++) {
      byte[] mod = MOD.clone(), policy = POLICY.clone(), binding = BINDING.clone();
      new byte[][] {mod, policy, binding}[target][0] ^= 1;
      assertThrows(IllegalArgumentException.class, () -> value.verifyArtifacts(
          ControlAuth.Role.CLIENT_CONTROLLER, "peer-1", "hub-1", value.configHash(),
          mod, policy, binding));
    }
  }

  @Test
  void returnedArraysAndConstructorInputsAreDefensive() throws Exception {
    byte[] raw = manifest("CLIENT_CONTROLLER", "peer-1", "hub-1", MOD, POLICY, BINDING);
    byte[] expectedRaw = raw.clone();
    ControlDeploymentManifest value = ControlDeploymentManifest.parse(raw);
    raw[0] = 0;
    assertArrayEquals(expectedRaw, value.encode());
    byte[] hash = value.configHash();
    hash[0] ^= 1;
    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expectedRaw), value.configHash());
    byte[] declared = value.modManifestHash();
    declared[0] ^= 1;
    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(MOD), value.modManifestHash());
  }

  @Test
  void parseAndTrustedInputsSnapshotCallerOwnedArrays() throws Exception {
    byte[] raw = manifest("CLIENT_CONTROLLER", "peer-1", "hub-1", MOD, POLICY, BINDING);
    byte[] expectedRaw = raw.clone();
    ControlDeploymentManifest value = ControlDeploymentManifest.parse(raw);
    raw[0] ^= 1;
    assertArrayEquals(expectedRaw, value.encode());
    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expectedRaw), value.configHash());

    byte[] config = value.configHash();
    byte[] mod = MOD.clone(), policy = POLICY.clone(), binding = BINDING.clone();
    var match = value.verifyArtifacts(ControlAuth.Role.CLIENT_CONTROLLER, "peer-1", "hub-1",
        config, mod, policy, binding);
    config[0] ^= 1;
    mod[0] ^= 1;
    policy[0] ^= 1;
    binding[0] ^= 1;
    assertArrayEquals(value.configHash(), match.configHash());
    assertArrayEquals(sha(MOD), match.modManifestHash());
    assertArrayEquals(sha(POLICY), match.policyConfigHash());
    assertArrayEquals(sha(BINDING), match.bindingManifestHash());
    assertThrows(IllegalArgumentException.class, () -> value.verifyArtifacts(
        ControlAuth.Role.CLIENT_CONTROLLER, "peer-1", "hub-1", null, MOD, POLICY, BINDING));
    assertThrows(IllegalArgumentException.class, () -> value.verifyArtifacts(
        ControlAuth.Role.CLIENT_CONTROLLER, "peer-1", "hub-1", value.configHash(),
        null, POLICY, BINDING));
  }

  @Test
  void frozenAsciiIdGrammarAndBoundariesApplyToManifestAndTrustedIdentity() {
    String maximum = "a" + "A9._:/-".repeat(18) + "A";
    assertEquals(128, maximum.length());
    for (String id : new String[] {"a", maximum}) {
      ControlDeploymentManifest value = ControlDeploymentManifest.parse(
          manifest("CLIENT_CONTROLLER", id, id, MOD, POLICY, BINDING));
      assertEquals(id, value.peerInstallId());
      assertEquals(id, value.hubInstallId());
      assertDoesNotThrow(() -> value.verifyArtifacts(ControlAuth.Role.CLIENT_CONTROLLER,
          id, id, value.configHash(), MOD, POLICY, BINDING));
    }
    for (String invalid : new String[] {"quote\"", "back\\slash", "tab\t", "line\n",
        "control\u001f", "-lead", "_lead", "a".repeat(129)}) {
      assertThrows(IllegalArgumentException.class, () -> ControlDeploymentManifest.parse(
          manifest("CLIENT_CONTROLLER", invalid, "hub-1", MOD, POLICY, BINDING)));
      ControlDeploymentManifest value = ControlDeploymentManifest.parse(
          manifest("CLIENT_CONTROLLER", "peer-1", "hub-1", MOD, POLICY, BINDING));
      assertThrows(IllegalArgumentException.class, () -> value.verifyArtifacts(
          ControlAuth.Role.CLIENT_CONTROLLER, invalid, "hub-1", value.configHash(),
          MOD, POLICY, BINDING));
    }
  }

  @Test
  void rejectsRoleIdsHashesAndEveryFixedValueMutation() {
    byte[] valid = manifest("CLIENT_CONTROLLER", "peer-1", "hub-1", MOD, POLICY, BINDING);
    for (String role : new String[] {"AGENT_HUB", "CLIENT", "client_controller"}) {
      reject(replace(valid, "\"CLIENT_CONTROLLER\"", "\"" + role + "\""));
    }
    reject(replace(valid, "\"peer-1\"", "\"non ascii 雪\""));
    reject(replace(valid, "\"peer-1\"", "\"\""));
    reject(replace(valid, HexFormat.of().formatHex(sha(MOD)), "A".repeat(64)));
    String[] mutations = {
        "luosandbox.minecraft-control-deployment.v2_ALIAS", "luosandbox.control-auth.v1_ALIAS",
        "LUO_CONTROL_POSTAUTH_V2_ALIAS", "LUO_CONTROL_SESSION_KEY_V2_ALIAS",
        "LUO_CONTROL_FRAME_V2_ALIAS"
    };
    for (String mutation : mutations) {
      String original = mutation.substring(0, mutation.length() - 6);
      reject(replace(valid, original, mutation));
    }
    for (String pair : new String[] {"4096:4097", "2048:2049", "128:127", "8:9",
        "5000:5001", "500:501", "2000:2001", "2:3"}) {
      String[] parts = pair.split(":");
      reject(replaceFirstNumber(valid, parts[0], parts[1]));
    }
  }

  @Test
  void mutationOfEachOfTheExactTwentyThreeFieldsIsRejected() {
    byte[] valid = manifest("CLIENT_CONTROLLER", "peer-1", "hub-1", MOD, POLICY, BINDING);
    String mod = HexFormat.of().formatHex(sha(MOD));
    String policy = HexFormat.of().formatHex(sha(POLICY));
    String binding = HexFormat.of().formatHex(sha(BINDING));
    String[][] mutations = {
        {"\"schema\":\"luosandbox.minecraft-control-deployment.v2\"", "\"schema\":\"bad\""},
        {"\"security\":\"luosandbox.control-auth.v1\"", "\"security\":\"bad\""},
        {"\"peerRole\":\"CLIENT_CONTROLLER\"", "\"peerRole\":\"AGENT_HUB\""},
        {"\"peerInstallId\":\"peer-1\"", "\"peerInstallId\":\"\""},
        {"\"hubInstallId\":\"hub-1\"", "\"hubInstallId\":\"\""},
        {"\"protocol\":\"LUO_CONTROL_POSTAUTH_V2\"", "\"protocol\":\"bad\""},
        {"\"postAuthProfile\":\"LUO_CONTROL_POSTAUTH_V2\"", "\"postAuthProfile\":\"bad\""},
        {"\"sessionKdfDomain\":\"LUO_CONTROL_SESSION_KEY_V2\"", "\"sessionKdfDomain\":\"bad\""},
        {"\"frameMacDomain\":\"LUO_CONTROL_FRAME_V2\"", "\"frameMacDomain\":\"bad\""},
        {"\"frameMaxUtf8Bytes\":4096", "\"frameMaxUtf8Bytes\":4097"},
        {"\"payloadMaxRawBytes\":2048", "\"payloadMaxRawBytes\":2049"},
        {"\"asciiIdMaxBytes\":128", "\"asciiIdMaxBytes\":127"},
        {"\"outboundQueueFrames\":8", "\"outboundQueueFrames\":9"},
        {"\"openTimeoutMs\":5000", "\"openTimeoutMs\":5001"},
        {"\"heartbeatIntervalMs\":500", "\"heartbeatIntervalMs\":501"},
        {"\"heartbeatTimeoutMs\":2000", "\"heartbeatTimeoutMs\":2001"},
        {"\"routeCapacity\":2", "\"routeCapacity\":3"},
        {"\"routeTtlMs\":5000", "\"routeTtlMs\":5001"},
        {"\"bindingPendingCapacity\":8", "\"bindingPendingCapacity\":9"},
        {"\"storeOutstandingCapacity\":2", "\"storeOutstandingCapacity\":3"},
        {"\"modManifestSha256\":\"" + mod + "\"", "\"modManifestSha256\":\"" + mod.toUpperCase() + "\""},
        {"\"policyConfigSha256\":\"" + policy + "\"", "\"policyConfigSha256\":\"" + policy.toUpperCase() + "\""},
        {"\"bindingManifestSha256\":\"" + binding + "\"", "\"bindingManifestSha256\":\"" + binding.toUpperCase() + "\""},
    };
    assertEquals(23, mutations.length);
    for (int index = 0; index < mutations.length; index++) {
      String[] mutation = mutations[index];
      byte[] changed = replace(valid, mutation[0], mutation[1]);
      assertFalse(java.util.Arrays.equals(valid, changed), "mutation did not change field " + index);
      assertThrows(IllegalArgumentException.class,
          () -> ControlDeploymentManifest.parse(changed), "accepted field mutation " + index);
    }
  }

  @Test
  void rejectsAllNoncanonicalByteFormsAndSchemaChanges() {
    byte[] valid = manifest("CLIENT_CONTROLLER", "peer-1", "hub-1", MOD, POLICY, BINDING);
    reject(java.util.Arrays.copyOf(valid, valid.length - 1));
    reject(concat(valid, new byte[] {'\n'}));
    reject(replace(valid, "\n", "\r\n"));
    reject(concat(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, valid));
    reject(replace(valid, "{", "{ "));
    reject(replace(valid, "\":", "\" :"));
    reject(replace(valid, "\"security\":", "\"security\":\"duplicate\",\"security\":"));
    reject(replace(valid, "\"security\":", "\"unknown\":\"x\",\"security\":"));
    reject(replace(valid, "\"schema\":\"luosandbox.minecraft-control-deployment.v2\",", ""));
    reject(replace(valid, "\"frameMaxUtf8Bytes\":4096", "\"frameMaxUtf8Bytes\":true"));
    reject(replace(valid, "\"frameMaxUtf8Bytes\":4096", "\"frameMaxUtf8Bytes\":4096.0"));
    reject(swapFirstTwoKeys(valid));
    reject(new byte[] {(byte) 0xff});
    reject(replace(valid, "peer-1", "peer\\u002d1"));
    assertThrows(IllegalArgumentException.class,
        () -> ControlDeploymentManifest.parse(new byte[ControlDeploymentManifest.MAX_MANIFEST_BYTES + 1]));
  }

  private static byte[] manifest(String role, String peer, String hub, byte[] mod,
      byte[] policy, byte[] binding) {
    return ("{\"schema\":\"luosandbox.minecraft-control-deployment.v2\","
        + "\"security\":\"luosandbox.control-auth.v1\",\"peerRole\":\"" + role
        + "\",\"peerInstallId\":\"" + peer + "\",\"hubInstallId\":\"" + hub
        + "\",\"protocol\":\"LUO_CONTROL_POSTAUTH_V2\","
        + "\"postAuthProfile\":\"LUO_CONTROL_POSTAUTH_V2\","
        + "\"sessionKdfDomain\":\"LUO_CONTROL_SESSION_KEY_V2\","
        + "\"frameMacDomain\":\"LUO_CONTROL_FRAME_V2\","
        + "\"frameMaxUtf8Bytes\":4096,\"payloadMaxRawBytes\":2048,"
        + "\"asciiIdMaxBytes\":128,\"outboundQueueFrames\":8,\"openTimeoutMs\":5000,"
        + "\"heartbeatIntervalMs\":500,\"heartbeatTimeoutMs\":2000,"
        + "\"routeCapacity\":2,\"routeTtlMs\":5000,\"bindingPendingCapacity\":8,"
        + "\"storeOutstandingCapacity\":2,\"modManifestSha256\":\""
        + HexFormat.of().formatHex(sha(mod)) + "\",\"policyConfigSha256\":\""
        + HexFormat.of().formatHex(sha(policy)) + "\",\"bindingManifestSha256\":\""
        + HexFormat.of().formatHex(sha(binding)) + "\"}\n").getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] bytes(int value) { return ("artifact-" + value).getBytes(StandardCharsets.US_ASCII); }
  private static byte[] sha(byte[] value) {
    try { return MessageDigest.getInstance("SHA-256").digest(value); }
    catch (Exception error) { throw new AssertionError(error); }
  }
  private static void reject(byte[] raw) {
    assertThrows(IllegalArgumentException.class, () -> ControlDeploymentManifest.parse(raw));
  }
  private static byte[] replace(byte[] raw, String before, String after) {
    return new String(raw, StandardCharsets.UTF_8).replace(before, after).getBytes(StandardCharsets.UTF_8);
  }
  private static byte[] replaceFirstNumber(byte[] raw, String before, String after) {
    return new String(raw, StandardCharsets.US_ASCII)
        .replaceFirst(":" + before + "([,}])", ":" + after + "$1")
        .getBytes(StandardCharsets.US_ASCII);
  }
  private static byte[] concat(byte[] first, byte[] second) {
    byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }
  private static byte[] swapFirstTwoKeys(byte[] raw) {
    String text = new String(raw, StandardCharsets.US_ASCII);
    String first = "\"schema\":\"luosandbox.minecraft-control-deployment.v2\",";
    String second = "\"security\":\"luosandbox.control-auth.v1\",";
    return text.replace(first + second, second + first).getBytes(StandardCharsets.US_ASCII);
  }
}
