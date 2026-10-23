package io.github.jinyiwei2012.luosandbox.security;

import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Strict pure-byte value parser for the frozen v2 role-scoped deployment manifest. */
public final class ControlDeploymentManifest {
  public static final int MAX_MANIFEST_BYTES = 4096;
  public static final String SCHEMA = "luosandbox.minecraft-control-deployment.v2";
  public static final String SECURITY = "luosandbox.control-auth.v1";
  public static final String PROTOCOL = "LUO_CONTROL_POSTAUTH_V2";
  public static final String POST_AUTH_PROFILE = "LUO_CONTROL_POSTAUTH_V2";
  public static final String SESSION_KDF_DOMAIN = "LUO_CONTROL_SESSION_KEY_V2";
  public static final String FRAME_MAC_DOMAIN = "LUO_CONTROL_FRAME_V2";

  private static final List<String> KEYS = List.of(
      "schema", "security", "peerRole", "peerInstallId", "hubInstallId", "protocol",
      "postAuthProfile", "sessionKdfDomain", "frameMacDomain", "frameMaxUtf8Bytes",
      "payloadMaxRawBytes", "asciiIdMaxBytes", "outboundQueueFrames", "openTimeoutMs",
      "heartbeatIntervalMs", "heartbeatTimeoutMs", "routeCapacity", "routeTtlMs",
      "bindingPendingCapacity", "storeOutstandingCapacity", "modManifestSha256",
      "policyConfigSha256", "bindingManifestSha256");

  private static final HexFormat HEX = HexFormat.of();
  private final ControlAuth.Role peerRole;
  private final String peerInstallId;
  private final String hubInstallId;
  private final byte[] modManifestHash;
  private final byte[] policyConfigHash;
  private final byte[] bindingManifestHash;
  private final byte[] canonicalBytes;
  private final byte[] configHash;

  private ControlDeploymentManifest(ControlAuth.Role peerRole, String peerInstallId,
      String hubInstallId, byte[] modManifestHash, byte[] policyConfigHash,
      byte[] bindingManifestHash, byte[] canonicalBytes) {
    this.peerRole = peerRole;
    this.peerInstallId = peerInstallId;
    this.hubInstallId = hubInstallId;
    this.modManifestHash = modManifestHash.clone();
    this.policyConfigHash = policyConfigHash.clone();
    this.bindingManifestHash = bindingManifestHash.clone();
    this.canonicalBytes = canonicalBytes.clone();
    this.configHash = sha256(this.canonicalBytes);
  }

  /** Parse and require the exact frozen 23-key ASCII byte representation. */
  public static ControlDeploymentManifest parse(byte[] raw) {
    if (raw == null || raw.length == 0 || raw.length > MAX_MANIFEST_BYTES) {
      throw invalid();
    }
    byte[] snapshot = raw.clone();
    for (byte value : snapshot) {
      if ((value & 0xff) > 0x7f || value == 0 || value == '\r') throw invalid();
    }
    if (snapshot[snapshot.length - 1] != '\n') throw invalid();
    for (int index = 0; index < snapshot.length - 1; index++) {
      if (snapshot[index] == '\n') throw invalid();
    }

    Map<String, Object> values;
    try {
      values = StrictJson.decodeObject(snapshot);
    } catch (StrictJson.DecodeException | RuntimeException error) {
      throw invalid();
    }
    if (!List.copyOf(values.keySet()).equals(KEYS)) throw invalid();

    String roleName = string(values, "peerRole");
    ControlAuth.Role role;
    try {
      role = ControlAuth.Role.valueOf(roleName);
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
    if (role == ControlAuth.Role.AGENT_HUB) throw invalid();
    String peerInstallId = id(values, "peerInstallId");
    String hubInstallId = id(values, "hubInstallId");
    requireLiteral(values, "schema", SCHEMA);
    requireLiteral(values, "security", SECURITY);
    requireLiteral(values, "protocol", PROTOCOL);
    requireLiteral(values, "postAuthProfile", POST_AUTH_PROFILE);
    requireLiteral(values, "sessionKdfDomain", SESSION_KDF_DOMAIN);
    requireLiteral(values, "frameMacDomain", FRAME_MAC_DOMAIN);
    requireNumber(values, "frameMaxUtf8Bytes", 4096);
    requireNumber(values, "payloadMaxRawBytes", 2048);
    requireNumber(values, "asciiIdMaxBytes", 128);
    requireNumber(values, "outboundQueueFrames", 8);
    requireNumber(values, "openTimeoutMs", 5000);
    requireNumber(values, "heartbeatIntervalMs", 500);
    requireNumber(values, "heartbeatTimeoutMs", 2000);
    requireNumber(values, "routeCapacity", 2);
    requireNumber(values, "routeTtlMs", 5000);
    requireNumber(values, "bindingPendingCapacity", 8);
    requireNumber(values, "storeOutstandingCapacity", 2);
    byte[] modHash = hash(values, "modManifestSha256");
    byte[] policyHash = hash(values, "policyConfigSha256");
    byte[] bindingHash = hash(values, "bindingManifestSha256");

    ControlDeploymentManifest manifest = new ControlDeploymentManifest(role, peerInstallId,
        hubInstallId, modHash, policyHash, bindingHash, snapshot);
    if (!MessageDigest.isEqual(snapshot, manifest.encodeCanonical())) throw invalid();
    return manifest;
  }

  /**
   * Compare caller-supplied trusted identity/config expectations and raw artifact bytes.
   * This performs no filesystem, ACL, provisioning, authorization, or activation work.
   */
  public ArtifactMatch verifyArtifacts(ControlAuth.Role expectedRole,
      String expectedPeerInstallId, String expectedHubInstallId, byte[] expectedConfigHash,
      byte[] rawModManifest, byte[] rawPolicyConfig, byte[] rawBindingManifest) {
    byte[] trustedConfig = snapshotHash(expectedConfigHash);
    byte[] trustedMod = snapshotArtifact(rawModManifest);
    byte[] trustedPolicy = snapshotArtifact(rawPolicyConfig);
    byte[] trustedBinding = snapshotArtifact(rawBindingManifest);
    if (expectedRole == null || expectedRole == ControlAuth.Role.AGENT_HUB
        || expectedRole != peerRole
        || !peerInstallId.equals(expectedPeerInstallId)
        || !hubInstallId.equals(expectedHubInstallId)) {
      throw new IllegalArgumentException("trusted deployment identity mismatch");
    }
    byte[] mod = sha256(trustedMod);
    byte[] policy = sha256(trustedPolicy);
    byte[] binding = sha256(trustedBinding);
    if (!MessageDigest.isEqual(configHash, trustedConfig)
        || !MessageDigest.isEqual(modManifestHash, mod)
        || !MessageDigest.isEqual(policyConfigHash, policy)
        || !MessageDigest.isEqual(bindingManifestHash, binding)) {
      throw new IllegalArgumentException("trusted deployment hash mismatch");
    }
    return new ArtifactMatch(configHash, mod, policy, binding);
  }

  public ControlAuth.Role peerRole() { return peerRole; }
  public String peerInstallId() { return peerInstallId; }
  public String hubInstallId() { return hubInstallId; }
  public byte[] modManifestHash() { return modManifestHash.clone(); }
  public byte[] policyConfigHash() { return policyConfigHash.clone(); }
  public byte[] bindingManifestHash() { return bindingManifestHash.clone(); }
  public byte[] configHash() { return configHash.clone(); }
  public byte[] encode() { return canonicalBytes.clone(); }

  @Override public String toString() {
    return "ControlDeploymentManifest[role=" + peerRole + ",peerInstallId="
        + peerInstallId + ",hubInstallId=" + hubInstallId + ",hashes=redacted]";
  }

  /** Verified byte equality only; this is not an authorization or availability capability. */
  public record ArtifactMatch(byte[] configHash, byte[] modManifestHash,
      byte[] policyConfigHash, byte[] bindingManifestHash) {
    public ArtifactMatch {
      ControlAuth.requireHash(configHash);
      ControlAuth.requireHash(modManifestHash);
      ControlAuth.requireHash(policyConfigHash);
      ControlAuth.requireHash(bindingManifestHash);
      configHash = configHash.clone();
      modManifestHash = modManifestHash.clone();
      policyConfigHash = policyConfigHash.clone();
      bindingManifestHash = bindingManifestHash.clone();
    }
    @Override public byte[] configHash() { return configHash.clone(); }
    @Override public byte[] modManifestHash() { return modManifestHash.clone(); }
    @Override public byte[] policyConfigHash() { return policyConfigHash.clone(); }
    @Override public byte[] bindingManifestHash() { return bindingManifestHash.clone(); }
    @Override public String toString() { return "ArtifactMatch[hashes=redacted]"; }
  }

  private byte[] encodeCanonical() {
    String json = "{\"schema\":\"" + SCHEMA + "\",\"security\":\"" + SECURITY
        + "\",\"peerRole\":\"" + peerRole.name() + "\",\"peerInstallId\":\""
        + peerInstallId + "\",\"hubInstallId\":\"" + hubInstallId
        + "\",\"protocol\":\"" + PROTOCOL + "\",\"postAuthProfile\":\""
        + POST_AUTH_PROFILE + "\",\"sessionKdfDomain\":\"" + SESSION_KDF_DOMAIN
        + "\",\"frameMacDomain\":\"" + FRAME_MAC_DOMAIN
        + "\",\"frameMaxUtf8Bytes\":4096,\"payloadMaxRawBytes\":2048"
        + ",\"asciiIdMaxBytes\":128,\"outboundQueueFrames\":8,\"openTimeoutMs\":5000"
        + ",\"heartbeatIntervalMs\":500,\"heartbeatTimeoutMs\":2000,\"routeCapacity\":2"
        + ",\"routeTtlMs\":5000,\"bindingPendingCapacity\":8,\"storeOutstandingCapacity\":2"
        + ",\"modManifestSha256\":\"" + HEX.formatHex(modManifestHash)
        + "\",\"policyConfigSha256\":\"" + HEX.formatHex(policyConfigHash)
        + "\",\"bindingManifestSha256\":\"" + HEX.formatHex(bindingManifestHash)
        + "\"}\n";
    return json.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] snapshotArtifact(byte[] value) {
    if (value == null) throw new IllegalArgumentException("trusted artifact bytes required");
    return value.clone();
  }

  private static byte[] snapshotHash(byte[] value) {
    if (value == null) throw new IllegalArgumentException("trusted config hash required");
    byte[] snapshot = value.clone();
    ControlAuth.requireHash(snapshot);
    return snapshot;
  }

  private static String id(Map<String, Object> values, String name) {
    String value = string(values, name);
    try {
      ControlAuth.requireId(value);
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
    if (value.getBytes(StandardCharsets.US_ASCII).length > 128) throw invalid();
    return value;
  }

  private static byte[] hash(Map<String, Object> values, String name) {
    String value = string(values, name);
    if (!value.matches("[0-9a-f]{64}")) throw invalid();
    return HEX.parseHex(value);
  }

  private static String string(Map<String, Object> values, String name) {
    Object value = values.get(name);
    if (!(value instanceof String result)) throw invalid();
    return result;
  }

  private static void requireLiteral(Map<String, Object> values, String name, String expected) {
    if (!expected.equals(string(values, name))) throw invalid();
  }

  private static void requireNumber(Map<String, Object> values, String name, long expected) {
    Object value = values.get(name);
    if (!(value instanceof Long number) || number != expected) throw invalid();
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("invalid control deployment manifest");
  }
}
