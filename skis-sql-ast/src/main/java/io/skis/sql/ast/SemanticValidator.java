package io.skis.sql.ast;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Central semantic validation for portable statement scope, types, nulls, and writability. */
public final class SemanticValidator {

  private SemanticValidator() {}

  /**
   * Validates any complete statement supported by the current portable AST.
   *
   * <p>This compatibility entry point delegates to {@link #validateComplete(StatementAst)}.
   */
  public static void validate(StatementAst statement) {
    validateComplete(statement);
  }

  /**
   * Validates a complete statement with the query-block context required for scope and parameter
   * checks.
   */
  public static void validateComplete(StatementAst statement) {
    Objects.requireNonNull(statement, "statement");
    switch (statement) {
      case ReturningStatement returning -> {
        // The canonical constructor already ran validateReturning over these exact components.
        validateComplete(returning.mutation());
        validateStatementParameters(returning);
      }
      case ConflictInsertStatement conflict -> validateConflict(conflict);
      case MergeStatement merge -> validateMerge(merge);
      case SelectStatement select -> validateComplete(select);
      case CountAst count -> validate(count);
      // Immutable records whose canonical constructors run validateInsert/validateUpdate/
      // validateDelete over the same stored components: a second pass is provably identical.
      case InsertStatement ignored -> {}
      case UpdateStatement ignored -> {}
      case DeleteStatement ignored -> {}
      default ->
          throw new IllegalArgumentException(
              "unsupported statement node " + statement.getClass().getName());
    }
  }

  /** Validates outputs against the writable target occurrence, independently of input sources. */
  public static void validateReturning(
      TableExpression<?> target, List<? extends SqlExpression<?>> outputs) {
    ValidationContext context = new ValidationContext();
    context.addVisible(target);
    for (SqlExpression<?> output : outputs) context.validateExpression(output, "RETURNING");
  }

  /**
   * Checks the statement-wide parameter contract of a RETURNING wrapper around an already
   * validated mutation: one descriptor per ordinal and dense ordinals from zero.
   *
   * <p>Scope and type analysis are not repeated. Outputs without parameters cannot change the
   * validated mutation's parameter namespace, so they skip the statement walk entirely.
   */
  public static void validateReturningParameters(ReturningStatement returning) {
    Objects.requireNonNull(returning, "returning");
    boolean[] parameterized = {false};
    SqlAstTraversal.visitExpressions(
        returning.selections(),
        expression -> {
          if (expression instanceof ParameterSlot<?>) parameterized[0] = true;
        },
        ignored -> {});
    if (parameterized[0]) validateStatementParameters(returning);
  }

  private static void validateStatementParameters(StatementAst statement) {
    ValidationContext parameters = new ValidationContext(false);
    SqlAstTraversal.visit(
        statement,
        expression -> {
          if (expression instanceof ParameterSlot<?> parameter)
            parameters.validateExpression(parameter, "statement parameter");
        },
        ignored -> {});
    parameters.requireDenseParameterOrdinals();
  }

  /** Compatibility overload for complete SELECT validation. */
  public static void validate(SelectStatement statement) {
    validateComplete(statement);
  }

  /** Validates SELECT scopes in join order and then validates the final expression tree. */
  public static void validateComplete(SelectStatement statement) {
    analyzeComplete(statement);
  }

  /**
   * Resolves and validates a complete top-level SELECT without mutating its reusable AST.
   *
   * <p>The returned structural paths, occurrence identities, expression dependencies, and effective
   * nullability belong to this analysis occurrence only.
   */
  public static QueryBlockAnalysis analyzeComplete(SelectStatement statement) {
    Objects.requireNonNull(statement, "statement");
    validateSelectExpressions(statement, new ValidationContext(false));
    return QueryScopeAnalyzer.analyzeTopLevel(statement);
  }

  /** Resolves a non-correlated nested query in the containing statement's parameter namespace. */
  public static QueryBlockAnalysis analyzeNestedQuery(SelectStatement query) {
    validateLocal(query);
    return QueryScopeAnalyzer.analyzeInStatement(
        query, QueryBlockPath.root(), QueryBlockAnalysis.ScopeSnapshot.empty());
  }

  /** Resolves a mutation's nested SELECT against its actual outer source occurrences. */
  public static QueryBlockAnalysis analyzeMutationQuery(SelectStatement query, FromClause outer) {
    validateLocal(query);
    List<QueryBlockAnalysis.ScopeSource> sources = new ArrayList<>(outer.occurrences().size());
    for (TableOccurrence occurrence : outer.occurrences()) {
      boolean nullable =
          switch (occurrence.source()) {
            case EntityRelationSource entity -> outer.isNullExtended(entity.table());
            case DerivedRelationSource derived -> outer.isNullExtended(derived.reference());
            case ParameterRelationSource parameters -> outer.isNullExtended(parameters.reference());
          };
      sources.add(
          new QueryBlockAnalysis.ScopeSource(
              new ResolvedSourceIdentity(QueryBlockPath.root(), occurrence.occurrenceOrdinal()),
              occurrence.source(),
              nullable));
    }
    var frame = new QueryBlockAnalysis.ScopeFrame(QueryBlockPath.root(), sources, sources);
    return QueryScopeAnalyzer.analyzeInStatement(
        query,
        QueryBlockPath.root().child(QueryBlockLocation.where(0)),
        new QueryBlockAnalysis.ScopeSnapshot(List.of(frame)));
  }

  /** Validates invariants that do not require a parent or completed query-block scope. */
  static void validateLocal(SelectStatement statement) {
    Objects.requireNonNull(statement, "statement");
    validateSelectExpressions(statement, new ValidationContext(false));
  }

  private static void validateSelectExpressions(
      SelectStatement statement, ValidationContext context) {
    validateFromClause(statement.fromClause(), context);
    statement.selections().forEach(item -> context.validateExpression(item, "SELECT"));
    statement
        .hiddenSelections()
        .forEach(item -> context.validateExpression(item.expression(), "SELECT hidden item"));
    statement.where().ifPresent(item -> context.validateExpression(item, "WHERE"));
    statement.groupBy().forEach(item -> context.validateExpression(item, "GROUP BY"));
    statement.having().ifPresent(item -> context.validateExpression(item, "HAVING"));
    statement.orderBy().forEach(item -> context.validateExpression(item.expression(), "ORDER BY"));
    statement
        .pagination()
        .ifPresent(
            pagination -> {
              if (pagination instanceof KeysetSeek keyset) {
                context.validateExpression(keyset.predicate(), "pagination seek");
              }
              context.validateExpression(pagination.limit(), "pagination limit");
              if (pagination instanceof OffsetLimit offset) {
                context.validateExpression(offset.offset(), "pagination offset");
              }
            });
  }

  private static void validateFromClause(FromClause fromClause, ValidationContext context) {
    // validateDerivedSource performs the derived block's local validation exactly once.
    context.validateDerivedSource(fromClause.root());
    context.addVisible(fromClause.root());
    if (fromClause.root() instanceof ParameterRelationSource parameters) {
      parameters
          .parameters()
          .forEach(parameter -> context.validateExpression(parameter, "parameter relation"));
    }
    for (int index = 0; index < fromClause.joins().size(); index++) {
      JoinClause join = fromClause.joins().get(index);
      context.validateDerivedSource(join.right());
      context.addVisible(join.right());
      if (join.right() instanceof ParameterRelationSource parameters) {
        parameters
            .parameters()
            .forEach(parameter -> context.validateExpression(parameter, "parameter relation"));
      }
      int position = index + 1;
      join.on()
          .ifPresent(
              predicate ->
                  context.validateExpression(predicate, "SELECT FROM join #" + position + " ON"));
      context.applyJoin(join);
    }
  }

  /** Validates an independent COUNT plan. */
  public static void validate(CountAst statement) {
    analyzeComplete(statement);
  }

  /**
   * Resolves an independent COUNT plan through the same SELECT-shaped nested-query scope boundary
   * without constructing an analysis-only SELECT statement.
   */
  public static QueryBlockAnalysis analyzeComplete(CountAst statement) {
    Objects.requireNonNull(statement, "statement");
    validateLocal(statement);
    return QueryScopeAnalyzer.analyzeTopLevel(statement);
  }

  /** Validates COUNT invariants that do not require a completed query-block scope. */
  static void validateLocal(CountAst statement) {
    Objects.requireNonNull(statement, "statement");
    ValidationContext context = new ValidationContext(false);
    validateFromClause(statement.fromClause(), context);
    statement
        .distinctExpression()
        .ifPresent(expression -> context.validateExpression(expression, "SELECT"));
    statement.predicate().ifPresent(predicate -> context.validateExpression(predicate, "WHERE"));
  }

  static void validateInsert(
      TableExpression<?> target,
      List<ColumnExpression<?, ?>> columns,
      List<SqlExpression<?>> values,
      List<List<SqlExpression<?>>> additionalRows,
      @Nullable SelectStatement query) {
    requireWritableTarget(target, "INSERT");
    ValidationContext context = new ValidationContext();
    Set<Integer> seen = new HashSet<>();
    for (ColumnExpression<?, ?> column : columns) {
      requireTargetColumn(column, target, "INSERT");
      if (!seen.add(column.property().ordinal()) || !column.property().column().insertable())
        throw new IllegalArgumentException("duplicate or non-insertable INSERT target");
    }
    if (query != null) {
      // Hidden items are rendered as physical outputs, so they would break the column arity.
      if (columns.size() != query.selections().size() || !query.hiddenSelections().isEmpty())
        throw new IllegalArgumentException("INSERT SELECT arity mismatch");
      QueryBlockAnalysis analysis =
          QueryScopeAnalyzer.analyzeInStatement(
              query, QueryBlockPath.root(), QueryBlockAnalysis.ScopeSnapshot.empty());
      for (int index = 0; index < columns.size(); index++) {
        ColumnExpression<?, ?> column = columns.get(index);
        validateAssignment(column, query.selections().get(index), "INSERT SELECT");
        for (ResolvedExpression item : analysis.expressions()) {
          ExpressionPosition position = item.position();
          if (position.clause() == QueryClause.SELECT
              && position.itemOrdinal() == index
              && position.operandPath().isEmpty()
              && !column.nullable()
              && item.effectiveNullability().isNullable())
            throw new IllegalArgumentException("INSERT SELECT output is effectively nullable");
        }
      }
      context.collectQueryParameters(query);
    } else {
      validateInsertRow(columns, values, context);
      if (columns.isEmpty() && !additionalRows.isEmpty())
        throw new IllegalArgumentException(
            "multiple DEFAULT VALUES rows require an explicit native form");
      for (List<SqlExpression<?>> row : additionalRows) validateInsertRow(columns, row, context);
    }
    context.requireDenseParameterOrdinals();
  }

  private static void validateInsertRow(
      List<ColumnExpression<?, ?>> columns,
      List<SqlExpression<?>> values,
      ValidationContext context) {
    if (columns.size() != values.size())
      throw new IllegalArgumentException("INSERT row arity mismatch");
    for (int index = 0; index < columns.size(); index++) {
      validateAssignment(columns.get(index), values.get(index), "INSERT");
      context.validateAssignmentExpression(values.get(index), "INSERT value");
    }
  }

  private static void validateConflict(ConflictInsertStatement statement) {
    InsertStatement insert = statement.insert();
    // The INSERT record already ran validateInsert in its canonical constructor.
    ValidationContext context = new ValidationContext();
    insert.values().forEach(value -> context.validateAssignmentExpression(value, "INSERT"));
    for (var row : insert.additionalRows())
      row.forEach(value -> context.validateAssignmentExpression(value, "INSERT"));
    if (insert.query() != null) context.collectQueryParameters(insert.query());
    context.addVisible(insert.target());
    context.excludedTarget = insert.target();
    if (statement.doNothing()
        && !(statement.updateWhere() instanceof BooleanPredicate(boolean value) && value))
      throw new IllegalArgumentException("DO NOTHING cannot contain an update predicate");
    Set<Integer> targets = new HashSet<>();
    for (ColumnExpression<?, ?> column : statement.conflictColumns()) {
      requireTargetColumn(column, insert.target(), "ON CONFLICT");
      if (!targets.add(column.property().ordinal())) {
        throw new IllegalArgumentException("duplicate conflict column");
      }
    }
    validateAssignments(insert.target(), statement.assignments(), context);
    context.validateExpression(statement.updateWhere(), "ON CONFLICT WHERE");
    context.requireDenseParameterOrdinals();
  }

  private static void validateMerge(MergeStatement statement) {
    requireWritableTarget(statement.target(), "MERGE");
    if (statement.keyForm()) {
      if (statement.target().entity().version().isPresent()
          || !(statement.on() instanceof BooleanPredicate(boolean value) && value))
        throw new IllegalArgumentException("KEY cannot contain predicates or version metadata");
      ValidationContext context = new ValidationContext();
      validateMergeInsert(
          statement.target(), statement.insertColumns(), statement.insertValues(), context);
      Set<Integer> keys = new HashSet<>();
      for (ColumnExpression<?, ?> key : statement.keys()) {
        requireTargetColumn(key, statement.target(), "KEY");
        if (!statement.insertColumns().contains(key) || !keys.add(key.property().ordinal()))
          throw new IllegalArgumentException("KEY requires distinct provided columns");
      }
      for (var column : statement.insertColumns()) {
        boolean key = keys.contains(column.property().ordinal());
        if (statement
                .target()
                .entity()
                .primaryKey()
                .orElseThrow()
                .properties()
                .contains(column.property())
            ? !key
            : !column.property().column().updatable())
          throw new IllegalArgumentException("KEY cannot overwrite a protected column");
      }
    } else {
      ValidationContext matched = new ValidationContext();
      validateFromClause(statement.scope(), matched);
      matched.validateExpression(statement.on(), "MERGE ON");
      // The source was resolved and validated once in the matched scope above; the NOT MATCHED
      // branch only needs its visibility. Statement-wide parameters are checked below.
      ValidationContext notMatched = new ValidationContext();
      notMatched.addVisible(Objects.requireNonNull(statement.source()));
      for (MergeAction action : statement.actions()) {
        ValidationContext context = action.kind() == MergeAction.Kind.INSERT ? notMatched : matched;
        context.validateExpression(action.condition(), "MERGE branch condition");
        if (action.kind() == MergeAction.Kind.UPDATE) {
          Set<Integer> assigned = new HashSet<>();
          for (SetAssignment item : action.assignments()) {
            if (item
                instanceof
                UpdateAssignment<?>(ColumnExpression<?, ?> column, SqlExpression<?> value)) {
              validateMergeUpdateColumn(statement.target(), column, assigned);
              validateAssignment(column, value, "MERGE UPDATE");
              context.validateAssignmentExpression(value, "MERGE UPDATE");
            } else if (item instanceof RowAssignment row) {
              for (ColumnExpression<?, ?> column : row.columns())
                validateMergeUpdateColumn(statement.target(), column, assigned);
              context.validateRowQuery(row);
            }
          }
        } else if (action.kind() == MergeAction.Kind.INSERT)
          validateMergeInsert(statement.target(), action.columns(), action.values(), context);
      }
    }
    validateStatementParameters(statement);
  }

  /**
   * MERGE locates existing rows through the target key, so UPDATE actions keep the key protection
   * of ON CONFLICT. Explicit version assignments follow the caller's expression (T05 contract);
   * metadata version advancement is added by the mutation layer, not enforced here.
   */
  private static void validateMergeUpdateColumn(
      TableExpression<?> target, ColumnExpression<?, ?> column, Set<Integer> assigned) {
    validateUpdateColumn(target, column, assigned);
    if (target
        .entity()
        .primaryKey()
        .map(key -> key.properties().contains(column.property()))
        .orElse(false))
      throw new IllegalArgumentException(
          "MERGE UPDATE cannot assign primary-key column '" + column.property().name() + "'");
  }

  private static void validateMergeInsert(
      TableExpression<?> target,
      List<ColumnExpression<?, ?>> columns,
      List<SqlExpression<?>> values,
      ValidationContext context) {
    Set<Integer> seen = new HashSet<>();
    for (int index = 0; index < columns.size(); index++) {
      ColumnExpression<?, ?> column = columns.get(index);
      requireTargetColumn(column, target, "MERGE INSERT");
      if (!column.property().column().insertable() || !seen.add(column.property().ordinal()))
        throw new IllegalArgumentException("invalid or duplicate MERGE insert column");
      validateAssignment(column, values.get(index), "MERGE INSERT");
      context.validateAssignmentExpression(values.get(index), "MERGE INSERT");
    }
  }

  private static void validateAssignments(
      TableExpression<?> target, List<UpdateAssignment<?>> assignments, ValidationContext context) {
    Set<Integer> seen = new HashSet<>();
    for (UpdateAssignment<?> assignment : assignments) {
      ColumnExpression<?, ?> column = assignment.column();
      requireTargetColumn(column, target, "UPDATE");
      if (!column.property().column().updatable()
          || !seen.add(column.property().ordinal())
          || target.entity().primaryKey().orElseThrow().properties().contains(column.property())) {
        throw new IllegalArgumentException("invalid or duplicate ON CONFLICT assignment");
      }
      if (target.entity().version().map(v -> v.property() == column.property()).orElse(false)
          && !(assignment.value() instanceof IncrementExpression<?>(SqlExpression<?> operand)
              && operand.equals(column)))
        throw new IllegalArgumentException(
            "version assignment must increment the current database column");
      validateAssignment(column, assignment.value(), "UPDATE");
      context.validateAssignmentExpression(assignment.value(), "UPDATE");
    }
  }

  static void validateUpdate(
      TableExpression<?> target,
      List<? extends SetAssignment> assignments,
      SqlPredicate where,
      @Nullable FromClause from) {
    requireWritableTarget(target, "UPDATE");
    ValidationContext context = new ValidationContext();
    // One scope construction both checks target/source qualifier uniqueness and validates the
    // sources in their resolution order (sources first, then the never null-extended target).
    if (from == null) context.addVisible(target);
    else validateFromClause(MutationScope.of(target, from), context);
    Set<Integer> assigned = new HashSet<>();
    for (SetAssignment item : assignments) {
      if (item
          instanceof UpdateAssignment<?>(ColumnExpression<?, ?> column, SqlExpression<?> value)) {
        validateUpdateColumn(target, column, assigned);
        validateAssignment(column, value, "UPDATE");
        context.validateAssignmentExpression(value, "UPDATE assignment");
        if (from != null
            && !column.nullable()
            && !isDefault(value)
            && context.effectiveNullability(value).isNullable())
          throw new IllegalArgumentException(
              "UPDATE cannot assign an effectively nullable source expression to non-null column '"
                  + column.property().name()
                  + "'");
      } else if (item instanceof RowAssignment row) {
        for (ColumnExpression<?, ?> column : row.columns())
          validateUpdateColumn(target, column, assigned);
        context.validateRowQuery(row);
      }
    }
    context.validateExpression(where, "UPDATE WHERE");
    context.requireDenseParameterOrdinals();
  }

  private static void validateUpdateColumn(
      TableExpression<?> target, ColumnExpression<?, ?> column, Set<Integer> assigned) {
    requireTargetColumn(column, target, "UPDATE");
    if (!column.property().column().updatable() || !assigned.add(column.property().ordinal()))
      throw new IllegalArgumentException(
          "UPDATE contains a duplicate or non-updatable target column");
  }

  static void validateDelete(
      TableExpression<?> target, SqlPredicate where, @Nullable FromClause using) {
    requireWritableTarget(target, "DELETE");
    ValidationContext context = new ValidationContext();
    if (using == null) context.addVisible(target);
    else validateFromClause(MutationScope.of(target, using), context);
    context.validateExpression(where, "DELETE WHERE");
    context.requireDenseParameterOrdinals();
  }

  static void validateAssignment(
      ColumnExpression<?, ?> column, SqlExpression<?> value, String operation) {
    requireSameJavaType(column, value, operation + " value");
    // DEFAULT carries no value: its SQL type is inferred from the Java type only, so it must not be
    // compared with the column's declared (possibly OTHER/converter-backed) SQL type or nullability.
    if (isDefault(value)) return;
    if (!column.sqlType().equalityCompatibleWith(value.sqlType())) {
      throw new IllegalArgumentException(
          operation
              + " value SQL type "
              + value.sqlType()
              + " is not compatible with column '"
              + column.property().name()
              + "' of type "
              + column.sqlType());
    }
    if (!column.nullable() && value.nullable()) {
      throw new IllegalArgumentException(
          operation
              + " cannot assign a nullable expression to non-null column '"
              + column.property().name()
              + "'");
    }
  }

  static void validateComparison(
      SqlExpression<?> left, ComparisonOperator operator, SqlExpression<?> right) {
    requireSameJavaType(left, right, "comparison");
    requireNotAlwaysNull(left, "comparison left operand");
    requireNotAlwaysNull(right, "comparison right operand");
    boolean compatible =
        operator.isOrdered()
            ? left.sqlType().orderingCompatibleWith(right.sqlType())
            : left.sqlType().equalityCompatibleWith(right.sqlType());
    if (!compatible) {
      throw new IllegalArgumentException(
          "operator "
              + operator
              + " is not compatible with SQL types "
              + left.sqlType()
              + " and "
              + right.sqlType());
    }
  }

  static void validateBetween(
      SqlExpression<?> value, SqlExpression<?> lower, SqlExpression<?> upper) {
    requireSameJavaType(value, lower, "BETWEEN");
    requireSameJavaType(value, upper, "BETWEEN");
    requireNotAlwaysNull(value, "BETWEEN value");
    requireNotAlwaysNull(lower, "BETWEEN lower bound");
    requireNotAlwaysNull(upper, "BETWEEN upper bound");
    if (!value.sqlType().orderingCompatibleWith(lower.sqlType())
        || !value.sqlType().orderingCompatibleWith(upper.sqlType())) {
      throw new IllegalArgumentException(
          "BETWEEN requires compatible ordered SQL types but received "
              + value.sqlType()
              + ", "
              + lower.sqlType()
              + " and "
              + upper.sqlType());
    }
  }

  static void validateLike(SqlExpression<?> value, SqlExpression<?> pattern) {
    requireSameJavaType(value, pattern, "LIKE");
    requireNotAlwaysNull(value, "LIKE value");
    requireNotAlwaysNull(pattern, "LIKE pattern");
    if (!value.sqlType().supportsLike()
        || !value.sqlType().equalityCompatibleWith(pattern.sqlType())) {
      throw new IllegalArgumentException(
          "LIKE requires compatible character SQL types but received "
              + value.sqlType()
              + " and "
              + pattern.sqlType());
    }
  }

  static void validateIn(SqlExpression<?> value, List<? extends SqlExpression<?>> candidates) {
    if (candidates.isEmpty()) {
      return;
    }
    requireNotAlwaysNull(value, "IN value");
    for (SqlExpression<?> candidate : candidates) {
      requireSameJavaType(value, candidate, "IN");
      requireNotAlwaysNull(candidate, "IN candidate");
      if (!value.sqlType().equalityCompatibleWith(candidate.sqlType())) {
        throw new IllegalArgumentException(
            "IN requires compatible SQL types but received "
                + value.sqlType()
                + " and "
                + candidate.sqlType());
      }
    }
  }

  /** Validates one SQL subquery-membership value contract without requiring a parent scope. */
  static void validateInSubquery(SqlExpression<?> value, SelectStatement subquery) {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(subquery, "subquery");
    int physicalOutputs = subquery.selections().size() + subquery.hiddenSelections().size();
    if (physicalOutputs != 1) {
      throw new IllegalArgumentException(
          "IN subquery requires exactly one physical output column but received "
              + physicalOutputs
              + " ("
              + subquery.selections().size()
              + " visible, "
              + subquery.hiddenSelections().size()
              + " hidden)");
    }
    SqlExpression<?> output = subquery.selections().getFirst();
    Class<?> leftJavaType = boxedJavaType(value.javaType());
    Class<?> outputJavaType = boxedJavaType(output.javaType());
    if (!leftJavaType.equals(outputJavaType)) {
      throw new IllegalArgumentException(
          "IN subquery Java types differ: "
              + leftJavaType.getTypeName()
              + " and "
              + outputJavaType.getTypeName());
    }
    if (!value.sqlType().equalityCompatibleWith(output.sqlType())) {
      throw new IllegalArgumentException(
          "IN subquery requires equality-compatible SQL types but received "
              + value.sqlType()
              + " and "
              + output.sqlType());
    }
  }

  /** Validates one SQL scalar-subquery output contract without requiring a parent scope. */
  static void validateScalarSubquery(SelectStatement subquery) {
    Objects.requireNonNull(subquery, "subquery");
    int physicalOutputs = subquery.selections().size() + subquery.hiddenSelections().size();
    if (physicalOutputs != 1) {
      throw new IllegalArgumentException(
          "scalar subquery requires exactly one physical output column but received "
              + physicalOutputs
              + " ("
              + subquery.selections().size()
              + " visible, "
              + subquery.hiddenSelections().size()
              + " hidden)");
    }
    SqlExpression<?> output = subquery.selections().getFirst();
    if (output.javaType().isPrimitive()) {
      throw new IllegalArgumentException(
          "scalar subquery output must use a boxed Java type because a zero-row result is NULL");
    }
  }

  static void validateArithmetic(
      SqlExpression<?> left, ArithmeticOperator operator, SqlExpression<?> right) {
    Objects.requireNonNull(operator, "operator");
    requireSameJavaType(left, right, "arithmetic");
    if (!left.sqlType().isNumeric() || left.sqlType() != right.sqlType()) {
      throw new IllegalArgumentException(
          "arithmetic requires identical numeric SQL types but received "
              + left.sqlType()
              + " and "
              + right.sqlType());
    }
    if (operator == ArithmeticOperator.DIVIDE && left.javaType() == BigInteger.class) {
      throw new IllegalArgumentException(
          "BigInteger division is not portable because SQL DECIMAL division may produce "
              + "a fractional result; cast both operands to BigDecimal first");
    }
  }

  static void validateIncrement(SqlExpression<?> operand) {
    if (!operand.sqlType().isNumeric()) {
      throw new IllegalArgumentException(
          "increment requires a numeric SQL type but received " + operand.sqlType());
    }
  }

  static void validateSelectedOutputReference(SelectedOutputReference<?> reference) {
    Objects.requireNonNull(reference, "reference");
    requireJavaSqlDescriptor(
        reference.javaType(), reference.sqlType(), "selected output reference");
  }

  static void validateConcat(List<? extends SqlExpression<String>> operands) {
    for (SqlExpression<String> operand : operands) {
      if (operand.javaType() != String.class || !operand.sqlType().supportsLike()) {
        throw new IllegalArgumentException(
            "concatenation requires character expressions but received "
                + operand.javaType().getTypeName()
                + "/"
                + operand.sqlType());
      }
    }
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  static <T> void validateCase(List<CaseWhen<T>> branches, Optional<SqlExpression<T>> otherwise) {
    SqlExpression<?> first = branches.getFirst().result();
    for (CaseWhen<T> branch : branches) {
      requireCompatibleResult(first, branch.result(), "CASE");
    }
    otherwise.ifPresent(result -> requireCompatibleResult(first, result, "CASE ELSE"));
  }

  static void validateCast(SqlExpression<?> operand, Class<?> javaType, SqlType sqlType) {
    requireJavaSqlDescriptor(javaType, sqlType, "CAST target");
    // Internal typed parameter normalization needs this identity cast. The public Sql.cast
    // target whitelist remains narrower and still excludes binary conversion requests.
    if (sqlType == SqlType.VARBINARY
        && operand.sqlType() == SqlType.VARBINARY
        && javaType == byte[].class) {
      return;
    }
    if (!operand.sqlType().castableTo(sqlType)) {
      throw new IllegalArgumentException(
          "CAST from " + operand.sqlType() + " to " + sqlType + " is not portable");
    }
  }

  static <T> void validateCoalesce(List<SqlExpression<T>> operands) {
    SqlExpression<?> first = operands.getFirst();
    for (SqlExpression<T> operand : operands) {
      requireCompatibleResult(first, operand, "COALESCE");
    }
  }

  /** Validates an aggregate argument without assuming its final query block or parameter layout. */
  static void validateAggregateOperand(SqlExpression<?> operand) {
    // Immutable scalar leaves need no query scope or parameter-consistency maps.
    if (operand instanceof ColumnExpression<?, ?>
        || operand instanceof ParameterSlot<?>
        || operand instanceof LiteralExpression<?>
        || operand instanceof DerivedColumnExpression<?>) {
      requireJavaSqlDescriptor(operand.javaType(), operand.sqlType(), "aggregate argument");
      Nullability nullability =
          Objects.requireNonNull(operand.nullability(), "aggregate argument nullability");
      if (nullability.isNullable() && operand.javaType().isPrimitive()) {
        throw new IllegalArgumentException(
            "a nullable expression cannot use a primitive Java type");
      }
      return;
    }
    new ValidationContext(false).validateExpression(operand, "aggregate argument");
  }

  static void validateLiteral(
      LiteralExpression.Kind kind, Class<?> javaType, SqlType sqlType, Nullability nullability) {
    requireJavaSqlDescriptor(javaType, sqlType, "literal");
    switch (kind) {
      case NULL -> {
        if (!nullability.isNullable() || javaType.isPrimitive() || sqlType == SqlType.OTHER) {
          throw new IllegalArgumentException(
              "NULL literal requires a nullable portable value type");
        }
      }
      case DEFAULT -> {
        if (nullability != Nullability.NULLABLE)
          throw new IllegalArgumentException("DEFAULT must not promise a non-null database value");
      }
      case TRUE, FALSE -> {
        if (javaType != Boolean.class
            || sqlType != SqlType.BOOLEAN
            || nullability != Nullability.NON_NULL) {
          throw new IllegalArgumentException("boolean literal has an invalid type descriptor");
        }
      }
      case ZERO, ONE -> {
        if (!sqlType.isNumeric() || nullability != Nullability.NON_NULL) {
          throw new IllegalArgumentException("numeric literal requires a non-null numeric type");
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  static <T> Class<T> boxedJavaType(Class<T> javaType) {
    Objects.requireNonNull(javaType, "javaType");
    if (!javaType.isPrimitive()) {
      return javaType;
    }
    Class<?> boxed =
        switch (javaType.getName()) {
          case "boolean" -> Boolean.class;
          case "byte" -> Byte.class;
          case "short" -> Short.class;
          case "int" -> Integer.class;
          case "long" -> Long.class;
          case "float" -> Float.class;
          case "double" -> Double.class;
          case "char" -> Character.class;
          default -> throw new IllegalArgumentException("Java expression type must not be void");
        };
    return (Class<T>) boxed;
  }

  private static void requireWritableTarget(TableExpression<?> target, String operation) {
    Objects.requireNonNull(target, "target");
    if (target.entity().readOnly()) {
      throw new IllegalArgumentException(
          operation + " target entity '" + target.entity().entityName() + "' is read-only");
    }
  }

  private static void requireTargetColumn(
      ColumnExpression<?, ?> column, TableExpression<?> target, String operation) {
    if (column.table() != target) {
      throw new IllegalArgumentException(operation + " column does not belong to its target table");
    }
  }

  private static void requireCompatibleResult(
      SqlExpression<?> expected, SqlExpression<?> actual, String operation) {
    requireSameJavaType(expected, actual, operation);
    if (!expected.sqlType().equalityCompatibleWith(actual.sqlType())) {
      throw new IllegalArgumentException(
          operation
              + " result SQL types differ: "
              + expected.sqlType()
              + " and "
              + actual.sqlType());
    }
  }

  private static void requireSameJavaType(
      SqlExpression<?> left, SqlExpression<?> right, String operation) {
    if (!left.javaType().equals(right.javaType())) {
      throw new IllegalArgumentException(
          operation
              + " Java types differ: "
              + left.javaType().getTypeName()
              + " and "
              + right.javaType().getTypeName());
    }
  }

  private static void requireJavaSqlDescriptor(
      Class<?> javaType, SqlType sqlType, String description) {
    Objects.requireNonNull(javaType, "javaType");
    Objects.requireNonNull(sqlType, "sqlType");
    if (javaType == void.class || javaType == Void.class) {
      throw new IllegalArgumentException(description + " Java type must not be void");
    }
    SqlType inferred = SqlType.fromJavaType(javaType);
    boolean compatible =
        inferred == SqlType.OTHER
            ? sqlType == SqlType.OTHER
            : inferred.equalityCompatibleWith(sqlType);
    if (!compatible) {
      throw new IllegalArgumentException(
          description
              + " Java type "
              + javaType.getTypeName()
              + " is not compatible with SQL type "
              + sqlType);
    }
  }

  private static void requireNotAlwaysNull(SqlExpression<?> expression, String description) {
    if (isAlwaysNull(expression)) {
      throw new IllegalArgumentException(
          description + " is always NULL; use IS NULL or IS NOT NULL for a null test");
    }
  }

  private static boolean isDefault(SqlExpression<?> value) {
    return value instanceof LiteralExpression<?> literal
        && literal.kind() == LiteralExpression.Kind.DEFAULT;
  }

  private static boolean isAlwaysNull(SqlExpression<?> expression) {
    return switch (expression) {
      case LiteralExpression<?> literal -> literal.isNullLiteral();
      case ArithmeticExpression<?> arithmetic ->
          isAlwaysNull(arithmetic.left()) || isAlwaysNull(arithmetic.right());
      case ConcatExpression concat ->
          concat.operands().stream().anyMatch(SemanticValidator::isAlwaysNull);
      case CaseExpression<?> caseExpression ->
          caseExpression.branches().stream().allMatch(branch -> isAlwaysNull(branch.result()))
              && caseExpression.otherwise().map(SemanticValidator::isAlwaysNull).orElse(true);
      case CastExpression<?> cast -> isAlwaysNull(cast.operand());
      case CoalesceExpression<?> coalesce ->
          coalesce.operands().stream().allMatch(SemanticValidator::isAlwaysNull);
      case IncrementExpression<?> increment -> isAlwaysNull(increment.operand());
      default -> false;
    };
  }

  private static final class ValidationContext {

    private final boolean validatesQueryContext;
    private final Map<TableExpression<?>, Boolean> visibleTables;
    private final Map<DerivedRelationReference, Boolean> visibleDerivedRelations;
    private final Map<Integer, ParameterSlot<?>> parametersByOrdinal = new HashMap<>();
    private @Nullable TableExpression<?> excludedTarget;
    private final List<RelationSource> visibleSources = new ArrayList<>();

    private ValidationContext() {
      this(true);
    }

    private ValidationContext(boolean validatesQueryContext) {
      this.validatesQueryContext = validatesQueryContext;
      // Local validation only tracks parameter consistency; source identity is resolved separately.
      this.visibleTables = validatesQueryContext ? new IdentityHashMap<>() : Map.of();
      this.visibleDerivedRelations = validatesQueryContext ? new IdentityHashMap<>() : Map.of();
    }

    private void addVisible(RelationSource source) {
      if (!validatesQueryContext) {
        return;
      }
      switch (Objects.requireNonNull(source, "source")) {
        case EntityRelationSource entity -> addVisible(entity.table());
        case DerivedRelationSource derived -> {
          visibleDerivedRelations.put(derived.reference(), Boolean.FALSE);
          visibleSources.add(source);
        }
        case ParameterRelationSource parameters -> {
          visibleDerivedRelations.put(parameters.reference(), Boolean.FALSE);
          visibleSources.add(source);
        }
      }
    }

    private void addVisible(TableExpression<?> table) {
      visibleTables.put(Objects.requireNonNull(table, "table"), Boolean.FALSE);
      visibleSources.add(RelationSource.entity(table));
    }

    private void applyJoin(JoinClause join) {
      if (!validatesQueryContext) {
        return;
      }
      switch (join.type()) {
        case INNER, CROSS -> {}
        case LEFT -> markRightNullable(join.right());
        case RIGHT -> markLeftNullable(join.right());
        case FULL -> {
          markLeftNullable(join.right());
          markRightNullable(join.right());
        }
      }
    }

    private void markRightNullable(RelationSource right) {
      switch (right) {
        case EntityRelationSource entity -> visibleTables.put(entity.table(), Boolean.TRUE);
        case DerivedRelationSource derived ->
            visibleDerivedRelations.put(derived.reference(), Boolean.TRUE);
        case ParameterRelationSource parameters ->
            visibleDerivedRelations.put(parameters.reference(), Boolean.TRUE);
      }
    }

    private void markLeftNullable(RelationSource right) {
      TableExpression<?> rightTable =
          right instanceof EntityRelationSource(TableExpression<?> table) ? table : null;
      DerivedRelationReference rightDerived = right.derivedReference().orElse(null);
      for (TableExpression<?> table : visibleTables.keySet()) {
        if (table != rightTable) {
          visibleTables.put(table, Boolean.TRUE);
        }
      }
      for (DerivedRelationReference relation : visibleDerivedRelations.keySet()) {
        if (relation != rightDerived) {
          visibleDerivedRelations.put(relation, Boolean.TRUE);
        }
      }
    }

    private void collectQueryParameters(SelectStatement query) {
      ValidationContext parameters = new ValidationContext(false);
      validateSelectExpressions(query, parameters);
      for (ParameterSlot<?> parameter : parameters.parametersByOrdinal.values())
        validateParameter(parameter);
    }

    private void validateDerivedSource(RelationSource source) {
      if (!(source instanceof DerivedRelationSource derived)) return;
      // Local validation and parameter collection share one walk of the derived block.
      collectQueryParameters(derived.statement());
      if (validatesQueryContext)
        QueryScopeAnalyzer.analyzeInStatement(
            derived.statement(),
            QueryBlockPath.root()
                .child(QueryBlockLocation.relationSource(visibleSources.size(), 0)),
            QueryBlockAnalysis.ScopeSnapshot.empty());
    }

    private Nullability effectiveNullability(SqlExpression<?> expression) {
      return validatesQueryContext
          ? EffectiveNullabilityResolver.resolve(expression, visibleTables, visibleDerivedRelations)
          : expression.nullability();
    }

    private @Nullable QueryBlockAnalysis validateNested(SelectStatement query) {
      if (!validatesQueryContext) {
        validateSelectExpressions(query, this);
        return null;
      }
      List<QueryBlockAnalysis.ScopeSource> sources =
          new java.util.ArrayList<>(visibleSources.size());
      for (int index = 0; index < visibleSources.size(); index++) {
        RelationSource source = visibleSources.get(index);
        boolean nullable =
            switch (source) {
              case EntityRelationSource entity ->
                  Boolean.TRUE.equals(visibleTables.get(entity.table()));
              case DerivedRelationSource derived ->
                  Boolean.TRUE.equals(visibleDerivedRelations.get(derived.reference()));
              case ParameterRelationSource parameters ->
                  Boolean.TRUE.equals(visibleDerivedRelations.get(parameters.reference()));
            };
        sources.add(
            new QueryBlockAnalysis.ScopeSource(
                new ResolvedSourceIdentity(QueryBlockPath.root(), index), source, nullable));
      }
      var frame = new QueryBlockAnalysis.ScopeFrame(QueryBlockPath.root(), sources, sources);
      QueryBlockAnalysis analysis =
          QueryScopeAnalyzer.analyzeInStatement(
              query,
              QueryBlockPath.root().child(QueryBlockLocation.where(0)),
              new QueryBlockAnalysis.ScopeSnapshot(List.of(frame)));
      collectQueryParameters(query);
      return analysis;
    }

    private void validateRowQuery(RowAssignment row) {
      QueryBlockAnalysis analysis = Objects.requireNonNull(validateNested(row.query()));
      boolean guaranteed =
          analysis.isGrouped()
              && row.query().groupBy().isEmpty()
              && row.query().having().isEmpty()
              && row.query().pagination().isEmpty();
      for (int index = 0; index < row.columns().size(); index++) {
        if (row.columns().get(index).nullable()) continue;
        if (!guaranteed)
          throw new IllegalArgumentException(
              "a zero-row assignment query can produce NULL for a required target");
        for (ResolvedExpression expression : analysis.expressions())
          if (expression.position().clause() == QueryClause.SELECT
              && expression.position().itemOrdinal() == index
              && expression.effectiveNullability().isNullable())
            throw new IllegalArgumentException("row query output is effectively nullable");
      }
    }

    private void validateAssignmentExpression(SqlExpression<?> expression, String clause) {
      if (!isDefault(expression)) validateExpression(expression, clause);
    }

    private void validateExpression(SqlExpression<?> expression, String clause) {
      Objects.requireNonNull(expression, "expression");
      Objects.requireNonNull(clause, "clause");
      requireJavaSqlDescriptor(expression.javaType(), expression.sqlType(), "expression");
      Objects.requireNonNull(expression.nullability(), "expression nullability");
      switch (expression) {
        case ExcludedExpression<?> excluded -> {
          if (validatesQueryContext && excluded.column().table() != excludedTarget) {
            throw new IllegalArgumentException("EXCLUDED is outside its conflict update scope");
          }
        }
        case ColumnExpression<?, ?> column -> validateColumn(column, clause);
        case DerivedColumnExpression<?> column -> validateDerivedColumn(column, clause);
        case ParameterSlot<?> parameter -> validateParameter(parameter);
        case BooleanPredicate ignored -> {
          // A deterministic boolean predicate has no operand, parameter, or source dependency.
        }
        case SelectedOutputReference<?> reference -> validateSelectedOutputReference(reference);
        case LiteralExpression<?> literal -> {
          if (isDefault(literal))
            throw new IllegalArgumentException(
                "DEFAULT is only legal as a complete assignment or VALUES item");
          validateLiteral(
              literal.kind(), literal.javaType(), literal.sqlType(), literal.nullability());
        }
        case ArithmeticExpression<?> arithmetic -> {
          validateArithmetic(arithmetic.left(), arithmetic.operator(), arithmetic.right());
          validateExpression(arithmetic.left(), clause);
          validateExpression(arithmetic.right(), clause);
        }
        case ConcatExpression concat -> {
          validateConcat(concat.operands());
          concat.operands().forEach(item -> validateExpression(item, clause));
        }
        case CaseExpression<?> caseExpression -> validateCaseExpression(caseExpression, clause);
        case CastExpression<?> cast -> {
          validateCast(cast.operand(), cast.javaType(), cast.sqlType());
          validateExpression(cast.operand(), clause);
        }
        case CoalesceExpression<?> coalesce -> validateCoalesceExpression(coalesce, clause);
        case AggregateExpression<?> aggregate -> {
          if (validatesQueryContext) {
            throw new IllegalArgumentException(
                clause + " uses an aggregate outside a SELECT query block");
          }
          aggregate.operand().ifPresent(operand -> validateExpression(operand, clause));
        }
        case ComparisonPredicate<?> comparison -> {
          validateComparison(comparison.left(), comparison.operator(), comparison.right());
          validateExpression(comparison.left(), clause);
          validateExpression(comparison.right(), clause);
        }
        case LogicalPredicate logical ->
            logical.operands().forEach(item -> validateExpression(item, clause));
        case NullPredicate nullPredicate -> validateExpression(nullPredicate.operand(), clause);
        case BetweenPredicate<?> between -> {
          validateBetween(between.value(), between.lower(), between.upper());
          validateExpression(between.value(), clause);
          validateExpression(between.lower(), clause);
          validateExpression(between.upper(), clause);
        }
        case LikePredicate like -> {
          validateLike(like.value(), like.pattern());
          validateExpression(like.value(), clause);
          validateExpression(like.pattern(), clause);
        }
        case InPredicate<?> in -> {
          validateIn(in.value(), in.candidates());
          validateExpression(in.value(), clause);
          in.candidates().forEach(item -> validateExpression(item, clause));
        }
        case InSubqueryPredicate<?> in -> {
          validateInSubquery(in.value(), in.subquery());
          validateExpression(in.value(), clause);
          validateNested(in.subquery());
        }
        case ExistsPredicate exists -> validateNested(exists.subquery());
        case ScalarSubqueryExpression<?> scalar -> {
          validateScalarSubquery(scalar.subquery());
          validateNested(scalar.subquery());
        }
        case NotPredicate not -> validateExpression(not.operand(), clause);
        case IncrementExpression<?> increment -> {
          validateIncrement(increment.operand());
          validateExpression(increment.operand(), clause);
        }
        default ->
            throw new IllegalArgumentException(
                clause
                    + " uses unsupported SQL expression node "
                    + expression.getClass().getName());
      }
      Nullability resolvedNullability =
          validatesQueryContext
              ? EffectiveNullabilityResolver.resolve(
                  expression, visibleTables, visibleDerivedRelations)
              : expression.nullability();
      if (resolvedNullability.isNullable() && expression.javaType().isPrimitive()) {
        throw new IllegalArgumentException(
            "a nullable expression cannot use a primitive Java type");
      }
    }

    private void validateColumn(ColumnExpression<?, ?> column, String clause) {
      if (!validatesQueryContext) {
        return;
      }
      if (!visibleTables.containsKey(column.table())) {
        throw new IllegalArgumentException(
            clause
                + " column '"
                + column.property().name()
                + "' references invisible table entity '"
                + column.table().entity().entityName()
                + "'"
                + column
                    .table()
                    .alias()
                    .map(alias -> " with alias '" + alias.value() + "'")
                    .orElse("")
                + "; table references are matched by object identity");
      }
    }

    private void validateDerivedColumn(DerivedColumnExpression<?> column, String clause) {
      if (!validatesQueryContext) {
        return;
      }
      if (!visibleDerivedRelations.containsKey(column.relation())) {
        throw new IllegalArgumentException(
            clause
                + " derived column '"
                + column.relation().alias().value()
                + '.'
                + column.output().name().value()
                + "' references an invisible derived relation; relation references are matched "
                + "by object identity");
      }
    }

    private void validateParameter(ParameterSlot<?> parameter) {
      ParameterSlot<?> existing = parametersByOrdinal.putIfAbsent(parameter.ordinal(), parameter);
      if (existing != null
          && (!existing.javaType().equals(parameter.javaType())
              || existing.sqlType() != parameter.sqlType()
              || existing.nullability() != parameter.nullability())) {
        throw new IllegalArgumentException(
            "parameter ordinal "
                + parameter.ordinal()
                + " has conflicting Java type, SQL type or nullability");
      }
    }

    private void validateCaseExpression(CaseExpression<?> caseExpression, String clause) {
      validateCaseUntyped(caseExpression);
      for (CaseWhen<?> branch : caseExpression.branches()) {
        validateExpression(branch.condition(), clause);
        validateExpression(branch.result(), clause);
      }
      caseExpression.otherwise().ifPresent(item -> validateExpression(item, clause));
    }

    private void validateCoalesceExpression(CoalesceExpression<?> coalesce, String clause) {
      validateCoalesceUntyped(coalesce);
      coalesce.operands().forEach(item -> validateExpression(item, clause));
    }

    private void requireDenseParameterOrdinals() {
      for (int expected = 0; expected < parametersByOrdinal.size(); expected++) {
        if (!parametersByOrdinal.containsKey(expected)) {
          throw new IllegalArgumentException(
              "parameter ordinals must be contiguous from zero; missing ordinal " + expected);
        }
      }
    }
  }

  private static <T> void validateCaseUntyped(CaseExpression<T> caseExpression) {
    validateCase(caseExpression.branches(), caseExpression.otherwise());
  }

  private static <T> void validateCoalesceUntyped(CoalesceExpression<T> coalesce) {
    validateCoalesce(coalesce.operands());
  }
}
