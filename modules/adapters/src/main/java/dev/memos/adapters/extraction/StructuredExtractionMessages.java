package dev.memos.adapters.extraction;

import dev.memos.materialization.CandidateExtractionRequest;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class StructuredExtractionMessages {
  private StructuredExtractionMessages() {}

  static List<Map<String, String>> create(
      StructuredExtractionResources resources, CandidateExtractionRequest request) {
    return List.of(
        Map.of("role", "system", "content", resources.prompt()),
        Map.of("role", "user", "content", userMessage(request)));
  }

  private static String userMessage(CandidateExtractionRequest request) {
    StringBuilder message = new StringBuilder();
    message.append("source_event_id: ").append(request.sourceEventId()).append('\n');
    request.metadata().entrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .forEach(
            entry ->
                message
                    .append("metadata.")
                    .append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue())
                    .append('\n'));
    message.append("<untrusted_source_content>\n");
    message.append(request.content());
    message.append("\n</untrusted_source_content>");
    return message.toString();
  }
}
