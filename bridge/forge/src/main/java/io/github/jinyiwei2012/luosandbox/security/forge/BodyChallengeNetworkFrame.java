package io.github.jinyiwei2012.luosandbox.security.forge;

import net.minecraft.network.FriendlyByteBuf;

/** Separate S2C message type: raw remaining JSON, never an inner length-prefixed envelope. */
public final class BodyChallengeNetworkFrame {
  private static final BodyChallengeNetworkFrame MALFORMED = new BodyChallengeNetworkFrame(null);
  private final byte[] raw;

  private BodyChallengeNetworkFrame(byte[] raw) { this.raw = raw == null ? null : raw.clone(); }

  public static BodyChallengeNetworkFrame raw(byte[] raw) {
    if (raw == null || raw.length < 1 || raw.length > 4096) {
      throw new IllegalArgumentException("Invalid BODY challenge frame size");
    }
    return new BodyChallengeNetworkFrame(raw);
  }

  /** Rejected buffers may remain unread; no invalid payload or ByteBuf is retained. */
  public static BodyChallengeNetworkFrame decode(FriendlyByteBuf buffer) {
    try {
      int size = buffer.readableBytes();
      if (size < 1 || size > 4096) return MALFORMED;
      byte[] bytes = new byte[size];
      buffer.readBytes(bytes);
      return buffer.readableBytes() == 0 ? new BodyChallengeNetworkFrame(bytes) : MALFORMED;
    } catch (RuntimeException error) { return MALFORMED; }
  }

  public void encode(FriendlyByteBuf buffer) {
    if (raw == null) throw new IllegalArgumentException("Cannot encode malformed BODY challenge");
    buffer.writeBytes(raw);
  }

  public boolean malformed() { return raw == null; }
  public byte[] raw() { return raw == null ? null : raw.clone(); }
  @Override public String toString() {
    return "BodyChallengeNetworkFrame[" + (malformed() ? "MALFORMED" : "RAW") + "]";
  }
}
