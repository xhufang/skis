package io.skis.jdbc.internal;

/** Internal JDBC resource lifecycle phase used while translating driver failures. */
enum JdbcFailurePhase {
  ACQUIRE("connection-acquire"),
  CONFIGURE("statement-configuration"),
  EXECUTE("execution"),
  RESULT_SET_CLOSE("result-set-close"),
  STATEMENT_CLOSE("statement-close"),
  RELEASE("connection-release");

  private final String label;

  JdbcFailurePhase(String label) {
    this.label = label;
  }

  String label() {
    return label;
  }
}
