package dev.memos.materialization;

import java.io.Serial;
import java.util.Objects;

public final class ProposalDecodingException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  private final ProposalDecodingError error;
  private final String path;

  public ProposalDecodingException(ProposalDecodingError error, String path, String safeDetail) {
    super(Objects.requireNonNull(safeDetail, "safeDetail must not be null"));
    this.error = Objects.requireNonNull(error, "error must not be null");
    this.path = MaterializationTextValidation.requireText(path, "path", 256);
  }

  public ProposalDecodingError error() {
    return error;
  }

  public String path() {
    return path;
  }
}
