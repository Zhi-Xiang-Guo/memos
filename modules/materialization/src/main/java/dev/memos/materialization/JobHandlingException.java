package dev.memos.materialization;

import java.io.Serial;
import java.util.Objects;

public final class JobHandlingException extends Exception {
  @Serial private static final long serialVersionUID = 1L;

  private final JobFailureKind kind;
  private final String errorClass;

  private JobHandlingException(JobFailureKind kind, JobErrorClass errorClass) {
    super(errorClass.toString());
    this.kind = Objects.requireNonNull(kind, "kind must not be null");
    this.errorClass = Objects.requireNonNull(errorClass, "errorClass must not be null").value();
  }

  public static JobHandlingException transientFailure(String errorClass) {
    return new JobHandlingException(JobFailureKind.TRANSIENT, new JobErrorClass(errorClass));
  }

  public static JobHandlingException permanentFailure(String errorClass) {
    return new JobHandlingException(JobFailureKind.PERMANENT, new JobErrorClass(errorClass));
  }

  public JobFailureKind kind() {
    return kind;
  }

  public JobErrorClass errorClass() {
    return new JobErrorClass(errorClass);
  }
}
