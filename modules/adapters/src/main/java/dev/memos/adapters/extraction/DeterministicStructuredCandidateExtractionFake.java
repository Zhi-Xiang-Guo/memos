package dev.memos.adapters.extraction;

import dev.memos.materialization.CandidateExtractionRequest;
import dev.memos.materialization.ProviderCallMetadata;
import dev.memos.materialization.ProviderTokenUsage;
import dev.memos.materialization.RawExtractionResponse;
import dev.memos.materialization.StructuredCandidateExtractionPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A deterministic, credential-free structured extraction adapter for tests and local startup. */
public final class DeterministicStructuredCandidateExtractionFake
    implements StructuredCandidateExtractionPort {
  public static final String PROVIDER = "fake";
  public static final String DEFAULT_MODEL_VERSION = "deterministic-fixture-v1";
  public static final String DEFAULT_PROMPT_VERSION = "candidate-extraction-v1";
  public static final String DEFAULT_SCHEMA_VERSION = "memory-candidate.v1";
  public static final String DURABLE_PREFERENCE_SOURCE = "I prefer a dark editor theme.";

  private static final int MAX_RAW_JSON_BYTES = 65_536;
  private static final String EMPTY_RESPONSE =
      "{\"schema_version\":\"memory-candidate.v1\",\"candidates\":[]}";
  private static final String DURABLE_PREFERENCE_RESPONSE =
      """
      {"schema_version":"memory-candidate.v1","candidates":[{"proposed_decision":"REMEMBER","memory_type":"SEMANTIC","subject":{"kind":"USER","label":null},"predicate":"preference.editor.theme","value":"dark","normalized_content":"The user prefers a dark editor theme.","event_time":null,"valid_interval":null,"importance":0.7,"confidence":0.96,"sensitivity":["NONE"],"candidate_relations":[]}]}
      """
          .strip();

  private final String modelVersion;
  private final String promptVersion;
  private final String schemaVersion;
  private final Map<String, String> scriptedResponsesByContent;

  public DeterministicStructuredCandidateExtractionFake() {
    this(
        DEFAULT_MODEL_VERSION,
        DEFAULT_PROMPT_VERSION,
        DEFAULT_SCHEMA_VERSION,
        Map.of(DURABLE_PREFERENCE_SOURCE, DURABLE_PREFERENCE_RESPONSE));
  }

  public DeterministicStructuredCandidateExtractionFake(
      String modelVersion,
      String promptVersion,
      String schemaVersion,
      Map<String, String> scriptedResponsesByContent) {
    this.modelVersion = requireText(modelVersion, "modelVersion");
    this.promptVersion = requireText(promptVersion, "promptVersion");
    this.schemaVersion = requireText(schemaVersion, "schemaVersion");
    Objects.requireNonNull(
        scriptedResponsesByContent, "scriptedResponsesByContent must not be null");
    this.scriptedResponsesByContent = Map.copyOf(scriptedResponsesByContent);
    for (Map.Entry<String, String> entry : this.scriptedResponsesByContent.entrySet()) {
      requireText(entry.getKey(), "script content");
      requireBoundedJson(entry.getValue());
    }
  }

  @Override
  public RawExtractionResponse extract(CandidateExtractionRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    assertNoActiveTransaction();
    if (!promptVersion.equals(request.promptVersion())) {
      throw new IllegalArgumentException("request prompt version does not match the fake adapter");
    }
    if (!schemaVersion.equals(request.schemaVersion())) {
      throw new IllegalArgumentException("request schema version does not match the fake adapter");
    }
    String rawJson = scriptedResponsesByContent.getOrDefault(request.content(), emptyResponse());
    return new RawExtractionResponse(
        rawJson,
        new ProviderCallMetadata(
            PROVIDER,
            modelVersion,
            promptVersion,
            schemaVersion,
            deterministicCallId(request),
            new ProviderTokenUsage(0, 0),
            Duration.ZERO));
  }

  private String emptyResponse() {
    if (DEFAULT_SCHEMA_VERSION.equals(schemaVersion)) {
      return EMPTY_RESPONSE;
    }
    return "{\"schema_version\":\"" + jsonEscape(schemaVersion) + "\",\"candidates\":[]}";
  }

  private String deterministicCallId(CandidateExtractionRequest request) {
    String identity =
        request.sourceEventId() + "\n" + modelVersion + "\n" + promptVersion + "\n" + schemaVersion;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
      return "fake-" + HexFormat.of().formatHex(digest, 0, 16);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void assertNoActiveTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "structured extraction must run outside a database transaction");
    }
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String requireBoundedJson(String value) {
    requireText(value, "scripted response");
    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RAW_JSON_BYTES) {
      throw new IllegalArgumentException("scripted response exceeds 65536 UTF-8 bytes");
    }
    return value;
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
