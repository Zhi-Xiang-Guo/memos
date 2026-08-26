package dev.memos.domain.candidate;

public record TextCandidateValue(String value) implements CandidateValue {
  public TextCandidateValue {
    value = CandidateValidation.requireText(value, "value", 4_096);
  }

  @Override
  public String canonicalJson() {
    return '"' + escape(value) + '"';
  }

  private static String escape(String source) {
    StringBuilder escaped = new StringBuilder(source.length());
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }
}
