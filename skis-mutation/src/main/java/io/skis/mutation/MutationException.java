package io.skis.mutation;

import io.skis.core.SkisException;
import io.skis.dialect.SqlExceptionCategory;
import java.io.Serial;
import java.util.Objects;

/** Reports an invalid mutation or a failed mutation execution. */
public class MutationException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  private final SqlExceptionCategory category;

  /** Creates a mutation failure with a safe structural diagnostic. */
  public MutationException(String message) {
    super(message);
    this.category = SqlExceptionCategory.UNCATEGORIZED;
  }

  /** Creates a mutation failure retaining its original cause. */
  public MutationException(String message, Throwable cause) {
    super(message, cause);
    this.category = SqlExceptionCategory.UNCATEGORIZED;
  }

  /** Creates a JDBC-backed mutation failure with its dialect-classified category. */
  public MutationException(String message, Throwable cause, SqlExceptionCategory category) {
    super(message, cause);
    this.category = Objects.requireNonNull(category, "category");
  }

  /** Returns the classified JDBC category, or {@code UNCATEGORIZED} for validation failures. */
  public final SqlExceptionCategory category() {
    return category;
  }
}
