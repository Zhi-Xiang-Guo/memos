package dev.memos.materialization;

/** Provider failure translated into durable worker retry/dead semantics. */
public final class ProjectionEmbeddingProviderException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final JobFailureKind kind;
  private final transient JobErrorClass errorClass;

  private ProjectionEmbeddingProviderException(
      JobFailureKind kind, JobErrorClass errorClass, Throwable cause) {
    super(errorClass.value(), cause);
    this.kind = kind;
    this.errorClass = errorClass;
  }

  public static ProjectionEmbeddingProviderException transientFailure(
      String errorClass, Throwable cause) {
    return new ProjectionEmbeddingProviderException(
        JobFailureKind.TRANSIENT, new JobErrorClass(errorClass), cause);
  }

  public static ProjectionEmbeddingProviderException permanentFailure(
      String errorClass, Throwable cause) {
    return new ProjectionEmbeddingProviderException(
        JobFailureKind.PERMANENT, new JobErrorClass(errorClass), cause);
  }

  public JobFailureKind kind() {
    return kind;
  }

  public JobErrorClass errorClass() {
    return errorClass;
  }
}
