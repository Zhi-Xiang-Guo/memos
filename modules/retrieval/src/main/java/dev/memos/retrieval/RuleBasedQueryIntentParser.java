package dev.memos.retrieval;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleBasedQueryIntentParser implements QueryIntentParser {
  private static final Pattern ISO_DATE =
      Pattern.compile("(?<![0-9])(\\d{4}-\\d{2}-\\d{2})(?![0-9])");

  @Override
  public QueryIntent parse(String query, Instant explicitTime) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    String normalized = query.toLowerCase(Locale.ROOT);
    TemporalQueryIntent temporal =
        containsAny(
                normalized,
                "changed",
                "change",
                "moved",
                "previous",
                "before and after",
                "变化",
                "改变",
                "搬家",
                "前后")
            ? TemporalQueryIntent.CHANGE
            : containsAny(
                    normalized,
                    "used to",
                    "formerly",
                    "previously",
                    "at that time",
                    "过去",
                    "以前",
                    "当时",
                    "曾经")
                ? TemporalQueryIntent.HISTORICAL
                : TemporalQueryIntent.PRESENT;
    return new QueryIntent(temporal, explicitTime == null ? parsedDate(normalized) : explicitTime);
  }

  private static Instant parsedDate(String query) {
    Matcher matcher = ISO_DATE.matcher(query);
    if (!matcher.find()) {
      return null;
    }
    try {
      return LocalDate.parse(matcher.group(1)).atStartOfDay().toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private static boolean containsAny(String value, String... terms) {
    for (String term : terms) {
      if (value.contains(term)) {
        return true;
      }
    }
    return false;
  }
}
