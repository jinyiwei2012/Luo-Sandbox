package io.github.jinyiwei2012.luosandbox.security.forge;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class BodyChallengeNetworkFrameTest {
  @Test void boundedRawDefensiveNoPrefixAndNoBufferRetention() {
    for (int size : new int[] {1, 4096}) {
      byte[] raw = new byte[size]; raw[0] = '{';
      var input = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
      var frame = BodyChallengeNetworkFrame.decode(input);
      assertEquals(0, input.readableBytes()); input.release();
      raw[0] = 'X'; frame.raw()[0] = 'Y';
      assertEquals('{', frame.raw()[0]);
      var output = new FriendlyByteBuf(Unpooled.buffer());
      try { frame.encode(output); assertEquals(size, output.readableBytes()); assertEquals('{', output.readByte()); }
      finally { output.release(); }
    }
  }

  @Test void malformedRetainsNoPayloadAndCannotEncode() {
    for (int size : new int[] {0, 4097}) {
      var input = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[size]));
      try {
        var frame = BodyChallengeNetworkFrame.decode(input);
        assertTrue(frame.malformed()); assertNull(frame.raw());
        assertEquals(size, input.readableBytes());
        assertThrows(IllegalArgumentException.class, () -> frame.encode(input));
      } finally { input.release(); }
    }
    assertTrue(BodyChallengeNetworkFrame.decode(null).malformed());
    assertEquals("BodyChallengeNetworkFrame[RAW]", BodyChallengeNetworkFrame.raw(new byte[] {1}).toString());
    assertThrows(IllegalArgumentException.class, () -> BodyChallengeNetworkFrame.raw(new byte[4097]));
  }
}
