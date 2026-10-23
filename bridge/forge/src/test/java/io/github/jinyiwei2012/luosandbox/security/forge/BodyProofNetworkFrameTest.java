package io.github.jinyiwei2012.luosandbox.security.forge;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class BodyProofNetworkFrameTest {
  @Test void rawRemainingBytesHaveNoInnerPrefixAndNoRetainedBuffer() {
    for (int size : new int[] {1, 4096}) {
      byte[] raw = new byte[size];
      raw[0] = '{';
      FriendlyByteBuf source = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
      BodyProofNetworkFrame frame;
      try {
        frame = BodyProofNetworkFrame.decode(source);
        assertEquals(0, source.readableBytes());
      } finally { source.release(); }
      assertFalse(frame.malformed());
      assertArrayEquals(raw, frame.raw());
      FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
      try {
        frame.encode(output);
        assertEquals(size, output.readableBytes());
        assertEquals('{', output.readByte());
      } finally { output.release(); }
    }
  }

  @Test void emptyOversizeAndReadFailureRetainNoPayload() {
    for (int size : new int[] {0, 4097}) {
      FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[size]));
      try {
        var frame = BodyProofNetworkFrame.decode(input);
        assertTrue(frame.malformed());
        assertNull(frame.raw());
        assertEquals(size, input.readableBytes());
        assertThrows(IllegalArgumentException.class, () -> frame.encode(input));
      } finally { input.release(); }
    }
    assertTrue(BodyProofNetworkFrame.decode(null).malformed());
    FriendlyByteBuf released = new FriendlyByteBuf(Unpooled.buffer());
    released.release();
    assertTrue(BodyProofNetworkFrame.decode(released).malformed());
  }

  @Test void defensiveCopiesAndRedactedRepresentation() {
    byte[] raw = {'s', 'e', 'c', 'r', 'e', 't'};
    var frame = BodyProofNetworkFrame.raw(raw);
    raw[0] = 'X';
    frame.raw()[0] = 'Y';
    assertEquals('s', frame.raw()[0]);
    assertEquals("BodyProofNetworkFrame[RAW]", frame.toString());
    assertThrows(IllegalArgumentException.class, () -> BodyProofNetworkFrame.raw(new byte[0]));
  }
}
