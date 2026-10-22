package io.github.jinyiwei2012.luosandbox.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrictJsonTest {
  @Test
  void decodesUnicodeNullImmutableContainersAndDistinctNumbers() throws Exception {
    Map<String, Object> document = StrictJson.decodeObject(bytes(
        "{\"中文\":\"😀\",\"none\":null,\"whole\":7,\"fraction\":7.0,\"items\":[null,\"ok\"]}"));

    assertEquals("😀", document.get("中文"));
    assertNull(document.get("none"));
    assertEquals(7L, document.get("whole"));
    assertEquals(new BigDecimal("7.0"), document.get("fraction"));
    assertInstanceOf(BigDecimal.class, document.get("fraction"));
    assertEquals(Arrays.asList(null, "ok"), document.get("items"));
    assertThrows(UnsupportedOperationException.class, () -> document.put("new", "value"));
    assertThrows(UnsupportedOperationException.class, () -> ((List<?>) document.get("items")).clear());
  }

  @Test
  void rejectsInvalidEncodingsBomAndLoneSurrogates() {
    assertReason(StrictJson.Reason.ENCODING, new byte[] {(byte) 0xc3, 0x28});
    assertReason(StrictJson.Reason.ENCODING, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'});
    assertReason(StrictJson.Reason.ENCODING, bytes("{\"x\":\"\\uD800\"}"));
    assertReason(StrictJson.Reason.ENCODING, bytes("{\"x\":\"\\uDC00\"}"));
  }

  @Test
  void rejectsDuplicateNamesExtensionsAndInvalidRoots() {
    assertReason(StrictJson.Reason.MALFORMED, bytes("{\"x\":1,\"x\":2}"));
    assertReason(StrictJson.Reason.MALFORMED, bytes("{\"x\":1,\"\\u0078\":2}"));
    assertReason(StrictJson.Reason.MALFORMED, bytes("{'x':1}"));
    assertReason(StrictJson.Reason.MALFORMED, bytes("{\"x\":1,}"));
    assertReason(StrictJson.Reason.MALFORMED, bytes("{/* no */\"x\":1}"));
    assertReason(StrictJson.Reason.ROOT, bytes("[1]"));
    assertReason(StrictJson.Reason.MALFORMED, bytes("{}{}"));
  }

  @Test
  void enforcesDepthFrameTokenFieldStringAndNumberLimits() throws Exception {
    assertEquals(1, StrictJson.decodeObject(bytes(nestedObject(12))).size());
    assertReason(StrictJson.Reason.LIMIT, bytes(nestedObject(14)));
    assertReason(StrictJson.Reason.LIMIT, bytes(objectWithMembers(65)));
    assertReason(StrictJson.Reason.LIMIT, bytes("{\"x\":\"" + "a".repeat(16_385) + "\"}"));
    assertReason(StrictJson.Reason.LIMIT, bytes("{\"x\":" + "1".repeat(65) + "}"));
    assertReason(StrictJson.Reason.LIMIT, new byte[StrictJson.MAX_FRAME_BYTES + 1]);
    assertReason(StrictJson.Reason.LIMIT, bytes("{\"x\":[" + "0,".repeat(2_048) + "0]}"));
  }

  @Test
  void rejectsIntegerOverflowAndNonfiniteFloatingValues() {
    assertReason(StrictJson.Reason.NUMBER, bytes("{\"x\":9223372036854775808}"));
    assertReason(StrictJson.Reason.NUMBER, bytes("{\"x\":1e400}"));
    assertReason(StrictJson.Reason.MALFORMED, bytes("{\"x\":NaN}"));
    assertReason(StrictJson.Reason.MALFORMED, bytes("{\"x\":Infinity}"));
  }

  private static void assertReason(StrictJson.Reason expected, byte[] input) {
    StrictJson.DecodeException exception = assertThrows(
        StrictJson.DecodeException.class, () -> StrictJson.decodeObject(input));
    assertEquals(expected, exception.reason());
  }

  private static byte[] bytes(String input) {
    return input.getBytes(StandardCharsets.UTF_8);
  }

  private static String nestedObject(int depth) {
    String document = "{}";
    for (int index = 1; index < depth; index++) {
      document = "{\"x\":" + document + "}";
    }
    return document;
  }

  private static String objectWithMembers(int count) {
    StringBuilder document = new StringBuilder("{");
    for (int index = 0; index < count; index++) {
      if (index > 0) {
        document.append(',');
      }
      document.append('\"').append(index).append("\":0");
    }
    return document.append('}').toString();
  }
}
