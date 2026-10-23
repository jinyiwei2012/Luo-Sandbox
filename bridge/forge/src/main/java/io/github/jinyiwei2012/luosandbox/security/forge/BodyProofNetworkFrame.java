package io.github.jinyiwei2012.luosandbox.security.forge;

import net.minecraft.network.FriendlyByteBuf;

/** Raw remaining JSON bytes, with no inner length prefix and no retained ByteBuf ownership. */
public final class BodyProofNetworkFrame {
  private static final BodyProofNetworkFrame MALFORMED = new BodyProofNetworkFrame(null);
  private final byte[] raw;

  private BodyProofNetworkFrame(byte[] raw) { this.raw = raw == null ? null : raw.clone(); }

  public static BodyProofNetworkFrame raw(byte[] raw) {
    if (raw == null || raw.length < 1 || raw.length > 4096) {
      throw new IllegalArgumentException("Invalid BODY frame size");
    }
    return new BodyProofNetworkFrame(raw);
  }

  /** Invalid-size buffers may remain unread: the rejected frame retains none of their payload. */
  public static BodyProofNetworkFrame decode(FriendlyByteBuf buffer) {
    try {
      int size = buffer.readableBytes();
      if (size < 1 || size > 4096) return MALFORMED;
      byte[] bytes = new byte[size];
      buffer.readBytes(bytes);
      if (buffer.readableBytes() != 0) return MALFORMED;
      return new BodyProofNetworkFrame(bytes);
    } catch (RuntimeException error) {
      return MALFORMED;
    }
  }

  public void encode(FriendlyByteBuf buffer) {
    if (raw == null) throw new IllegalArgumentException("Cannot encode malformed BODY frame");
    buffer.writeBytes(raw);
  }

  public boolean malformed() { return raw == null; }
  public byte[] raw() { return raw == null ? null : raw.clone(); }
  @Override public String toString() { return "BodyProofNetworkFrame[" + (malformed() ? "MALFORMED" : "RAW") + "]"; }
}
