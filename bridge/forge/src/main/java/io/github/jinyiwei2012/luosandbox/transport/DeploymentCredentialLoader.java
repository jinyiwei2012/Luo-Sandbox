package io.github.jinyiwei2012.luosandbox.transport;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.ControlDeploymentManifest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Dormant snapshot loader; provisioning is neither socket authentication nor activation. */
final class DeploymentCredentialLoader {
  enum Purpose { MANIFEST, MOD, POLICY, BINDING, CREDENTIAL }
  interface Resource { void close(); }
  interface HeldFile {
    /** Positive byte count, -1 only for confirmed EOF; zero/no-progress is invalid. */
    int read(byte[] ownedBuffer, int offset, int length);
  }
  interface Custody {
    HeldFile file(Purpose purpose);
    void validate();
  }
  interface NativePort {
    Custody acquire(TrustedDeploymentPolicy policy, CustodyAttemptReceipt receipt);
  }
  static final class Unavailable extends RuntimeException {
    Unavailable() { super("deployment custody unavailable", null, false, false); }
  }
  static final class TrustedDeploymentPolicy {
    final String root;
    private final String[] paths;
    final ControlAuth.Role role;
    final String peerId, hubId, keyId;
    final long volume, rootIndex;
    private final byte[] configHash, rootOwner;
    private final byte[][] fileOwners, content, security, namespace, readers;

    TrustedDeploymentPolicy(String root, String[] paths, ControlAuth.Role role, String peerId,
        String hubId, String keyId, byte[] configHash, long volume, long rootIndex,
        byte[] rootOwner, byte[][] fileOwners, byte[][] content, byte[][] security,
        byte[][] namespace, byte[][] readers) {
      lexical(root);
      if (paths == null || paths.length != 5 || role == null || role == ControlAuth.Role.AGENT_HUB
          || volume <= 0 || volume > 0xffffffffL || rootIndex == 0) throw new Unavailable();
      this.paths = paths.clone();
      for (int i = 0; i < 5; i++) {
        lexical(this.paths[i]);
        if (!this.paths[i].substring(0, this.paths[i].lastIndexOf('\\')).equalsIgnoreCase(root)) throw new Unavailable();
        for (int j = 0; j < i; j++) if (this.paths[j].equalsIgnoreCase(this.paths[i])) throw new Unavailable();
      }
      ControlAuth.requireId(peerId); ControlAuth.requireId(hubId); ControlAuth.requireId(keyId);
      ControlAuth.requireHash(configHash);
      this.root = root; this.role = role; this.peerId = peerId; this.hubId = hubId; this.keyId = keyId;
      this.configHash = configHash.clone(); this.volume = volume; this.rootIndex = rootIndex;
      this.rootOwner = sid(rootOwner);
      if (fileOwners == null || fileOwners.length != 5) throw new Unavailable();
      this.fileOwners = Arrays.stream(fileOwners).map(TrustedDeploymentPolicy::sid).toArray(byte[][]::new);
      this.content = sids(content); this.security = sids(security); this.namespace = sids(namespace); this.readers = sids(readers);
      if (!contains(this.security, this.rootOwner)) throw new Unavailable();
      for (byte[] owner : this.fileOwners) if (!contains(this.security, owner)) throw new Unavailable();
    }
    byte[] configHash() { return configHash.clone(); }
    String path(Purpose purpose) { return paths[purpose.ordinal()]; }
    byte[] owner(int file) { return (file < 0 ? rootOwner : fileOwners[file]).clone(); }
    boolean trusted(int category, byte[] sid) {
      return contains(switch (category) { case 0 -> content; case 1 -> security; case 2 -> namespace; case 3 -> readers; default -> throw new Unavailable(); }, sid);
    }
    private static byte[] sid(byte[] value) {
      if (value == null || value.length < 12 || value.length > 68 || value[0] != 1
          || (value[1] & 255) > 15 || value.length != 8 + (value[1] & 255) * 4) throw new Unavailable();
      return value.clone();
    }
    private static byte[][] sids(byte[][] values) {
      if (values == null || values.length < 1 || values.length > 16) throw new Unavailable();
      byte[][] copy = Arrays.stream(values).map(TrustedDeploymentPolicy::sid).toArray(byte[][]::new);
      for (int i = 0; i < copy.length; i++) for (int j = 0; j < i; j++) if (Arrays.equals(copy[i], copy[j])) throw new Unavailable();
      return copy;
    }
    private static boolean contains(byte[][] values, byte[] sid) {
      for (byte[] value : values) if (Arrays.equals(value, sid)) return true;
      return false;
    }
    static void lexical(String value) {
      if (value == null || value.length() > 259 || !value.matches("[A-Za-z]:\\\\[^/]+")) throw new Unavailable();
      String[] parts = value.substring(3).split("\\\\", -1);
      if (parts.length > 32) throw new Unavailable();
      for (String part : parts) {
        for (int i = 0; i < part.length(); i++) {
          char c = part.charAt(i);
          if (Character.isHighSurrogate(c)) {
            if (++i >= part.length() || !Character.isLowSurrogate(part.charAt(i))) throw new Unavailable();
          } else if (Character.isLowSurrogate(c)) throw new Unavailable();
        }
        String base = part.split("\\.", -1)[0].toUpperCase(Locale.ROOT);
        if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
            || part.chars().anyMatch(c -> c < 32 || "<>:\"/|?*".indexOf(c) >= 0)
            || base.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) throw new Unavailable();
      }
    }
  }

  record CleanupSnapshot(String state, int retained, boolean unknown) {}
  static final class CustodyAttemptReceipt {
    private String state = "FRESH";
    private boolean unknown;
    private Object operation;
    private final NativeRecord[] nativeRecords = new NativeRecord[44];
    static final class NativeRecord {
      enum Kind { HANDLE, DESCRIPTOR, QUERY_MEMORY, WCHAR_MEMORY }
      private final Kind kind;
      private final WindowsDeploymentCustody.Api api;
      private final CustodyAttemptReceipt owner;
      private final int slot;
      private final Object queryOperation;
      private Object owned;
      private String state = "QUERYING";
      private NativeRecord(Kind kind, WindowsDeploymentCustody.Api api, CustodyAttemptReceipt receipt, int slot, Object token) {
        this.kind = kind; this.api = api; owner = receipt; this.slot = slot; queryOperation = token;
      }
    }
    synchronized Object beginOperation() {
      if (!state.equals("ACQUIRING") || unknown || operation != null) { unknown = true; throw new Unavailable(); }
      return operation = new Object();
    }
    synchronized void check(Object token) {
      if (token == null || operation != token || !state.equals("ACQUIRING") || unknown) throw new Unavailable();
    }
    synchronized void endOperation(Object token) { if (operation == token) operation = null; }
    synchronized NativeRecord reserve(Object token, NativeRecord.Kind kind, WindowsDeploymentCustody.Api api) {
      check(token);
      int start = switch (kind) { case HANDLE -> 0; case DESCRIPTOR -> 38; case QUERY_MEMORY -> 39; case WCHAR_MEMORY -> 43; };
      int end = switch (kind) { case HANDLE -> 38; case DESCRIPTOR -> 39; case QUERY_MEMORY -> 43; case WCHAR_MEMORY -> 44; };
      for (int i = start; i < end; i++) {
        if (nativeRecords[i] == null || nativeRecords[i].state.equals("RELEASED")) {
          NativeRecord record = new NativeRecord(kind, api, this, i, token);
          nativeRecords[i] = record;
          return record;
        }
      }
      throw new Unavailable();
    }
    synchronized Object bindReturned(Object token, NativeRecord record, Object value) {
      recordIdentity(record);
      if (record.queryOperation != token || !(record.state.equals("QUERYING") || record.state.equals("UNKNOWN"))) throw new Unavailable();
      record.owned = value; // The original reserved record owns a late return before validation.
      if (!record.state.equals("QUERYING") || operation != token || !accepting()) {
        record.state = "UNKNOWN"; unknown = true; throw new Unavailable();
      }
      if (value == null) { record.state = "RELEASED"; throw new Unavailable(); }
      record.state = "OWNED"; return value;
    }
    synchronized void finishEmptyFailure(Object token, NativeRecord record) {
      recordIdentity(record);
      if (record.queryOperation != token || !(record.state.equals("QUERYING") || record.state.equals("UNKNOWN"))) throw new Unavailable();
      if (operation == token && accepting() && record.state.equals("QUERYING")) record.state = "RELEASED";
      else { record.state = "UNKNOWN"; unknown = true; }
    }
    synchronized void reconcileDescriptor(Object token, NativeRecord record, boolean success, Object pointer, boolean ambiguous) {
      recordIdentity(record);
      if (record.queryOperation != token || !(record.state.equals("QUERYING") || record.state.equals("UNKNOWN"))) throw new Unavailable();
      record.owned = pointer;
      if (operation != token || !accepting() || !record.state.equals("QUERYING") || ambiguous || !success && pointer != null) {
        record.state = "OPAQUE_UNKNOWN"; unknown = true; throw new Unavailable();
      }
      if (!success || pointer == null) { record.state = "RELEASED"; throw new Unavailable(); }
      record.state = "OWNED";
    }
    void closeOwned(Object token, NativeRecord record) {
      synchronized (this) { recordIdentity(record); check(token); }
      Throwable failure = closeRecord(record);
      if (failure instanceof Error error) throw error;
      if (failure != null) throw new Unavailable();
      check(token);
    }
    private Throwable closeRecord(NativeRecord record) {
      Object value;
      synchronized (this) {
        recordIdentity(record);
        if (record.state.equals("RELEASED")) return null;
        if (!record.state.equals("OWNED")) { unknown = true; return new Unavailable(); }
        record.state = "CLOSING"; value = record.owned;
      }
      Throwable first = null;
      try { if (record.kind == NativeRecord.Kind.QUERY_MEMORY || record.kind == NativeRecord.Kind.WCHAR_MEMORY) record.api.clear(value); }
      catch (Throwable failure) { first = failure; }
      try { record.api.release(record.kind, value); }
      catch (Throwable failure) { first = prefer(first, failure); }
      synchronized (this) {
        recordIdentity(record);
        if (first != null || !record.state.equals("CLOSING")) { record.state = "UNKNOWN"; unknown = true; return first == null ? new Unavailable() : first; }
        record.owned = null; record.state = "RELEASED";
      }
      return null;
    }
    private void recordIdentity(NativeRecord record) {
      if (record == null || record.owner != this || record.slot < 0 || record.slot >= nativeRecords.length
          || nativeRecords[record.slot] != record) throw new Unavailable();
    }
    synchronized void claim() {
      if (!state.equals("FRESH")) throw new Unavailable();
      state = "ACQUIRING";
    }
    private synchronized boolean accepting() { return state.equals("ACQUIRING") && !unknown; }
    synchronized void invalidate() { unknown = true; }
    synchronized void acquiring() { if (!state.equals("ACQUIRING") || unknown) throw new Unavailable(); }
    synchronized CleanupSnapshot snapshot() {
      int retained = 0;
      for (NativeRecord resource : nativeRecords) if (resource != null && !resource.state.equals("RELEASED")) retained++;
      return new CleanupSnapshot(state, retained, unknown);
    }
    Throwable release(Throwable first) {
      synchronized (this) {
        if (!state.equals("ACQUIRING")) { unknown = true; return prefer(first, new Unavailable()); }
        state = "CLOSING";
        if (operation != null || unknown) {
          unknown = true; state = "UNKNOWN";
          for (NativeRecord record : nativeRecords) if (record != null && !record.state.equals("RELEASED")) record.state = "UNKNOWN";
          return prefer(first, new Unavailable());
        }
      }
      for (int i = nativeRecords.length - 1; i >= 0; i--) {
        NativeRecord resource;
        synchronized (this) { resource = nativeRecords[i]; if (resource == null) continue; }
        Throwable failure = closeRecord(resource);
        if (failure != null) first = prefer(first, failure);
      }
      synchronized (this) {
        if (snapshot().retained() != 0) unknown = true;
        state = unknown ? "UNKNOWN" : "RELEASED";
      }
      return first;
    }
  }

  private static final class Secret implements V2PeerConnector.CredentialOwner {
    private byte[] bytes;
    Secret(byte[] owned) { bytes = owned; }
    public synchronized byte[] copy() { if (bytes == null) throw new Unavailable(); return bytes.clone(); }
    public synchronized void close() { if (bytes != null) { Arrays.fill(bytes, (byte) 0); bytes = null; } }
    synchronized boolean live() { return bytes != null; }
    @Override public String toString() { return "CredentialOwner[redacted]"; }
  }
  static final class Provisioning {
    final ControlDeploymentManifest manifest;
    final String keyId;
    private final Secret owner;
    private Provisioning(ControlDeploymentManifest manifest, String keyId, Secret owner) {
      this.manifest = manifest; this.keyId = keyId; this.owner = owner;
    }
    V2PeerConnector.CredentialOwner credential() { return owner; }
    @Override public String toString() { return "Provisioning[redacted]"; }
  }
  static final class HandoffSlot implements AutoCloseable {
    private String state = "EMPTY";
    private Provisioning value;
    private boolean transferred;
    private synchronized void publish(Provisioning provisioning) {
      if (!state.equals("EMPTY")) throw new Unavailable();
      value = provisioning; state = "READY";
    }
    synchronized Provisioning claim() {
      if (!state.equals("READY")) throw new Unavailable(); state = "CLAIMED"; return value;
    }
    synchronized void acknowledgeConstruction() {
      if (!state.equals("CLAIMED") || !value.owner.live()) throw new Unavailable();
      transferred = true; value = null; state = "CLOSED_TRANSFERRED";
    }
    synchronized String state() { return state; }
    public void close() {
      Secret secret;
      synchronized (this) {
        if (transferred || state.equals("CLOSED_WIPED")) return;
        state = "CLOSED_WIPED"; secret = value == null ? null : value.owner; value = null;
      }
      if (secret != null) secret.close();
    }
  }

  private final NativePort port;
  DeploymentCredentialLoader(NativePort port) { this.port = Objects.requireNonNull(port); }
  void load(TrustedDeploymentPolicy policy, CustodyAttemptReceipt receipt, HandoffSlot slot) {
    Objects.requireNonNull(receipt); Objects.requireNonNull(slot); receipt.claim();
    byte[] secret = null;
    Throwable first = null;
    ControlDeploymentManifest manifest = null;
    try {
      Objects.requireNonNull(policy);
      Custody custody = Objects.requireNonNull(port.acquire(policy, receipt));
      custody.validate();
      byte[] rawManifest = read(custody.file(Purpose.MANIFEST), 4096);
      manifest = ControlDeploymentManifest.parse(rawManifest);
      byte[] mod = read(custody.file(Purpose.MOD), 1 << 20);
      byte[] config = read(custody.file(Purpose.POLICY), 1 << 20);
      byte[] binding = read(custody.file(Purpose.BINDING), 1 << 20);
      manifest.verifyArtifacts(policy.role, policy.peerId, policy.hubId, policy.configHash(), mod, config, binding);
      secret = read(custody.file(Purpose.CREDENTIAL), 32);
      if (secret.length != 32) throw new Unavailable();
      custody.validate();
    } catch (Throwable failure) { first = failure; }
    first = receipt.release(first);
    if (first != null || receipt.snapshot().unknown()) {
      if (secret != null) Arrays.fill(secret, (byte) 0);
      if (first instanceof Error error) throw error;
      throw new Unavailable();
    }
    Secret owner = null;
    try {
      owner = new Secret(secret);
      slot.publish(new Provisioning(manifest, policy.keyId, owner));
      secret = null;
    } finally {
      if (secret != null) { Arrays.fill(secret, (byte) 0); if (owner != null) owner.close(); }
    }
  }
  private static byte[] read(HeldFile file, int limit) {
    byte[] buffer = new byte[limit + 1]; int used = 0;
    try {
      for (int calls = 0; calls <= limit + 1; calls++) {
        int count = file.read(buffer, used, buffer.length - used);
        if (count == -1) return Arrays.copyOf(buffer, used);
        if (count <= 0 || count > buffer.length - used) throw new Unavailable();
        used += count; if (used > limit) throw new Unavailable();
      }
      throw new Unavailable();
    } finally { Arrays.fill(buffer, (byte) 0); }
  }
  private static Throwable prefer(Throwable first, Throwable later) {
    return first == null || !(first instanceof Error) && later instanceof Error ? later : first;
  }
}
