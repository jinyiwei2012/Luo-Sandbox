package io.github.jinyiwei2012.luosandbox.wire;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Bounded JSON document decoder for wire fixtures. It is not a protocol or authorization layer. */
public final class StrictJson {
  public static final int MAX_FRAME_BYTES = 65_536;
  public static final int MAX_DEPTH = 12;
  public static final int MAX_TOKENS = 2_048;
  public static final int MAX_FIELD_NAMES = 64;
  public static final int MAX_STRING_LENGTH = 16_384;
  public static final int MAX_NUMBER_LENGTH = 64;

  private static final JsonFactory FACTORY = JsonFactory.builder()
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
      .disable(JsonFactory.Feature.CHARSET_DETECTION)
      .build();

  private StrictJson() {}

  /** Decodes one UTF-8 object document into immutable maps and lists. */
  public static Map<String, Object> decodeObject(byte[] frame) throws DecodeException {
    if (frame == null) {
      throw failure(Reason.MALFORMED, "Frame is required");
    }
    if (frame.length > MAX_FRAME_BYTES) {
      throw failure(Reason.LIMIT, "Frame exceeds the byte limit");
    }
    String input = decodeUtf8(frame);
    try (JsonParser parser = FACTORY.createParser(input)) {
      Reader reader = new Reader(parser);
      JsonToken first = reader.next();
      if (first != JsonToken.START_OBJECT) {
        throw failure(Reason.ROOT, "Root value must be an object");
      }
      Map<String, Object> result = reader.object(1);
      if (reader.next() != null) {
        throw failure(Reason.MALFORMED, "Document has trailing content");
      }
      return result;
    } catch (DecodeException exception) {
      throw exception;
    } catch (StreamConstraintsException exception) {
      throw failure(Reason.LIMIT, "Document exceeds a parser limit");
    } catch (IOException | RuntimeException exception) {
      throw failure(Reason.MALFORMED, "Invalid JSON document");
    }
  }

  private static String decodeUtf8(byte[] frame) throws DecodeException {
    if (startsWithBom(frame)) {
      throw failure(Reason.ENCODING, "UTF-8 BOM is not allowed");
    }
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      String text = decoder.decode(ByteBuffer.wrap(frame)).toString();
      ensureNoLoneSurrogates(text);
      return text;
    } catch (CharacterCodingException exception) {
      throw failure(Reason.ENCODING, "Input must be valid UTF-8");
    }
  }

  private static boolean startsWithBom(byte[] bytes) {
    return bytes.length >= 3
        && bytes[0] == (byte) 0xef
        && bytes[1] == (byte) 0xbb
        && bytes[2] == (byte) 0xbf;
  }

  private static void ensureNoLoneSurrogates(String value) throws DecodeException {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
          throw failure(Reason.ENCODING, "String has an unpaired surrogate");
        }
      } else if (Character.isLowSurrogate(current)) {
        throw failure(Reason.ENCODING, "String has an unpaired surrogate");
      }
    }
  }

  private static final class Reader {
    private final JsonParser parser;
    private int tokens;
    private int fieldNames;

    Reader(JsonParser parser) {
      this.parser = parser;
    }

    JsonToken next() throws IOException, DecodeException {
      JsonToken token = parser.nextToken();
      if (token != null) {
        if (++tokens > MAX_TOKENS) {
          throw failure(Reason.LIMIT, "Document exceeds the token limit");
        }
      }
      return token;
    }

    Map<String, Object> object(int depth) throws IOException, DecodeException {
      checkDepth(depth);
      Map<String, Object> values = new LinkedHashMap<>();
      while (next() != JsonToken.END_OBJECT) {
        if (parser.currentToken() != JsonToken.FIELD_NAME) {
          throw failure(Reason.MALFORMED, "Object member name is required");
        }
        if (++fieldNames > MAX_FIELD_NAMES) {
          throw failure(Reason.LIMIT, "Document exceeds the field-name limit");
        }
        String name = checkedString(parser.currentName());
        JsonToken valueToken = next();
        if (valueToken == null) {
          throw failure(Reason.MALFORMED, "Object member value is required");
        }
        values.put(name, value(valueToken, depth));
      }
      return Collections.unmodifiableMap(values);
    }

    List<Object> array(int depth) throws IOException, DecodeException {
      checkDepth(depth);
      java.util.ArrayList<Object> values = new java.util.ArrayList<>();
      JsonToken token;
      while ((token = next()) != JsonToken.END_ARRAY) {
        if (token == null) {
          throw failure(Reason.MALFORMED, "Array is not closed");
        }
        values.add(value(token, depth));
      }
      return Collections.unmodifiableList(values);
    }

    Object value(JsonToken token, int parentDepth) throws IOException, DecodeException {
      return switch (token) {
        case START_OBJECT -> object(parentDepth + 1);
        case START_ARRAY -> array(parentDepth + 1);
        case VALUE_STRING -> checkedString(parser.getText());
        case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> number(token);
        case VALUE_TRUE -> Boolean.TRUE;
        case VALUE_FALSE -> Boolean.FALSE;
        case VALUE_NULL -> null;
        default -> throw failure(Reason.MALFORMED, "Unexpected JSON token");
      };
    }

    Object number(JsonToken token) throws IOException, DecodeException {
      String lexical = parser.getText();
      if (lexical.length() > MAX_NUMBER_LENGTH) {
        throw failure(Reason.LIMIT, "Number exceeds the lexical limit");
      }
      try {
        if (token == JsonToken.VALUE_NUMBER_INT) {
          return Long.valueOf(lexical);
        }
        BigDecimal decimal = new BigDecimal(lexical);
        if (!Double.isFinite(decimal.doubleValue())) {
          throw failure(Reason.NUMBER, "Number is not finite as a double");
        }
        return decimal;
      } catch (NumberFormatException exception) {
        throw failure(Reason.NUMBER, "Number is outside the supported range");
      }
    }

    private String checkedString(String value) throws DecodeException {
      if (value.length() > MAX_STRING_LENGTH) {
        throw failure(Reason.LIMIT, "String exceeds the character limit");
      }
      ensureNoLoneSurrogates(value);
      return value;
    }

    private static void checkDepth(int depth) throws DecodeException {
      if (depth > MAX_DEPTH) {
        throw failure(Reason.LIMIT, "Document exceeds the nesting limit");
      }
    }
  }

  public enum Reason {
    ENCODING,
    ROOT,
    LIMIT,
    NUMBER,
    MALFORMED
  }

  /** Sanitized lexical failure only; no raw document text or source location is exposed. */
  public static final class DecodeException extends Exception {
    private final Reason reason;

    private DecodeException(Reason reason, String message) {
      super(message);
      this.reason = reason;
    }

    public Reason reason() {
      return reason;
    }
  }

  private static DecodeException failure(Reason reason, String message) {
    return new DecodeException(reason, message);
  }
}
