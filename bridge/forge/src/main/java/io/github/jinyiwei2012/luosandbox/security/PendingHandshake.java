package io.github.jinyiwei2012.luosandbox.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Synchronized, bounded hub-side proof completion primitive; it owns no socket or allowlist persistence. */
public final class PendingHandshake {
  public static final int MAX_PENDING = 4;
  public static final long TTL_MILLIS = 5_000;

  private final Map<String, ControlAuth.Transcript> allowlist;
  private final Map<String, byte[]> roleKeys;
  private final Map<String, State> pending = new LinkedHashMap<>();

  public PendingHandshake(Map<String, ControlAuth.Transcript> allowlist, Map<String, byte[]> roleKeys) {
    this.allowlist = Map.copyOf(allowlist);
    Map<String, byte[]> copied = new LinkedHashMap<>();
    roleKeys.forEach((id, key) -> copied.put(id, key.clone()));
    this.roleKeys = Map.copyOf(copied);
  }

  public synchronized boolean begin(String connectionId, String allowlistId, long monotonicNow) {
    cleanup(monotonicNow);
    if (connectionId == null || pending.containsKey(connectionId) || pending.size() >= MAX_PENDING) return false;
    ControlAuth.Transcript transcript = allowlist.get(allowlistId);
    byte[] key = roleKeys.get(allowlistId);
    if (transcript == null || key == null) return false;
    try {
      pending.put(connectionId, new State(transcript, key, Math.addExact(monotonicNow, TTL_MILLIS)));
      return true;
    } catch (ArithmeticException error) {
      return false;
    }
  }

  public synchronized Optional<AuthenticatedHandshake> consumePeerProof(String connectionId, String proof,
      long monotonicNow) {
    State state = pending.remove(connectionId);
    if (state == null || monotonicNow >= state.expiresAt()) return Optional.empty();
    if (!ControlAuth.verify(state.key(), ControlAuth.Label.PEER_PROOF, state.transcript(), proof)) return Optional.empty();
    byte[] readyProof = ControlAuth.hmac(state.key(), ControlAuth.transcript(ControlAuth.Label.READY_PROOF, state.transcript()));
    return Optional.of(new AuthenticatedHandshake(connectionId, state.transcript(), readyProof));
  }

  public synchronized void disconnect(String connectionId) { pending.remove(connectionId); }
  public synchronized int pendingCount(long monotonicNow) { cleanup(monotonicNow); return pending.size(); }

  private void cleanup(long monotonicNow) { pending.entrySet().removeIf(entry -> monotonicNow >= entry.getValue().expiresAt()); }

  private record State(ControlAuth.Transcript transcript, byte[] key, long expiresAt) {
    private State { key = key.clone(); }
    @Override public byte[] key() { return key.clone(); }
  }

  public record AuthenticatedHandshake(String connectionId, ControlAuth.Role role, String peerInstallId,
      String hubInstallId, String keyId, String protocol, byte[] modHash, byte[] configHash,
      byte[] bindingHash, byte[] readyProof) {
    private AuthenticatedHandshake(String connectionId, ControlAuth.Transcript transcript, byte[] readyProof) {
      this(connectionId, transcript.peerRole(), transcript.peerInstallId(), transcript.hubInstallId(),
          transcript.keyId(), transcript.protocol(), transcript.modHash(), transcript.configHash(),
          transcript.bindingHash(), readyProof);
    }
    public AuthenticatedHandshake {
      modHash = modHash.clone(); configHash = configHash.clone(); bindingHash = bindingHash.clone(); readyProof = readyProof.clone();
    }
    @Override public byte[] modHash() { return modHash.clone(); }
    @Override public byte[] configHash() { return configHash.clone(); }
    @Override public byte[] bindingHash() { return bindingHash.clone(); }
    @Override public byte[] readyProof() { return readyProof.clone(); }
  }
}
