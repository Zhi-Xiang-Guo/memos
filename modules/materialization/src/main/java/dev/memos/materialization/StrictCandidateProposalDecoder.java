package dev.memos.materialization;

import dev.memos.domain.candidate.BooleanCandidateValue;
import dev.memos.domain.candidate.CandidateRelation;
import dev.memos.domain.candidate.CandidateRelationType;
import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.CandidateValue;
import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.NumberCandidateValue;
import dev.memos.domain.candidate.ProposalDecision;
import dev.memos.domain.candidate.ProposedTimeRange;
import dev.memos.domain.candidate.SensitivityCategory;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TemporalPrecision;
import dev.memos.domain.candidate.TextCandidateValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StrictCandidateProposalDecoder implements CandidateProposalDecoder {
  public static final String SCHEMA_VERSION = "memory-candidate.v1";

  private static final int MAX_PAYLOAD_BYTES = 65_536;
  private static final int MAX_CANDIDATES = 16;

  @Override
  public DecodedCandidateBatch decode(String rawJson, String expectedSchemaVersion) {
    if (rawJson == null) {
      throw failure(ProposalDecodingError.INVALID_TYPE, "$", "response must not be null");
    }
    if (rawJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
      throw failure(
          ProposalDecodingError.PAYLOAD_TOO_LARGE, "$", "response exceeds the maximum byte size");
    }
    if (!SCHEMA_VERSION.equals(expectedSchemaVersion)) {
      throw failure(
          ProposalDecodingError.SCHEMA_VERSION_MISMATCH,
          "$.schema_version",
          "decoder does not support the requested schema version");
    }

    Map<String, Object> root = object(new BoundedJsonParser(rawJson).parse(), "$");
    requireExactFields(root, Set.of("schema_version", "candidates"), Set.of(), "$");
    String schemaVersion = string(required(root, "schema_version", "$"), "$.schema_version", 128);
    if (!expectedSchemaVersion.equals(schemaVersion)) {
      throw failure(
          ProposalDecodingError.SCHEMA_VERSION_MISMATCH,
          "$.schema_version",
          "response schema version does not match the request");
    }

    List<Object> encodedCandidates = array(required(root, "candidates", "$"), "$.candidates");
    if (encodedCandidates.size() > MAX_CANDIDATES) {
      throw failure(
          ProposalDecodingError.LIMIT_EXCEEDED, "$.candidates", "too many candidate proposals");
    }
    List<MemoryCandidateProposal> candidates = new ArrayList<>(encodedCandidates.size());
    for (int index = 0; index < encodedCandidates.size(); index++) {
      candidates.add(candidate(encodedCandidates.get(index), "$.candidates[" + index + "]"));
    }
    return new DecodedCandidateBatch(schemaVersion, candidates);
  }

  private static MemoryCandidateProposal candidate(Object value, String path) {
    Map<String, Object> candidate = object(value, path);
    Set<String> required =
        Set.of(
            "proposed_decision",
            "memory_type",
            "subject",
            "predicate",
            "value",
            "normalized_content",
            "importance",
            "confidence",
            "sensitivity",
            "event_time",
            "valid_interval",
            "candidate_relations");
    Set<String> optional = Set.of();
    requireExactFields(candidate, required, optional, path);
    return new MemoryCandidateProposal(
        enumeration(
            ProposalDecision.class,
            required(candidate, "proposed_decision", path),
            path + ".proposed_decision"),
        enumeration(
            MemoryType.class, required(candidate, "memory_type", path), path + ".memory_type"),
        subject(required(candidate, "subject", path), path + ".subject"),
        string(required(candidate, "predicate", path), path + ".predicate", 128),
        candidateValue(required(candidate, "value", path), path + ".value"),
        string(
            required(candidate, "normalized_content", path), path + ".normalized_content", 8_192),
        nullableTimeRange(required(candidate, "event_time", path), path + ".event_time"),
        nullableTimeRange(required(candidate, "valid_interval", path), path + ".valid_interval"),
        unitDecimal(required(candidate, "importance", path), path + ".importance"),
        unitDecimal(required(candidate, "confidence", path), path + ".confidence"),
        enumSet(
            SensitivityCategory.class,
            required(candidate, "sensitivity", path),
            path + ".sensitivity",
            8),
        relations(required(candidate, "candidate_relations", path), path + ".candidate_relations"));
  }

  private static CandidateSubject subject(Object value, String path) {
    Map<String, Object> subject = object(value, path);
    requireExactFields(subject, Set.of("kind", "label"), Set.of(), path);
    SubjectKind kind =
        enumeration(SubjectKind.class, required(subject, "kind", path), path + ".kind");
    String label = nullableString(required(subject, "label", path), path + ".label", 500);
    try {
      return new CandidateSubject(kind, label);
    } catch (IllegalArgumentException exception) {
      throw failure(ProposalDecodingError.INVALID_RANGE, path, "invalid subject");
    }
  }

  private static CandidateValue candidateValue(Object value, String path) {
    if (value instanceof String text) {
      return new TextCandidateValue(string(text, path, 4_096));
    }
    if (value instanceof Boolean bool) {
      return new BooleanCandidateValue(bool);
    }
    if (value instanceof BigDecimal number) {
      try {
        return new NumberCandidateValue(number);
      } catch (IllegalArgumentException exception) {
        throw failure(ProposalDecodingError.INVALID_RANGE, path, "number exceeds its bounds");
      }
    }
    throw failure(
        ProposalDecodingError.INVALID_TYPE, path, "value must be a string, boolean, or number");
  }

  private static ProposedTimeRange nullableTimeRange(Object encoded, String path) {
    if (encoded == null) {
      return null;
    }
    Map<String, Object> range = object(encoded, path);
    requireExactFields(
        range, Set.of("original_text", "start", "end", "precision", "confidence"), Set.of(), path);
    String text =
        nullableString(required(range, "original_text", path), path + ".original_text", 256);
    Instant start = nullableInstant(required(range, "start", path), path + ".start");
    Instant end = nullableInstant(required(range, "end", path), path + ".end");
    TemporalPrecision precision =
        enumeration(
            TemporalPrecision.class, required(range, "precision", path), path + ".precision");
    double confidence = unitDecimal(required(range, "confidence", path), path + ".confidence");
    try {
      return new ProposedTimeRange(text, start, end, precision, confidence);
    } catch (IllegalArgumentException exception) {
      throw failure(ProposalDecodingError.INVALID_RANGE, path, "invalid temporal range");
    }
  }

  private static String nullableString(Object value, String path, int maximumLength) {
    return value == null ? null : string(value, path, maximumLength);
  }

  private static Instant nullableInstant(Object value, String path) {
    if (value == null) {
      return null;
    }
    String encoded = string(value, path, 64);
    try {
      return Instant.parse(encoded);
    } catch (DateTimeParseException exception) {
      throw failure(
          ProposalDecodingError.INVALID_TIME, path, "time boundary must be an ISO-8601 instant");
    }
  }

  private static List<CandidateRelation> relations(Object value, String path) {
    List<Object> array = array(value, path);
    if (array.size() > 8) {
      throw failure(ProposalDecodingError.LIMIT_EXCEEDED, path, "array exceeds its size limit");
    }
    List<CandidateRelation> relations = new ArrayList<>(array.size());
    for (int index = 0; index < array.size(); index++) {
      String relationPath = path + "[" + index + "]";
      Map<String, Object> relation = object(array.get(index), relationPath);
      requireExactFields(
          relation, Set.of("type", "target_subject", "target_predicate"), Set.of(), relationPath);
      CandidateRelation decoded =
          new CandidateRelation(
              enumeration(
                  CandidateRelationType.class,
                  required(relation, "type", relationPath),
                  relationPath + ".type"),
              string(
                  required(relation, "target_subject", relationPath),
                  relationPath + ".target_subject",
                  256),
              string(
                  required(relation, "target_predicate", relationPath),
                  relationPath + ".target_predicate",
                  128));
      if (relations.contains(decoded)) {
        throw failure(ProposalDecodingError.INVALID_RANGE, path, "relations must be unique");
      }
      relations.add(decoded);
    }
    return List.copyOf(relations);
  }

  private static <E extends Enum<E>> Set<E> enumSet(
      Class<E> type, Object value, String path, int maximumSize) {
    List<Object> array = array(value, path);
    if (array.size() > maximumSize) {
      throw failure(ProposalDecodingError.LIMIT_EXCEEDED, path, "array exceeds its size limit");
    }
    EnumSet<E> result = EnumSet.noneOf(type);
    for (int index = 0; index < array.size(); index++) {
      E decoded = enumeration(type, array.get(index), path + "[" + index + "]");
      if (!result.add(decoded)) {
        throw failure(ProposalDecodingError.INVALID_RANGE, path, "array values must be unique");
      }
    }
    return result;
  }

  private static <E extends Enum<E>> E enumeration(Class<E> type, Object value, String path) {
    String encoded = string(value, path, 64);
    try {
      return Enum.valueOf(type, encoded);
    } catch (IllegalArgumentException exception) {
      throw failure(ProposalDecodingError.INVALID_ENUM, path, "unsupported enum value");
    }
  }

  private static double unitDecimal(Object value, String path) {
    if (!(value instanceof BigDecimal decimal)) {
      throw failure(ProposalDecodingError.INVALID_TYPE, path, "value must be a number");
    }
    if (decimal.compareTo(BigDecimal.ZERO) < 0 || decimal.compareTo(BigDecimal.ONE) > 0) {
      throw failure(ProposalDecodingError.INVALID_RANGE, path, "value must be between 0 and 1");
    }
    return decimal.doubleValue();
  }

  private static String string(Object value, String path, int maximumLength) {
    if (!(value instanceof String string)) {
      throw failure(ProposalDecodingError.INVALID_TYPE, path, "value must be a string");
    }
    if (string.isBlank()) {
      throw failure(ProposalDecodingError.INVALID_RANGE, path, "string must not be blank");
    }
    if (string.length() > maximumLength) {
      throw failure(ProposalDecodingError.LIMIT_EXCEEDED, path, "string exceeds its size limit");
    }
    return string;
  }

  private static Object required(Map<String, Object> object, String field, String path) {
    if (!object.containsKey(field)) {
      throw failure(
          ProposalDecodingError.MISSING_FIELD, path + "." + field, "required field is missing");
    }
    return object.get(field);
  }

  private static Map<String, Object> object(Object value, String path) {
    if (!(value instanceof Map<?, ?> encoded)) {
      throw failure(ProposalDecodingError.INVALID_TYPE, path, "value must be an object");
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : encoded.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw failure(ProposalDecodingError.INVALID_TYPE, path, "object key must be a string");
      }
      copy.put(key, entry.getValue());
    }
    return copy;
  }

  private static List<Object> array(Object value, String path) {
    if (!(value instanceof List<?> encoded)) {
      throw failure(ProposalDecodingError.INVALID_TYPE, path, "value must be an array");
    }
    return new ArrayList<>(encoded);
  }

  private static void requireExactFields(
      Map<String, Object> object, Set<String> required, Set<String> optional, String path) {
    for (String field : required) {
      if (!object.containsKey(field)) {
        throw failure(
            ProposalDecodingError.MISSING_FIELD, path + "." + field, "required field is missing");
      }
    }
    for (String field : object.keySet()) {
      if (!required.contains(field) && !optional.contains(field)) {
        throw failure(
            ProposalDecodingError.UNKNOWN_FIELD,
            path + "." + field,
            "unknown field is not permitted");
      }
    }
  }

  private static ProposalDecodingException failure(
      ProposalDecodingError error, String path, String detail) {
    return new ProposalDecodingException(error, path, detail);
  }
}
