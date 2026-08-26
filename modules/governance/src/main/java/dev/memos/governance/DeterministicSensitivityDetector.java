package dev.memos.governance;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.SensitivityCategory;
import dev.memos.domain.candidate.TextCandidateValue;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class DeterministicSensitivityDetector implements SensitivityDetector {
  private static final Pattern EMAIL =
      Pattern.compile("(?i)[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}");
  private static final Pattern PHONE =
      Pattern.compile(
          "(?<![0-9-])(?:\\+[0-9]{7,15}|\\+?[0-9]{1,3}[ ()-][0-9]{2,4}[ ()-][0-9]{3,4}[ -][0-9]{3,4})(?![0-9-])");
  private static final Pattern SECRET_MARKER =
      Pattern.compile(
          "(?i)(password|api[_. -]?key|auth[_. -]?secret|credential|access[_. -]?token)");

  @Override
  public Set<SensitivityCategory> detect(MemoryCandidateProposal proposal) {
    EnumSet<SensitivityCategory> detected = EnumSet.noneOf(SensitivityCategory.class);
    String predicate = proposal.predicate().toLowerCase(Locale.ROOT);
    String content = proposal.normalizedContent();
    String textValue = proposal.value() instanceof TextCandidateValue text ? text.value() : "";
    String combined = predicate + "\n" + textValue + "\n" + content;
    if (SECRET_MARKER.matcher(combined).find()) {
      detected.add(SensitivityCategory.AUTH_SECRET);
    }
    if (EMAIL.matcher(combined).find() || PHONE.matcher(combined).find()) {
      detected.add(SensitivityCategory.CONTACT);
    }
    return Set.copyOf(detected);
  }
}
