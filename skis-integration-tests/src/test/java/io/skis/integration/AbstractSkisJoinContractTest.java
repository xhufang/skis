package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.Dialect;
import io.skis.jdbc.QueryExecutionException;
import io.skis.query.NullableSelectQuery;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.QueryParameter;
import io.skis.query.QueryParameters;
import io.skis.query.SelectQuery;
import io.skis.query.Selectable;
import io.skis.query.SingleRow;
import io.skis.query.Slice;
import io.skis.query.SliceRequest;
import io.skis.query.Sql;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import io.skis.testmodel.join.JoinPet;
import io.skis.testmodel.join.Owner;
import io.skis.testmodel.join.PetOwnerPairView;
import io.skis.testmodel.join.PetOwnerView;
import io.skis.testmodel.join.skis.JoinPetMeta;
import io.skis.testmodel.join.skis.JoinPetTable;
import io.skis.testmodel.join.skis.OwnerMeta;
import io.skis.testmodel.join.skis.OwnerTable;
import io.skis.testmodel.join.skis.PetOwnerPairViewProjection;
import io.skis.testmodel.join.skis.PetOwnerViewProjection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared real-driver contract for the Join result, count, and pagination semantics. */
abstract class AbstractSkisJoinContractTest {

  protected DataSource dataSource;
  protected SkisExecutor executor;
  protected final JoinPetTable pet = JoinPetTable.JOIN_PET;
  protected final OwnerTable owner = OwnerTable.OWNER;
  protected long ownerAdaId;
  protected long ownerGraceId;
  protected long ownerWithoutPetId;
  protected long petAdaOneId;
  protected long petAdaTwoId;
  protected long petGraceId;
  protected long petOrphanId;
  protected long petOrphanTwoId;

  protected abstract DataSource createDataSource() throws Exception;

  protected abstract Dialect dialect();

  @BeforeEach
  void setUpJoinContract() throws Exception {
    dataSource = createDataSource();
    prepareSchema(dataSource);
    executor = SkisExecutorFactory.create(dataSource, dialect());

    long base = 10_000L + (UUID.randomUUID().getMostSignificantBits() & 0x1ffffffffffff000L);
    ownerAdaId = base;
    ownerGraceId = base + 1;
    ownerWithoutPetId = base + 2;
    petAdaOneId = base + 100;
    petAdaTwoId = base + 101;
    petGraceId = base + 102;
    petOrphanId = base + 103;
    petOrphanTwoId = base + 104;

    executor.insert(OwnerMeta.ENTITY, new Owner(ownerAdaId, "Ada"));
    executor.insert(OwnerMeta.ENTITY, new Owner(ownerGraceId, "Grace"));
    executor.insert(OwnerMeta.ENTITY, new Owner(ownerWithoutPetId, "Lin"));
    executor.insert(
        JoinPetMeta.ENTITY, new JoinPet(petAdaOneId, ownerAdaId, "Alpha"));
    executor.insert(
        JoinPetMeta.ENTITY, new JoinPet(petAdaTwoId, ownerAdaId, "Beta"));
    executor.insert(
        JoinPetMeta.ENTITY, new JoinPet(petGraceId, ownerGraceId, "Gamma"));
    executor.insert(JoinPetMeta.ENTITY, new JoinPet(petOrphanId, null, "Orphan"));
    executor.insert(JoinPetMeta.ENTITY, new JoinPet(petOrphanTwoId, null, "Orphan Two"));
  }

  @AfterEach
  void cleanJoinContractRows() throws Exception {
    if (dataSource == null || ownerAdaId == 0) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement pets =
            connection.prepareStatement(
                "DELETE FROM \"shelter\".\"skis_join_pet\" WHERE \"id\" IN (?, ?, ?, ?, ?)");
        PreparedStatement owners =
            connection.prepareStatement(
                "DELETE FROM \"shelter\".\"skis_join_owner\" WHERE \"id\" IN (?, ?, ?)")) {
      pets.setLong(1, petAdaOneId);
      pets.setLong(2, petAdaTwoId);
      pets.setLong(3, petGraceId);
      pets.setLong(4, petOrphanId);
      pets.setLong(5, petOrphanTwoId);
      pets.executeUpdate();
      owners.setLong(1, ownerAdaId);
      owners.setLong(2, ownerGraceId);
      owners.setLong(3, ownerWithoutPetId);
      owners.executeUpdate();
    }
  }

  @Test
  void executesEveryJoinKindSharedByPostgreSqlAndH2() {
    List<Long> inner =
        executor
            .select(pet.id())
            .from(pet)
            .innerJoin(owner)
            .on(pet.ownerId().eq(owner.id()))
            .where(pet.id().in(petIds()))
            .fetchList();
    List<Long> left =
        executor
            .select(pet.id())
            .from(pet)
            .leftJoin(owner)
            .on(pet.ownerId().eq(owner.id()))
            .where(pet.id().in(petIds()))
            .fetchList();
    List<Long> right =
        executor
            .select(owner.id())
            .from(pet)
            .rightJoin(owner)
            .on(pet.ownerId().eq(owner.id()))
            .where(owner.id().in(ownerIds()))
            .fetchList();
    List<Long> cross =
        executor
            .select(pet.id())
            .from(pet)
            .crossJoin(owner)
            .where(pet.id().in(petIds()).and(owner.id().in(ownerIds())))
            .fetchList();

    assertEquals(3, inner.size());
    assertEquals(5, left.size());
    assertEquals(4, right.size());
    assertTrue(right.contains(ownerWithoutPetId));
    assertEquals(15, cross.size());
  }

  @Test
  void mapsOuterJoinEntityScalarAndGeneratedProjection() {
    List<PetOwnerView> projections = projectionQuery().fetchList();
    List<Owner> nullableOwners =
        executor
            .selectNullable(owner)
            .from(pet)
            .leftJoin(owner)
            .on(pet.ownerId().eq(owner.id()))
            .where(pet.id().in(petIds()))
            .orderBy(pet.id().asc())
            .fetchList();
    List<String> nullableNames =
        executor
            .selectNullable(owner.name())
            .from(pet)
            .leftJoin(owner)
            .on(pet.ownerId().eq(owner.id()))
            .where(pet.id().in(petIds()))
            .orderBy(pet.id().asc())
            .fetchList();

    assertEquals(
        List.of(
            new PetOwnerView(petAdaOneId, "Alpha", "Ada"),
            new PetOwnerView(petAdaTwoId, "Beta", "Ada"),
            new PetOwnerView(petGraceId, "Gamma", "Grace"),
            new PetOwnerView(petOrphanId, "Orphan", null),
            new PetOwnerView(petOrphanTwoId, "Orphan Two", null)),
        projections);
    assertEquals(
        Arrays.asList(
            new Owner(ownerAdaId, "Ada"),
            new Owner(ownerAdaId, "Ada"),
            new Owner(ownerGraceId, "Grace"),
            null,
            null),
        nullableOwners);
    assertEquals(Arrays.asList("Ada", "Ada", "Grace", null, null), nullableNames);
  }

  @Test
  void keepsSameTableAliasesAsIndependentOccurrences() {
    OwnerTable primaryOwner = owner.as("primary_owner");
    OwnerTable reviewer = owner.as("reviewer");

    List<PetOwnerPairView> rows =
        executor
            .select(
                PetOwnerPairViewProjection.of(
                    pet.id(), primaryOwner.name(), reviewer.name()))
            .from(pet)
            .leftJoin(primaryOwner)
            .on(pet.ownerId().eq(primaryOwner.id()))
            .leftJoin(reviewer)
            .on(primaryOwner.id().eq(reviewer.id()))
            .where(pet.id().in(petIds()))
            .orderBy(pet.id().asc())
            .fetchList();

    assertEquals(
        List.of(
            new PetOwnerPairView(petAdaOneId, "Ada", "Ada"),
            new PetOwnerPairView(petAdaTwoId, "Ada", "Ada"),
            new PetOwnerPairView(petGraceId, "Grace", "Grace"),
            new PetOwnerPairView(petOrphanId, null, null),
            new PetOwnerPairView(petOrphanTwoId, null, null)),
        rows);
  }

  @Test
  void preservesJoinDuplicatesAndDistinguishesOnFromWhere() {
    List<Owner> duplicates =
        executor
            .selectFrom(owner)
            .join(pet)
            .on(owner.id().eq(pet.ownerId()))
            .where(owner.id().eq(ownerAdaId))
            .orderBy(pet.id().asc())
            .fetchList();
    List<Owner> distinct =
        executor
            .selectFrom(owner)
            .join(pet)
            .on(owner.id().eq(pet.ownerId()))
            .where(owner.id().eq(ownerAdaId))
            .distinct()
            .orderBy(owner.id().asc(), owner.name().asc())
            .fetchList();
    List<Long> restrictedInOn =
        executor
            .select(pet.id())
            .from(pet)
            .leftJoin(owner)
            .on(pet.ownerId().eq(owner.id()).and(owner.name().eq("Ada")))
            .where(pet.id().in(petIds()))
            .orderBy(pet.id().asc())
            .fetchList();
    List<Long> restrictedInWhere =
        executor
            .select(pet.id())
            .from(pet)
            .leftJoin(owner)
            .on(pet.ownerId().eq(owner.id()))
            .where(pet.id().in(petIds()).and(owner.name().eq("Ada")))
            .orderBy(pet.id().asc())
            .fetchList();

    Owner ada = new Owner(ownerAdaId, "Ada");
    assertEquals(List.of(ada, ada), duplicates);
    assertEquals(List.of(ada), distinct);
    assertEquals(
        List.of(
            petAdaOneId, petAdaTwoId, petGraceId, petOrphanId, petOrphanTwoId),
        restrictedInOn);
    assertEquals(List.of(petAdaOneId, petAdaTwoId), restrictedInWhere);
  }

  @Test
  void preservesExistsRowNullAndDuplicateSemantics() {
    JoinPetTable witness = pet.as("exists_witness");
    QueryParameter<Long> orphanId = Sql.parameter(Long.class, "orphanId");
    var singleNullRow =
        Sql.select(witness.ownerId()).from(witness).where(witness.id().eq(orphanId));
    var duplicateRows =
        Sql.select(witness.ownerId()).from(witness).where(witness.ownerId().isNotNull());
    var emptyRows =
        Sql.select(witness.id()).from(witness).where(witness.id().ne(witness.id()));

    var ownersWhenNullRowExists =
        Sql.select(owner.id())
            .from(owner)
            .where(Sql.exists(singleNullRow))
            .orderBy(owner.id().asc());
    List<Long> nullRowExists =
        executor
            .query(ownersWhenNullRowExists, QueryParameters.of(orphanId, petOrphanId))
            .fetchList();
    List<Long> duplicateRowsExist =
        executor
            .select(owner.id())
            .from(owner)
            .where(owner.id().eq(ownerAdaId).and(Sql.exists(duplicateRows)))
            .fetchList();
    List<Long> notExists =
        executor
            .select(owner.id())
            .from(owner)
            .where(owner.id().eq(ownerAdaId).and(Sql.notExists(emptyRows)))
            .fetchList();

    assertEquals(ownerIds(), nullRowExists);
    assertEquals(List.of(ownerAdaId), duplicateRowsExist);
    assertEquals(List.of(ownerAdaId), notExists);
  }

  @Test
  void executesCorrelatedExistsAndNotExistsWithoutMaterializingTheSubquery() {
    List<Owner> ownersWithPets = correlatedExistsQuery().fetchList();
    JoinPetTable witness = pet.as("not_exists_pet");
    var petsOfOwner =
        Sql.select(witness.ownerId())
            .from(witness)
            .where(witness.ownerId().eq(owner.id()));
    List<Owner> ownersWithoutPets =
        executor
            .selectFrom(owner)
            .where(Sql.notExists(petsOfOwner))
            .orderBy(owner.id().asc())
            .fetchList();

    assertEquals(
        List.of(new Owner(ownerAdaId, "Ada"), new Owner(ownerGraceId, "Grace")),
        ownersWithPets);
    assertEquals(List.of(new Owner(ownerWithoutPetId, "Lin")), ownersWithoutPets);
  }

  @Test
  void bindsParametersInsideACorrelatedExistsDescription() {
    JoinPetTable witness = pet.as("parameter_pet");
    QueryParameter<String> petName = Sql.parameter(String.class, "petName");
    var namedPetsOfOwner =
        Sql.select(witness.id())
            .from(witness)
            .where(
                witness
                    .ownerId()
                    .eq(owner.id())
                    .and(witness.name().eq(petName)));
    var owners =
        Sql.selectFrom(owner)
            .where(Sql.exists(namedPetsOfOwner))
            .orderBy(owner.id().asc());

    List<Owner> result =
        executor.query(owners, QueryParameters.of(petName, "Alpha")).fetchList();

    assertEquals(List.of(new Owner(ownerAdaId, "Ada")), result);
  }

  @Test
  void preservesInSubqueryThreeValuedLogicForEmptyNullAndDuplicateResults() {
    JoinPetTable values = pet.as("membership_values");
    QueryParameter<Long> selectedOwner = Sql.parameter(Long.class, "selectedOwner");
    var selectedOwnerIds =
        Sql.select(values.ownerId())
            .from(values)
            .where(values.ownerId().eq(selectedOwner));
    var matching =
        Sql.select(pet.id())
            .from(pet)
            .where(pet.ownerId().in(selectedOwnerIds))
            .orderBy(pet.id().asc());
    var notMatching =
        Sql.select(pet.id())
            .from(pet)
            .where(pet.ownerId().notIn(selectedOwnerIds))
            .orderBy(pet.id().asc());

    assertEquals(
        List.of(petAdaOneId, petAdaTwoId),
        executor.query(matching, QueryParameters.of(selectedOwner, ownerAdaId)).fetchList());
    assertEquals(
        List.of(petGraceId),
        executor.query(notMatching, QueryParameters.of(selectedOwner, ownerAdaId)).fetchList());
    assertEquals(
        List.of(),
        executor
            .query(matching, QueryParameters.of(selectedOwner, ownerWithoutPetId))
            .fetchList());
    assertEquals(
        petIds(),
        executor
            .query(notMatching, QueryParameters.of(selectedOwner, ownerWithoutPetId))
            .fetchList());

    var allOwnerIds = Sql.select(values.ownerId()).from(values);
    assertEquals(
        List.of(petAdaOneId, petAdaTwoId, petGraceId),
        executor
            .query(
                Sql.select(pet.id())
                    .from(pet)
                    .where(pet.ownerId().in(allOwnerIds))
                    .orderBy(pet.id().asc()))
            .fetchList());
    assertEquals(
        List.of(),
        executor
            .query(
                Sql.select(pet.id())
                    .from(pet)
                    .where(pet.ownerId().notIn(allOwnerIds)))
            .fetchList());

    var nullOnly =
        Sql.select(values.ownerId()).from(values).where(values.ownerId().isNull());
    assertEquals(
        List.of(),
        executor
            .query(
                Sql.select(pet.id()).from(pet).where(pet.ownerId().in(nullOnly)))
            .fetchList());
    assertEquals(
        List.of(),
        executor
            .query(
                Sql.select(pet.id()).from(pet).where(pet.ownerId().notIn(nullOnly)))
            .fetchList());
  }

  @Test
  void keepsNotInDifferentFromNotExistsWhenTheChildContainsNull() {
    JoinPetTable values = pet.as("nullable_membership_values");
    var allOwnerIds = Sql.select(values.ownerId()).from(values);
    List<Owner> notIn =
        executor
            .query(Sql.selectFrom(owner).where(owner.id().notIn(allOwnerIds)))
            .fetchList();

    JoinPetTable witness = pet.as("absence_witness");
    var petsOfOwner =
        Sql.select(witness.id())
            .from(witness)
            .where(witness.ownerId().eq(owner.id()));
    List<Owner> notExists =
        executor
            .query(
                Sql.selectFrom(owner)
                    .where(Sql.notExists(petsOfOwner))
                    .orderBy(owner.id().asc()))
            .fetchList();

    assertEquals(List.of(), notIn);
    assertEquals(List.of(new Owner(ownerWithoutPetId, "Lin")), notExists);
  }

  @Test
  void preservesScalarSubqueryRowAndCardinalitySemantics() {
    JoinPetTable witness = pet.as("scalar_pet");
    Selectable<Long> correlated =
        Sql.scalar(
            Sql.select(witness.ownerId())
                .from(witness)
                .where(witness.id().eq(pet.id())));
    Selectable<Long> empty =
        Sql.scalar(
            Sql.select(witness.ownerId())
                .from(witness)
                .where(witness.id().ne(witness.id())));

    SingleRow<Long> emptyChild =
        executor.select(empty).from(pet).where(pet.id().eq(petAdaOneId)).fetchOne();
    SingleRow<Long> nullValue =
        executor.select(correlated).from(pet).where(pet.id().eq(petOrphanId)).fetchOne();
    SingleRow<Long> value =
        executor.select(correlated).from(pet).where(pet.id().eq(petAdaOneId)).fetchOne();
    SingleRow<Long> noOuterRow =
        executor
            .select(correlated)
            .from(pet)
            .where(pet.id().ne(pet.id()))
            .fetchOne();

    assertTrue(
        emptyChild instanceof SingleRow.Present<?> present && present.value() == null);
    assertTrue(nullValue instanceof SingleRow.Present<?> present && present.value() == null);
    assertEquals(new SingleRow.Present<>(ownerAdaId), value);
    assertTrue(noOuterRow instanceof SingleRow.NoRow<?>);

    Selectable<Long> duplicate =
        Sql.scalar(
            Sql.select(witness.id())
                .from(witness)
                .where(witness.ownerId().eq(pet.ownerId())));
    QueryExecutionException failure =
        assertThrows(
            QueryExecutionException.class,
            () ->
                executor
                    .select(duplicate)
                    .from(pet)
                    .where(pet.id().eq(petAdaOneId))
                    .fetchList());
    assertTrue(failure.getCause() instanceof SQLException);
    SQLException cause = (SQLException) failure.getCause();
    assertTrue(cause.getSQLState() != null && !cause.getSQLState().isBlank());
    assertEquals(cause.getSQLState(), failure.sqlState());
    assertEquals(cause.getErrorCode(), failure.vendorCode());
  }

  @Test
  void decodesCorrelatedScalarSubqueriesInGeneratedProjections() {
    OwnerTable lookup = owner.as("scalar_owner");
    Selectable<String> ownerName =
        Sql.scalar(
            Sql.select(lookup.name())
                .from(lookup)
                .where(lookup.id().eq(pet.ownerId())));

    List<PetOwnerView> result =
        executor
            .select(PetOwnerViewProjection.of(pet.id(), pet.name(), ownerName))
            .from(pet)
            .where(pet.id().in(petIds()))
            .orderBy(pet.id().asc())
            .fetchList();

    assertEquals(
        List.of(
            new PetOwnerView(petAdaOneId, "Alpha", "Ada"),
            new PetOwnerView(petAdaTwoId, "Beta", "Ada"),
            new PetOwnerView(petGraceId, "Gamma", "Grace"),
            new PetOwnerView(petOrphanId, "Orphan", null),
            new PetOwnerView(petOrphanTwoId, "Orphan Two", null)),
        result);
  }

  @Test
  void preservesEmptyCollectionMembershipForParameterizedScalarOperands() {
    OwnerTable lookup = owner.as("empty_scalar_owner");
    QueryParameter<String> name = Sql.parameter(String.class, "name");
    Selectable<Long> scalar =
        Sql.scalar(
            Sql.select(lookup.id()).from(lookup).where(lookup.name().eq(name)));
    QueryParameters parameters = QueryParameters.of(name, "Ada");

    List<Long> none =
        executor
            .query(Sql.select(pet.id()).from(pet).where(scalar.in(List.of())), parameters)
            .and(pet.id().in(petIds()))
            .fetchList();
    List<Long> all =
        executor
            .query(Sql.select(pet.id()).from(pet).where(scalar.notIn(List.of())), parameters)
            .and(pet.id().in(petIds()))
            .orderBy(pet.id().asc())
            .fetchList();

    assertEquals(List.of(), none);
    assertEquals(petIds(), all);
  }

  @Test
  void ordersBySeparateScalarParametersWithoutCollapsingTheirIdentities() {
    OwnerTable lookup = owner.as("ordered_scalar_owner");
    QueryParameter<String> firstName = Sql.parameter(String.class, "name");
    QueryParameter<String> secondName = Sql.parameter(String.class, "name");
    Selectable<Long> first =
        Sql.scalar(
            Sql.select(lookup.id())
                .from(lookup)
                .where(lookup.id().eq(pet.ownerId()).and(lookup.name().eq(firstName))));
    Selectable<Long> second =
        Sql.scalar(
            Sql.select(lookup.id())
                .from(lookup)
                .where(lookup.id().eq(pet.ownerId()).and(lookup.name().eq(secondName))));
    QueryParameters parameters =
        QueryParameters.builder().bind(firstName, "Ada").bind(secondName, "Grace").build();

    List<Long> rows =
        executor
            .query(
                Sql.select(pet.id())
                    .from(pet)
                    .orderBy(first.asc().nullsLast(), second.asc().nullsLast(), pet.id().asc()),
                parameters)
            .where(pet.id().in(petIds()))
            .fetchList();

    assertEquals(petIds(), rows);
  }

  @Test
  void executesAParameterizedDerivedRootWithoutMaterializingItsInnerRows() {
    JoinPetTable inner = pet.as("derived_pet");
    QueryParameter<Long> lower = Sql.parameter(Long.class, "lower");
    QueryParameter<Long> upper = Sql.parameter(Long.class, "upper");
    var child = Sql.select(inner.id()).from(inner).where(inner.id().ge(lower));
    var idOutput = Sql.output(inner.id(), "pet_id");
    var derived = Sql.derived(child, "filtered_pet", idOutput);
    var derivedId = derived.column(idOutput);
    var query =
        Sql.select(derivedId)
            .from(derived)
            .where(derivedId.le(upper))
            .orderBy(derivedId.asc());
    QueryParameters parameters =
        QueryParameters.builder()
            .bind(lower, petAdaOneId)
            .bind(upper, petGraceId)
            .build();

    List<Long> result = executor.query(query, parameters).fetchList();

    assertEquals(List.of(petAdaOneId, petAdaTwoId, petGraceId), result);
  }

  @Test
  void nullExtendsANonNullDerivedOutputWhenTheOuterJoinHasNoMatch() {
    OwnerTable inner = owner.as("derived_owner");
    var idOutput = Sql.output(inner.id(), "owner_id");
    var nameOutput = Sql.output(inner.name(), "owner_name");
    var derived =
        Sql.derived(Sql.selectFrom(inner), "visible_owner", idOutput, nameOutput);
    var derivedId = derived.column(idOutput);
    var derivedName = derived.column(nameOutput);

    List<String> result =
        executor
            .selectNullable(derivedName)
            .from(pet)
            .leftJoin(derived)
            .on(pet.ownerId().eq(derivedId))
            .where(pet.id().in(petIds()))
            .orderBy(pet.id().asc())
            .fetchList();

    assertEquals(Arrays.asList("Ada", "Ada", "Grace", null, null), result);
  }

  @Test
  void keepsJoinPaginationCountDistinctAndNullableKeysetEquivalent() {
    SelectQuery<Owner, Owner> duplicateOwners =
        executor
            .selectFrom(owner)
            .join(pet)
            .on(owner.id().eq(pet.ownerId()))
            .where(owner.id().eq(ownerAdaId))
            .orderBy(owner.id().asc(), pet.id().asc());
    Page<Owner> duplicatePage = duplicateOwners.fetchPage(PageRequest.page(0, 1));

    SelectQuery<Owner, Owner> distinctOwners =
        executor
            .selectFrom(owner)
            .join(pet)
            .on(owner.id().eq(pet.ownerId()))
            .where(owner.id().eq(ownerAdaId))
            .distinct()
            .orderBy(owner.id().asc(), owner.name().asc());
    Page<Owner> distinctPage = distinctOwners.fetchPage(PageRequest.page(0, 10));

    SelectQuery<JoinPet, PetOwnerView> keysetQuery =
        projectionQuery().orderBy(owner.id().asc().nullsLast(), pet.id().asc());
    Slice<PetOwnerView> first = keysetQuery.fetchSlice(SliceRequest.keysetFirst(2));
    Slice<PetOwnerView> second =
        keysetQuery.fetchSlice(
            SliceRequest.resume(first.nextContinuation().orElseThrow(), 2));
    Slice<PetOwnerView> third =
        keysetQuery.fetchSlice(
            SliceRequest.resume(second.nextContinuation().orElseThrow(), 2));

    NullableSelectQuery<JoinPet, String> distinctOwnerNames =
        executor
            .selectNullable(owner.name())
            .from(pet)
            .leftJoin(owner)
            .on(pet.ownerId().eq(owner.id()))
            .where(pet.id().in(petIds()))
            .distinct()
            .orderBy(owner.name().asc().nullsLast());
    Page<String> distinctNamePage =
        distinctOwnerNames.fetchPage(PageRequest.page(0, 10));

    assertEquals(List.of(new Owner(ownerAdaId, "Ada")), duplicatePage.items());
    assertEquals(2, duplicatePage.totalElements());
    assertEquals(List.of(new Owner(ownerAdaId, "Ada")), distinctPage.items());
    assertEquals(1, distinctPage.totalElements());
    assertEquals(List.of(petAdaOneId, petAdaTwoId), petIds(first));
    assertTrue(first.hasNext());
    assertEquals(List.of(petGraceId, petOrphanId), petIds(second));
    assertTrue(second.hasNext());
    assertEquals(List.of(petOrphanTwoId), petIds(third));
    assertFalse(third.hasNext());
    assertEquals(Arrays.asList("Ada", "Grace", null), distinctNamePage.items());
    assertEquals(3, distinctNamePage.totalElements());
  }

  protected SelectQuery<JoinPet, PetOwnerView> projectionQuery() {
    return executor
        .select(PetOwnerViewProjection.of(pet.id(), pet.name(), owner.name()))
        .from(pet)
        .leftJoin(owner)
        .on(pet.ownerId().eq(owner.id()))
        .where(pet.id().in(petIds()))
        .orderBy(pet.id().asc());
  }

  protected SelectQuery<Owner, Owner> correlatedExistsQuery() {
    JoinPetTable witness = pet.as("correlated_pet");
    var petsOfOwner =
        Sql.select(witness.ownerId())
            .from(witness)
            .where(witness.ownerId().eq(owner.id()));
    return executor
        .selectFrom(owner)
        .where(Sql.exists(petsOfOwner))
        .orderBy(owner.id().asc());
  }

  protected SelectQuery<?, Owner> correlatedInSubqueryQuery() {
    JoinPetTable witness = pet.as("correlated_membership_pet");
    var ownerIds =
        Sql.select(witness.ownerId())
            .from(witness)
            .where(witness.ownerId().eq(owner.id()));
    return executor
        .query(Sql.selectFrom(owner).where(owner.id().in(ownerIds)).orderBy(owner.id().asc()));
  }

  protected NullableSelectQuery<JoinPet, String> correlatedScalarQuery() {
    OwnerTable lookup = owner.as("scalar_owner");
    Selectable<String> ownerName =
        Sql.scalar(
            Sql.select(lookup.name())
                .from(lookup)
                .where(lookup.id().eq(pet.ownerId())));
    return executor
        .select(ownerName)
        .from(pet)
        .where(pet.id().in(petIds()))
        .orderBy(ownerName.asc().nullsLast(), pet.id().asc());
  }

  protected List<Long> petIds() {
    return List.of(petAdaOneId, petAdaTwoId, petGraceId, petOrphanId, petOrphanTwoId);
  }

  protected List<Long> ownerIds() {
    return List.of(ownerAdaId, ownerGraceId, ownerWithoutPetId);
  }

  private static List<Long> petIds(Slice<PetOwnerView> slice) {
    return slice.items().stream().map(PetOwnerView::petId).toList();
  }

  private static void prepareSchema(DataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS \"shelter\"");
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS "shelter"."skis_join_owner" (
            "id" BIGINT PRIMARY KEY,
            "owner_name" VARCHAR(200) NOT NULL
          )
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS "shelter"."skis_join_pet" (
            "id" BIGINT PRIMARY KEY,
            "owner_id" BIGINT NULL,
            "pet_name" VARCHAR(200) NOT NULL
          )
          """);
    }
  }
}
