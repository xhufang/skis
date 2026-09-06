package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.Dialect;
import io.skis.query.NullableSelectQuery;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.SelectQuery;
import io.skis.query.Slice;
import io.skis.query.SliceRequest;
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
