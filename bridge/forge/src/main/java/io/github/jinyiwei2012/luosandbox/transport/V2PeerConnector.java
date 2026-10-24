package io.github.jinyiwei2012.luosandbox.transport;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.PostAuthSessionKeys;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Deterministic, default-disabled single attempt. No production transport or authority wiring. */
final class V2PeerConnector {
  static final String PROFILE = "LUO_CONTROL_POSTAUTH_V2";
  enum State { IDLE, DISABLED, CONNECTING, WAIT_CHALLENGE, WAIT_READY,
    WAIT_SESSION_OPEN, OPEN, STOPPING, TERMINAL_API_CLOSED, TERMINAL_UNKNOWN }
  interface CredentialOwner { byte[] copy(); void close(); }
  interface Socket {
    String subprotocol();
    CompletionStage<Void> send(byte[] ownedUtf8);
    CompletionStage<Void> close();
    void requestOne();
    void abort();
  }
  interface Events {
    void opened(Socket socket);
    void textFragment(Socket socket, CharSequence text, boolean last);
    void ping(Socket socket, int length);
    void pong(Socket socket, int length);
    void closed(Socket socket);
    void failed(Socket socket);
  }
  @FunctionalInterface interface Dialer { CompletionStage<Socket> connect(Events events); }
  record Context(boolean enabled, URI endpoint, String profile, ControlAuth.Role role,
      String peerId, String hubId, String keyId, byte[] modHash, byte[] configHash,
      byte[] bindingHash) {
    Context {
      Objects.requireNonNull(endpoint);
      if (!"ws".equals(endpoint.getScheme()) || endpoint.getPort() < 1
          || endpoint.getPort() > 65535 || endpoint.getRawUserInfo() != null
          || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
          || !("127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost()))
          || endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty()
          || !PROFILE.equals(profile) || role == null || role == ControlAuth.Role.AGENT_HUB) {
        throw new IllegalArgumentException("invalid v2 context");
      }
      ControlAuth.requireId(peerId); ControlAuth.requireId(hubId); ControlAuth.requireId(keyId);
      ControlAuth.requireHash(modHash); ControlAuth.requireHash(configHash);
      ControlAuth.requireHash(bindingHash);
      modHash = modHash.clone(); configHash = configHash.clone(); bindingHash = bindingHash.clone();
    }
    @Override public byte[] modHash() { return modHash.clone(); }
    @Override public byte[] configHash() { return configHash.clone(); }
    @Override public byte[] bindingHash() { return bindingHash.clone(); }
  }
  private enum LeaseState { FREE, ASSEMBLING, RETAINED, PROCESSING }
  private enum Phase { NONE, HELLO, PROOF, SESSION, CLOSE }
  private static final class Lane {
    final char[] chars = new char[4096];
    final byte[] bytes = new byte[4096];
    LeaseState state = LeaseState.FREE;
    long lease;
    long deadline;
    int length;
    int byteLength;
    int fragments;
    char high;
    boolean announced;
  }
  /** Only the private verifier creates this provenance, never a caller flag/transcript. */
  private record Verified(ControlAuth.Transcript transcript, long readyAt) {}

  private final Object lock = new Object();
  private final Context context;
  private final BooleanSupplier owner;
  private final LongSupplier monotonic;
  private final Supplier<Instant> utc;
  private final Supplier<byte[]> nonce;
  private final CredentialOwner credential;
  private final Dialer dialer;
  private final Runnable fence;
  private final PostAuthPeerSession.CleanupDiagnostics diagnostics;
  private final V2OwnerMailbox mailbox;
  private final V2SessionSendAdapter sends;
  private final Lane[] lanes = {new Lane(), new Lane()};
  private final Events events = new AttemptEvents();
  private volatile boolean invalid;
  private State state = State.IDLE;
  private boolean started;
  private Socket socket;
  private boolean opened;
  private boolean built;
  private boolean credit;
  private boolean aborted;
  private boolean cleanup;
  private boolean fenced;
  private boolean credentialClosed;
  private boolean sessionClosed;
  private boolean physicalIntent;
  private boolean closeStarted;
  private volatile boolean closeSettled;
  private long nextLease;
  private volatile long lastNow;
  private Instant lastUtc;
  private long handshakeDeadline;
  private long challengeDeadline = Long.MAX_VALUE;
  private long closeDeadline;
  private byte[] psk;
  private byte[] peerNonce;
  private ControlAuth.Transcript transcript;
  private PostAuthSessionKeys callerKeys;
  private PostAuthPeerSession session;
  private Phase phase = Phase.NONE;
  private boolean sendOutcome;
  private boolean sendSucceeded;
  private boolean textHandler;
  private int envelopeDepth;
  private volatile boolean inputClosed;
  private boolean cleanupUnknown;
  private volatile boolean closeFailed;
  private volatile boolean cleanupReportPending;
  record CleanupSnapshot(boolean attempted, boolean ownerUnknown, boolean inputClosed,
      boolean outputClosed, boolean physicalCloseFailed) {}

  V2PeerConnector(Context context, BooleanSupplier owner, V2OwnerMailbox.OwnerEnqueue queue,
      LongSupplier monotonic, Supplier<Instant> utc, Supplier<byte[]> nonce,
      CredentialOwner credential, Dialer dialer, Runnable fence,
      PostAuthPeerSession.CleanupDiagnostics diagnostics) {
    this.context = Objects.requireNonNull(context); this.owner = Objects.requireNonNull(owner);
    this.monotonic = Objects.requireNonNull(monotonic); this.utc = Objects.requireNonNull(utc);
    this.nonce = Objects.requireNonNull(nonce); this.credential = Objects.requireNonNull(credential);
    this.dialer = Objects.requireNonNull(dialer); this.fence = Objects.requireNonNull(fence);
    this.diagnostics = Objects.requireNonNull(diagnostics);
    mailbox = new V2OwnerMailbox(owner, Objects.requireNonNull(queue), new V2OwnerMailbox.Handler() {
      public void timerTurn() { envelope(V2PeerConnector.this::timer); }
      public void terminal(V2OwnerMailbox.Reason reason) { envelope(V2PeerConnector.this::stop); }
      public void invalidated(V2OwnerMailbox.Reason reason) { contain(); }
      public void buildTurn() { envelope(V2PeerConnector.this::progress); }
      public void ordinary(V2OwnerMailbox.OrdinaryKind kind, int lane, long lease) {
        envelope(() -> {
          if (kind == V2OwnerMailbox.OrdinaryKind.FAILED
              || kind == V2OwnerMailbox.OrdinaryKind.CLOSED) stop();
          else if (kind == V2OwnerMailbox.OrdinaryKind.TEXT) {
            synchronized (lock) {
              if (lane < 0 || lane >= 2 || lanes[lane].lease != lease
                  || lanes[lane].state != LeaseState.RETAINED) return;
              lanes[lane].announced = true;
            }
            textHandler = true;
            try { progress(); } finally { textHandler = false; signalProgress(); }
          } else progress();
        });
      }
      public void controlTurn(boolean ping, int length) {
        envelope(() -> {
          textHandler = true;
          try { progress(); } finally { textHandler = false; signalProgress(); }
        });
      }
    });
    sends = new V2SessionSendAdapter(owner, mailbox, frame -> {
      if (invalid || socket == null) throw new IllegalStateException("not current");
      return socket.send(frame);
    }, this::envelope, new V2SessionSendAdapter.Containment() {
      public void invalidate() { mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT); }
      public void sendFailure() { mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT); }
    });
  }

  void start() {
    if (!context.enabled()) {
      synchronized (lock) {
        if (started) throw new IllegalStateException("single use");
        started = true; state = State.DISABLED;
      }
      return;
    }
    requireOwner();
    if (started) throw new IllegalStateException("single use");
    started = true;
    envelope(() -> {
      if (invalid) { stop(); return; }
      handshakeDeadline = Math.addExact(now(), 5000);
      current(State.IDLE);
      utcNow();
      current(State.IDLE);
      byte[] secret = credential.copy();
      try {
        current(State.IDLE); ControlAuth.requireHash(secret);
        psk = secret; secret = null;
      } finally { if (secret != null) Arrays.fill(secret, (byte) 0); }
      byte[] suppliedNonce = nonce.get();
      current(State.IDLE);
      requireNonce(suppliedNonce);
      byte[] ownedNonce = suppliedNonce.clone();
      try { current(State.IDLE); peerNonce = ownedNonce; ownedNonce = null; }
      finally { if (ownedNonce != null) Arrays.fill(ownedNonce, (byte) 0); }
      state = State.CONNECTING;
      CompletionStage<Socket> building = Objects.requireNonNull(dialer.connect(events));
      try {
        building.whenComplete((found, failure) -> {
          if (failure != null || found == null) fail();
          else if (bind(found)) {
            synchronized (lock) { built = true; }
            mailbox.postBuild();
          }
        });
        current(State.CONNECTING);
      } catch (Throwable failure) { fail(); }
    });
  }

  void tick() {
    if (invalid) { requireOwner(); envelope(this::timer); }
    else mailbox.signalTimer();
  }
  void close() { requireOwner(); envelope(this::stop); }
  void publishStopped() { mailbox.publishStopped(); }
  State state() { requireOwner(); return state; }
  CleanupSnapshot cleanupSnapshot() {
    requireOwner();
    return new CleanupSnapshot(cleanup, cleanupUnknown, inputClosed, closeSettled, closeFailed);
  }
  private void current(State expected) {
    if (invalid || cleanup || state != expected) throw new IllegalStateException("attempt invalidated");
  }
  private static void requireNonce(byte[] value) {
    ControlAuth.requireHash(value);
    int bits = 0;
    for (byte item : value) bits |= item;
    if (bits == 0) throw new IllegalArgumentException("zero nonce");
  }

  private void requireOwner() {
    boolean permitted;
    try { permitted = owner.getAsBoolean(); } catch (Throwable failure) { permitted = false; }
    if (!permitted) { contain(); throw new IllegalStateException("wrong connector owner"); }
  }
  private long now() {
    long value = monotonic.getAsLong();
    if (value < 0 || value < lastNow) throw new IllegalArgumentException("monotonic rollback");
    lastNow = value; return value;
  }
  private Instant utcNow() {
    Instant value = utc.get(); ControlAuth.requireUtc(value);
    if (lastUtc != null && value.isBefore(lastUtc)) throw new IllegalArgumentException("UTC rollback");
    lastUtc = value; return value;
  }
  private void checkDeadline() {
    State expected = state;
    long value = now();
    current(expected);
    if (value >= handshakeDeadline || value >= challengeDeadline) {
      throw new IllegalArgumentException("handshake timeout");
    }
    Instant observed = utcNow();
    current(expected);
    if (transcript != null && !observed.isBefore(transcript.expiresAt())) {
      throw new IllegalArgumentException("challenge expiry");
    }
  }
  private void fail() { mailbox.requestAbort(V2OwnerMailbox.Reason.INVALID_INPUT); }
  private void contain() {
    Socket found;
    synchronized (lock) { invalid = true; found = socket; }
    abort(found);
  }
  private void abort(Socket found) {
    if (found == null) return;
    synchronized (lock) {
      if (found == socket) { if (aborted) return; aborted = true; }
    }
    try { found.abort(); } catch (Throwable ignored) { /* Foreign containment only. */ }
  }
  private boolean bind(Socket found) {
    boolean accept;
    synchronized (lock) {
      accept = !invalid && found != null && (socket == null || socket == found);
      if (accept && socket == null) socket = found;
    }
    if (!accept) { abort(found); fail(); }
    return accept;
  }
  private boolean callback(Socket found) {
    boolean accept;
    synchronized (lock) {
      accept = !invalid && found == socket && credit;
      if (accept) credit = false;
    }
    if (!accept) { abort(found); fail(); }
    return accept;
  }
  private void signalProgress() {
    mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.PROGRESS, -1, 0);
  }
  private final class AttemptEvents implements Events {
    public void opened(Socket found) {
      if (!bind(found)) return;
      synchronized (lock) { opened = true; }
      mailbox.postBuild();
      signalProgress();
    }
    public void textFragment(Socket found, CharSequence text, boolean last) {
      if (!callback(found)) return;
      try {
        long receipt = monotonic.getAsLong();
        int index = -1;
        long lease = 0;
        synchronized (lock) {
          if (receipt < 0 || receipt < lastNow) throw new IllegalArgumentException("clock rollback");
          Lane lane = null;
          for (int i = 0; i < 2; i++) if (lanes[i].state == LeaseState.ASSEMBLING) {
            lane = lanes[i]; index = i; break;
          }
          if (lane == null) for (int i = 0; i < 2; i++) {
            if (lanes[i].state == LeaseState.FREE) {
              lane = lanes[i]; index = i;
              lane.lease = nextLease = Math.incrementExact(nextLease);
              lane.deadline = Math.addExact(receipt, 5000);
              lane.state = LeaseState.ASSEMBLING; break;
            }
          }
          if (lane == null || text == null) throw new IllegalArgumentException("no lane");
          append(lane, text, last);
          lease = lane.lease;
          if (last) lane.state = LeaseState.RETAINED;
        }
        if (last) mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.TEXT, index, lease);
        else signalProgress();
      } catch (Throwable failure) { fail(); }
    }
    public void ping(Socket found, int length) { control(found, true, length); }
    public void pong(Socket found, int length) { control(found, false, length); }
    private void control(Socket found, boolean ping, int length) {
      if (!callback(found)) return;
      if (length < 0 || length > 125) { fail(); return; }
      mailbox.postControl(ping, length);
    }
    public void closed(Socket found) {
      synchronized (lock) {
        if (found == socket) inputClosed = true;
      }
      if (invalid) { abort(found); return; }
      if (callback(found)) mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.CLOSED, -1, 0);
    }
    public void failed(Socket found) {
      boolean stale;
      synchronized (lock) { stale = found != socket || invalid; }
      if (stale) { abort(found); return; }
      fail();
    }
  }

  private static void append(Lane lane, CharSequence text, boolean last) {
    if (++lane.fragments > 64 || text.length() > 4096 - lane.length) {
      throw new IllegalArgumentException("fragment limit");
    }
    int bytes = lane.byteLength;
    char high = lane.high;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (high != 0) {
        if (!Character.isLowSurrogate(c)) throw new IllegalArgumentException("surrogate");
        bytes += 4; high = 0;
      } else if (Character.isHighSurrogate(c)) high = c;
      else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("surrogate");
      else bytes += c < 128 ? 1 : c < 2048 ? 2 : 3;
      if (bytes > 4096) throw new IllegalArgumentException("UTF8 limit");
    }
    if (last && high != 0) throw new IllegalArgumentException("surrogate");
    for (int i = 0; i < text.length(); i++) lane.chars[lane.length++] = text.charAt(i);
    lane.byteLength = bytes; lane.high = high;
    if (last) {
      int out = 0;
      for (int i = 0; i < lane.length; i++) {
        int c = lane.chars[i];
        if (Character.isHighSurrogate((char) c)) c = Character.toCodePoint((char) c, lane.chars[++i]);
        if (c < 128) lane.bytes[out++] = (byte) c;
        else if (c < 2048) {
          lane.bytes[out++] = (byte) (192 | c >> 6); lane.bytes[out++] = (byte) (128 | c & 63);
        } else if (c < 65536) {
          lane.bytes[out++] = (byte) (224 | c >> 12); lane.bytes[out++] = (byte) (128 | c >> 6 & 63);
          lane.bytes[out++] = (byte) (128 | c & 63);
        } else {
          lane.bytes[out++] = (byte) (240 | c >> 18); lane.bytes[out++] = (byte) (128 | c >> 12 & 63);
          lane.bytes[out++] = (byte) (128 | c >> 6 & 63); lane.bytes[out++] = (byte) (128 | c & 63);
        }
      }
    }
  }

  private void progress() {
    if (invalid) { stop(); return; }
    if (state == State.CONNECTING) {
      checkDeadline();
      synchronized (lock) { if (!built || !opened) return; }
      String profile = socket.subprotocol();
      current(State.CONNECTING);
      if (!context.profile().equals(profile)) throw new IllegalArgumentException("profile");
      state = State.WAIT_CHALLENGE;
      sendHandshake(Phase.HELLO, hello());
    }
    if (sendOutcome) {
      sendOutcome = false;
      if (!sendSucceeded) { stop(); return; }
      phase = Phase.NONE;
    }
    for (int count = 0; count < 2; count++) {
      Lane first = null;
      synchronized (lock) {
        for (Lane lane : lanes) if (lane.state == LeaseState.RETAINED
            && (first == null || lane.lease < first.lease)) first = lane;
        if (first == null || !first.announced || phase == Phase.HELLO || phase == Phase.PROOF) break;
        first.state = LeaseState.PROCESSING;
      }
      try {
        if (now() >= first.deadline) throw new IllegalArgumentException("assembly timeout");
        byte[] raw = Arrays.copyOf(first.bytes, first.byteLength);
        if (session != null) {
          session.receive(raw);
          if (session.state() == PostAuthPeerSession.State.OPEN) state = State.OPEN;
        } else handshake(raw);
      } finally { release(first); }
      if (invalid) break;
    }
    if (!textHandler) grant();
  }
  private void release(Lane lane) {
    synchronized (lock) {
      Arrays.fill(lane.chars, '\0'); Arrays.fill(lane.bytes, (byte) 0);
      lane.length = 0; lane.byteLength = 0; lane.fragments = 0; lane.high = 0;
      lane.announced = false;
      lane.state = LeaseState.FREE;
    }
  }
  private void grant() {
    if (!mailbox.hasIngressCapacity()) return;
    boolean grant = false;
    synchronized (lock) {
      if (!invalid && socket != null && opened && built && !credit) {
        for (Lane lane : lanes) if (lane.state == LeaseState.FREE || lane.state == LeaseState.ASSEMBLING) {
          credit = true; grant = true; break;
        }
      }
    }
    if (grant) socket.requestOne();
  }
  private void sendHandshake(Phase next, byte[] raw) {
    phase = next;
    CompletionStage<Void> sent = sends.sender().send(raw);
    try {
      sent.whenComplete((ignored, failure) -> {
        boolean success = failure == null;
        sends.ownerPost().post(() -> {
          if (invalid || phase != next) return;
          sendSucceeded = success; sendOutcome = true; signalProgress();
        });
      });
    } catch (Throwable failure) { fail(); }
  }
  private byte[] hello() {
    return json(Map.ofEntries(Map.entry("security", ControlAuth.DOMAIN), Map.entry("kind", "HELLO"),
        Map.entry("peerRole", context.role().name()), Map.entry("peerInstallId", context.peerId()),
        Map.entry("hubInstallId", context.hubId()), Map.entry("keyId", context.keyId()),
        Map.entry("protocol", context.profile()), Map.entry("modHash", ControlAuth.b32(context.modHash())),
        Map.entry("configHash", ControlAuth.b32(context.configHash())),
        Map.entry("bindingHash", ControlAuth.b32(context.bindingHash())),
        Map.entry("peerNonce", ControlAuth.b32(peerNonce))));
  }
  private void handshake(byte[] raw) {
    checkDeadline();
    Map<String, Object> fields;
    try { fields = StrictJson.decodeObject(raw); }
    catch (StrictJson.DecodeException failure) { throw new IllegalArgumentException("invalid JSON"); }
    if (!ControlAuth.DOMAIN.equals(fields.get("security"))) throw new IllegalArgumentException("domain");
    if (state == State.WAIT_CHALLENGE) {
      exact(fields, "security", "kind", "challengeId", "hubNonce", "issuedAt", "expiresAt", "hubProof");
      if (!"CHALLENGE".equals(fields.get("kind"))) throw new IllegalArgumentException("kind");
      Instant issued = timestamp(string(fields, "issuedAt"));
      Instant expires = timestamp(string(fields, "expiresAt"));
      Instant receipt = utcNow();
      current(State.WAIT_CHALLENGE);
      if (!issued.plusMillis(5000).equals(expires) || issued.isAfter(receipt.plusMillis(1000))
          || !receipt.isBefore(expires)) throw new IllegalArgumentException("challenge time");
      byte[] hubNonce = ControlAuth.decodeB32(string(fields, "hubNonce"));
      requireNonce(hubNonce);
      transcript = new ControlAuth.Transcript(context.role(), context.peerId(), context.hubId(),
          context.keyId(), context.profile(), context.modHash(), peerNonce,
          hubNonce, string(fields, "challengeId"), issued,
          expires, context.configHash(), context.bindingHash());
      if (!ControlAuth.verify(psk, ControlAuth.Label.HUB_PROOF, transcript, string(fields, "hubProof"))) {
        throw new IllegalArgumentException("hub proof");
      }
      challengeDeadline = Math.min(handshakeDeadline,
          Math.addExact(now(), Math.subtractExact(expires.toEpochMilli(), receipt.toEpochMilli())));
      current(State.WAIT_CHALLENGE);
      state = State.WAIT_READY;
      sendHandshake(Phase.PROOF, json(Map.of("security", ControlAuth.DOMAIN, "kind", "PROOF",
          "challengeId", transcript.challengeId(), "peerProof", ControlAuth.b32(ControlAuth.hmac(psk,
              ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript))))));
    } else if (state == State.WAIT_READY) {
      exact(fields, "security", "kind", "challengeId", "readyProof");
      if (!"READY".equals(fields.get("kind")) || !transcript.challengeId().equals(fields.get("challengeId"))
          || !ControlAuth.verify(psk, ControlAuth.Label.READY_PROOF, transcript, string(fields, "readyProof"))) {
        throw new IllegalArgumentException("ready proof");
      }
      checkDeadline();
      long verifiedAt = now();
      if (verifiedAt >= handshakeDeadline || verifiedAt >= challengeDeadline || invalid) {
        throw new IllegalArgumentException("ready boundary");
      }
      install(new Verified(transcript, verifiedAt));
    } else throw new IllegalArgumentException("handshake state");
  }
  private void install(Verified verified) {
    long deadline = Math.addExact(verified.readyAt(), 5000);
    try { callerKeys = PostAuthSessionKeys.derive(psk, verified.transcript()); }
    finally { erasePsk(); }
    closeCredential();
    if (invalid || cleanup || callerKeys == null) throw new IllegalStateException("installation invalidated");
    phase = Phase.SESSION;
    PostAuthSessionKeys constructingKeys = callerKeys;
    callerKeys = null;
    PostAuthPeerSession constructed;
    try {
      constructed = new PostAuthPeerSession(verified.transcript(), constructingKeys, 1, owner::getAsBoolean,
        this::now, sends.ownerPost(), sends.ownerAbort(), reason -> cleanupReportPending = true, sends.sender(),
        frame -> { throw new IllegalArgumentException("application disabled"); },
        new PostAuthPeerSession.Lifecycle() {
          public void fence(String reason) { invalid = true; fenceAndCredential(); }
          public void physicalClose(String reason) { physicalIntent = true; }
        }, deadline);
    } catch (Throwable failure) {
      try { constructingKeys.close(); }
      catch (Throwable keyFailure) { markUnknown(); failure = remember(failure, keyFailure); }
      rethrow(failure);
      throw new IllegalStateException("session construction failed");
    }
    if (invalid || cleanup || state != State.WAIT_READY) {
      try { constructed.close(); } catch (Throwable failure) { markUnknown(); rethrow(failure); }
      return;
    }
    session = constructed;
    state = State.WAIT_SESSION_OPEN;
  }
  private static void exact(Map<String, Object> fields, String... names) {
    if (!fields.keySet().equals(Set.of(names))) throw new IllegalArgumentException("schema");
    for (Object value : fields.values()) if (!(value instanceof String)) throw new IllegalArgumentException("type");
  }
  private static String string(Map<String, Object> fields, String name) {
    if (!(fields.get(name) instanceof String value)) throw new IllegalArgumentException("type");
    return value;
  }
  private static Instant timestamp(String raw) {
    Instant value = Instant.parse(raw);
    if (!ControlAuth.formatUtc(value).equals(raw)) throw new IllegalArgumentException("UTC canonical");
    return value;
  }
  private static byte[] json(Map<String, String> fields) {
    StringBuilder result = new StringBuilder("{");
    for (Map.Entry<String, String> entry : fields.entrySet()) {
      if (result.length() > 1) result.append(',');
      result.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
    }
    return result.append('}').toString().getBytes(StandardCharsets.UTF_8);
  }
  private void timer() {
    if (invalid) { if (!cleanup) stop(); return; }
    long value = now();
    synchronized (lock) {
      for (Lane lane : lanes) if (lane.state != LeaseState.FREE && value >= lane.deadline) {
        throw new IllegalArgumentException("assembly timeout");
      }
    }
    if (session != null) session.tick(); else checkDeadline();
  }
  private void erasePsk() { if (psk != null) { Arrays.fill(psk, (byte) 0); psk = null; } }
  private void closeCredential() {
    if (!credentialClosed) {
      credentialClosed = true;
      try { credential.close(); }
      catch (Throwable failure) { markUnknown(); throw failure; }
    }
  }
  private void fenceAndCredential() {
    Throwable first = null;
    if (!fenced) { fenced = true; try { fence.run(); } catch (Throwable failure) { markUnknown(); first = failure; } }
    try { closeCredential(); } catch (Throwable failure) { markUnknown(); first = remember(first, failure); }
    erasePsk();
    rethrow(first);
  }
  private void stop() {
    if (cleanup) return;
    invalid = true; cleanup = true; state = State.STOPPING;
    Throwable first = null;
    if (session != null && !sessionClosed) {
      sessionClosed = true;
      try { session.close(); } catch (Throwable failure) { markUnknown(); first = failure; }
    }
    try { fenceAndCredential(); } catch (Throwable failure) { markUnknown(); first = remember(first, failure); }
    if (callerKeys != null) {
      PostAuthSessionKeys keys = callerKeys; callerKeys = null;
      try { keys.close(); } catch (Throwable failure) { markUnknown(); first = remember(first, failure); }
    }
    for (Lane lane : lanes) release(lane);
    physicalIntent = true;
    if (first != null) { cleanupUnknown = true; reportUnknown(); }
    rethrow(first);
  }
  private void envelope(Runnable operation) {
    requireOwner();
    envelopeDepth++;
    Throwable first = null;
    try { operation.run(); }
    catch (Throwable failure) {
      first = failure;
      try { stop(); } catch (Throwable cleanupFailure) { first = remember(first, cleanupFailure); }
    } finally {
      if (cleanupReportPending) markUnknown();
      if (invalid && !cleanup) {
        try { stop(); } catch (Throwable failure) { first = remember(first, failure); }
      }
      envelopeDepth--;
      if (envelopeDepth == 0) {
        try { flushPhysical(); } catch (Throwable failure) { first = remember(first, failure); }
      }
    }
    rethrow(first);
  }
  private void flushPhysical() {
    if (!physicalIntent) return;
    if (closeDeadline == 0) {
      try { closeDeadline = Math.addExact(now(), 1000); }
      catch (Throwable failure) { abort(socket); state = State.TERMINAL_UNKNOWN; throw failure; }
    }
    if (closeFailed) markUnknown();
    if (closeSettled && inputClosed) { state = State.TERMINAL_API_CLOSED; return; }
    if (now() >= closeDeadline) { abort(socket); state = State.TERMINAL_UNKNOWN; return; }
    if (closeStarted || sends.hasUnsettledPhysicalSend()) return;
    closeStarted = true; phase = Phase.CLOSE;
    if (socket == null) { state = State.TERMINAL_UNKNOWN; return; }
    try {
      CompletionStage<Void> closing = Objects.requireNonNull(socket.close());
      closing.whenComplete((ignored, failure) -> {
        if (failure == null) closeSettled = true;
        else closeFailed = true;
        // Close outcomes do not need a second send cell or an assumed terminal callback.
        mailbox.signalTimer();
      });
    } catch (Throwable failure) { closeFailed = true; markUnknown(); rethrow(failure); }
  }
  private void markUnknown() { cleanupUnknown = true; reportUnknown(); }
  private void reportUnknown() {
    try { diagnostics.cleanupUnknown("connector_cleanup_unknown"); } catch (Throwable ignored) { }
  }
  private static Throwable remember(Throwable first, Throwable next) {
    return first == null || (!(first instanceof Error) && next instanceof Error) ? next : first;
  }
  private static void rethrow(Throwable failure) {
    if (failure instanceof Error error) throw error;
    // Malformed peer input terminates locally; no peer exception graph is retained.
  }
}
