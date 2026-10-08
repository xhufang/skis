package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.DerivedOutputColumn;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.ExistsPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.SqlType;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryPlanDependenciesTest {

  private static final EntityMeta<Root> ROOT = entity(Root.class, "root_entity");
  private static final EntityMeta<Joined> JOINED = entity(Joined.class, "joined_entity");
  private static final EntityMeta<Nested> NESTED = entity(Nested.class, "nested_entity");
  private static final EntityMeta<Derived> DERIVED = entity(Derived.class, "derived_entity");

  @Test
  void collectsRootJoinSubqueryAndDerivedTableSources() {
    TestTable<Root> root = new TestTable<>(ROOT);
    TestTable<Joined> joined = new TestTable<>(JOINED);
    TestTable<Nested> nested = new TestTable<>(NESTED);
    TestTable<Derived> derived = new TestTable<>(DERIVED);
    SelectStatement derivedStatement = new SelectStatement(derived.selections(), derived);
    DerivedRelationReference derivedReference =
        new DerivedRelationReference(
            Identifier.of("derived_source"),
            List.of(
                new DerivedOutputColumn(
                    Identifier.of("id"),
                    Long.class,
                    SqlType.BIGINT,
                    Nullability.NON_NULL)));
    DerivedRelationSource derivedSource =
        new DerivedRelationSource(derivedStatement, derivedReference);
    FromClause outerSources =
        new FromClause(
            root,
            List.of(
                new JoinClause(JoinType.CROSS, joined, null),
                new JoinClause(JoinType.CROSS, derivedSource, null)));
    SelectStatement nestedStatement = new SelectStatement(nested.selections(), nested);
    SelectStatement statement =
        new SelectStatement(
            root.selections(), outerSources, new ExistsPredicate(nestedStatement, false));
    QueryBlockAnalysis analysis = SemanticValidator.analyzeComplete(statement);

    QueryPlanDependencies dependencies = QueryPlanDependencies.from(analysis);

    assertEquals(4, dependencies.entities().size());
    assertTrue(dependencies.contains(ROOT));
    assertTrue(dependencies.contains(JOINED));
    assertTrue(dependencies.contains(NESTED));
    assertTrue(dependencies.contains(DERIVED));
    assertEquals(4, dependencies.sources().size());
  }

  @Test
  void deduplicatesRepeatedCanonicalMetadataByIdentity() {
    TestTable<Root> outer = new TestTable<>(ROOT);
    TestTable<Root> nested = new TestTable<>(ROOT, Identifier.of("nested_root"));
    SelectStatement statement =
        new SelectStatement(
            outer.selections(),
            outer,
            new ExistsPredicate(new SelectStatement(nested.selections(), nested), false));

    QueryPlanDependencies dependencies =
        QueryPlanDependencies.from(SemanticValidator.analyzeComplete(statement));

    assertEquals(List.of(ROOT), dependencies.entities());
    assertEquals(2, dependencies.sources().size());
    assertNotEquals(
        dependencies.sources().get(0).source().blockPath(),
        dependencies.sources().get(1).source().blockPath());
  }

  @Test
  void dependencyMembershipAndEqualityUseMetadataIdentity() {
    EntityMeta<Root> structurallyEqual = entity(Root.class, "root_entity");
    QueryPlanDependencies first = QueryTestSupport.dependencies(ROOT, JOINED, ROOT);
    QueryPlanDependencies reordered = QueryTestSupport.dependencies(JOINED, ROOT);

    assertEquals(2, first.entities().size());
    assertTrue(first.sameEntities(reordered));
    assertFalse(first.contains(structurallyEqual));
    assertFalse(
        first.sameEntities(QueryTestSupport.dependencies(structurallyEqual, JOINED)));
  }

  private static <E> EntityMeta<E> entity(Class<E> javaType, String tableName) {
    PropertyMeta<E, Long> id =
        new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
    return EntityMeta.simple(
        javaType,
        new TableMeta("", "cache_test", tableName),
        List.of(id),
        new PrimaryKeyMeta<>(List.of(id)),
        false);
  }

  private record Root(Long id) {}

  private record Joined(Long id) {}

  private record Nested(Long id) {}

  private record Derived(Long id) {}

  private static final class TestTable<E> extends QueryTable<E> {

    private TestTable(EntityMeta<E> entity) {
      super(entity);
    }

    private TestTable(EntityMeta<E> entity, Identifier alias) {
      super(entity, alias);
    }

    @Override
    public TestTable<E> as(Identifier alias) {
      return new TestTable<>(entity(), alias);
    }
  }
}
