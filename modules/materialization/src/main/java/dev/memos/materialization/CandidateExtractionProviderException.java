package dev.memos.materialization;

/** Provider failure translated into durable extraction retry/dead semantics. */
public final class CandidateExtractionProviderException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final JobFailureKind kind;
  private final transient JobErrorClass errorClass;

  private CandidateExtractionProviderException(
      JobFailureKind kind, JobErrorClass errorClass, Throwable cause) {
    super(errorClass.value(), cause);
    this.kind = kind;
    this.errorClass = errorClass;
  }

  public static CandidateExtractionProviderException transientFailure(
      String errorClass, Throwable cause) {
    return new CandidateExtractionProviderException(
        JobFailureKind.TRANSIENT, new JobErrorClass(errorClass), cause);
  }

  public static CandidateExtractionProviderException permanentFailure(
      String errorClass, Throwable cause) {
    return new CandidateExtractionProviderException(
        JobFailureKind.PERMANENT, new JobErrorClass(errorClass), cause);
  }

  public JobFailureKind kind() {
    return kind;
  }

  public JobErrorClass errorClass() {
    return errorClass;
  }
}
