package dev.memos.materialization;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BoundedJsonParser {
  private static final int MAX_DEPTH = 8;
  private static final int MAX_NODES = 1_024;
  private static final int MAX_STRING_CHARACTERS = 16_384;
  private static final int MAX_NUMBER_CHARACTERS = 64;

  private final String input;
  private int position;
  private int nodes;

  BoundedJsonParser(String input) {
    this.input = input;
  }

  Object parse() {
    skipWhitespace();
    Object value = parseValue(0);
    skipWhitespace();
    if (position != input.length()) {
      malformed("trailing data");
    }
    return value;
  }

  private Object parseValue(int depth) {
    if (depth > MAX_DEPTH) {
      fail(ProposalDecodingError.LIMIT_EXCEEDED, "maximum JSON depth exceeded");
    }
    nodes++;
    if (nodes > MAX_NODES) {
      fail(ProposalDecodingError.LIMIT_EXCEEDED, "maximum JSON node count exceeded");
    }
    if (position >= input.length()) {
      malformed("unexpected end of input");
    }
    return switch (input.charAt(position)) {
      case '{' -> parseObject(depth + 1);
      case '[' -> parseArray(depth + 1);
      case '"' -> parseString();
      case 't' -> parseLiteral("true", Boolean.TRUE);
      case 'f' -> parseLiteral("false", Boolean.FALSE);
      case 'n' -> parseLiteral("null", null);
      default -> parseNumber();
    };
  }

  private Map<String, Object> parseObject(int depth) {
    position++;
    skipWhitespace();
    Map<String, Object> object = new LinkedHashMap<>();
    if (consume('}')) {
      return object;
    }
    while (true) {
      if (position >= input.length() || input.charAt(position) != '"') {
        malformed("object key must be a string");
      }
      String key = parseString();
      skipWhitespace();
      require(':');
      skipWhitespace();
      if (object.containsKey(key)) {
        malformed("duplicate object key");
      }
      object.put(key, parseValue(depth));
      skipWhitespace();
      if (consume('}')) {
        return object;
      }
      require(',');
      skipWhitespace();
    }
  }

  private List<Object> parseArray(int depth) {
    position++;
    skipWhitespace();
    List<Object> array = new ArrayList<>();
    if (consume(']')) {
      return array;
    }
    while (true) {
      array.add(parseValue(depth));
      skipWhitespace();
      if (consume(']')) {
        return array;
      }
      require(',');
      skipWhitespace();
    }
  }

  private String parseString() {
    require('"');
    StringBuilder result = new StringBuilder();
    while (position < input.length()) {
      char character = input.charAt(position++);
      if (character == '"') {
        validateSurrogates(result);
        return result.toString();
      }
      if (character == '\\') {
        appendEscape(result);
      } else {
        if (character < 0x20) {
          malformed("unescaped control character");
        }
        result.append(character);
      }
      if (result.length() > MAX_STRING_CHARACTERS) {
        fail(ProposalDecodingError.LIMIT_EXCEEDED, "maximum JSON string length exceeded");
      }
    }
    malformed("unterminated string");
    return "";
  }

  private void appendEscape(StringBuilder result) {
    if (position >= input.length()) {
      malformed("unterminated escape");
    }
    char escape = input.charAt(position++);
    switch (escape) {
      case '"', '\\', '/' -> result.append(escape);
      case 'b' -> result.append('\b');
      case 'f' -> result.append('\f');
      case 'n' -> result.append('\n');
      case 'r' -> result.append('\r');
      case 't' -> result.append('\t');
      case 'u' -> result.append(parseUnicodeEscape());
      default -> malformed("invalid escape");
    }
  }

  private char parseUnicodeEscape() {
    if (position + 4 > input.length()) {
      malformed("incomplete unicode escape");
    }
    int value = 0;
    for (int index = 0; index < 4; index++) {
      int digit = Character.digit(input.charAt(position++), 16);
      if (digit < 0) {
        malformed("invalid unicode escape");
      }
      value = value * 16 + digit;
    }
    return (char) value;
  }

  private static void validateSurrogates(StringBuilder value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new ProposalDecodingException(
              ProposalDecodingError.MALFORMED_JSON, "$", "unpaired unicode surrogate");
        }
        index++;
      } else if (Character.isLowSurrogate(character)) {
        throw new ProposalDecodingException(
            ProposalDecodingError.MALFORMED_JSON, "$", "unpaired unicode surrogate");
      }
    }
  }

  private Object parseLiteral(String literal, Object value) {
    if (!input.startsWith(literal, position)) {
      malformed("invalid literal");
    }
    position += literal.length();
    return value;
  }

  private BigDecimal parseNumber() {
    int start = position;
    consume('-');
    if (consume('0')) {
      if (position < input.length() && Character.isDigit(input.charAt(position))) {
        malformed("leading zero in number");
      }
    } else {
      requireDigits();
    }
    if (consume('.')) {
      requireDigits();
    }
    if (position < input.length()
        && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
      position++;
      if (position < input.length()
          && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
        position++;
      }
      requireDigits();
    }
    String number = input.substring(start, position);
    if (number.length() > MAX_NUMBER_CHARACTERS) {
      fail(ProposalDecodingError.LIMIT_EXCEEDED, "maximum number length exceeded");
    }
    try {
      return new BigDecimal(number);
    } catch (NumberFormatException exception) {
      malformed("invalid number");
      return BigDecimal.ZERO;
    }
  }

  private void requireDigits() {
    int start = position;
    while (position < input.length() && Character.isDigit(input.charAt(position))) {
      position++;
    }
    if (start == position) {
      malformed("number requires a digit");
    }
  }

  private void skipWhitespace() {
    while (position < input.length()) {
      char character = input.charAt(position);
      if (character == ' ' || character == '\n' || character == '\r' || character == '\t') {
        position++;
      } else {
        return;
      }
    }
  }

  private boolean consume(char expected) {
    if (position < input.length() && input.charAt(position) == expected) {
      position++;
      return true;
    }
    return false;
  }

  private void require(char expected) {
    if (!consume(expected)) {
      malformed("expected '" + expected + "'");
    }
  }

  private void malformed(String safeDetail) {
    fail(ProposalDecodingError.MALFORMED_JSON, safeDetail);
  }

  private void fail(ProposalDecodingError error, String safeDetail) {
    throw new ProposalDecodingException(error, "$", safeDetail);
  }
}
