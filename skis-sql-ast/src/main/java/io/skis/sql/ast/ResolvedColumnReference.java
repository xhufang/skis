package io.skis.sql.ast;

/** Resolved dependency on one physical or derived output column. */
public sealed interface ResolvedColumnReference
    permits ResolvedColumnIdentity, ResolvedDerivedColumnIdentity {

  /** Concrete relation occurrence that owns this column. */
  ResolvedSourceIdentity source();
}
