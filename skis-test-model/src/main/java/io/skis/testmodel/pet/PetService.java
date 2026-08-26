package io.skis.testmodel.pet;

import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisSession;
import io.skis.testmodel.pet.skis.PetMeta;
import io.skis.testmodel.pet.skis.PetTable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** 0.1.0 query, projection, mutation, and transaction examples ordered by complexity. */
public final class PetService {

  private static final PetTable PET = PetTable.PET;

  private final SkisExecutor skisExecutor;

  public PetService(SkisExecutor skisExecutor) {
    this.skisExecutor = skisExecutor;
  }

  /** Simplest and fastest query: use the prewarmed single-primary-key Fast Path. */
  public Optional<Pet> findById(long id) {
    return skisExecutor.findById(PetMeta.ENTITY, id);
  }

  /** Select every persistent Pet column without a where predicate. */
  public List<Pet> findAll() {
    return skisExecutor.selectFrom(PET).fetchList();
  }

  /** Execute one equality predicate and require at most one matching row. */
  public Optional<Pet> findOneByName(String name) {
    return skisExecutor.selectFrom(PET).where(PET.name().eq(name)).fetchOne();
  }

  /** Query a primitive-backed Boolean column; boxing is handled by the generated DSL type. */
  public List<Pet> findByAdoptionStatus(boolean adopted) {
    return skisExecutor.selectFrom(PET).where(PET.adopted().eq(adopted)).fetchList();
  }

  /** Query a decimal column through its generated BigDecimal JDBC codec. */
  public List<Pet> findByExactWeight(BigDecimal weight) {
    return skisExecutor.selectFrom(PET).where(PET.weight().eq(weight)).fetchList();
  }

  /** Select one non-null column and return its scalar Java type instead of a Pet entity. */
  public List<String> findAllNames() {
    return skisExecutor.select(PET.name()).from(PET).fetchList();
  }

  /** Add a predicate to a scalar projection and require at most one matching row. */
  public Optional<String> findNameById(long id) {
    return skisExecutor.select(PET.name()).from(PET).where(PET.id().eq(id)).fetchOne();
  }

  /** Map several selected columns to a user-owned record through generated projection metadata. */
  public List<PetSummary> findAllSummaries() {
    return skisExecutor.selectProjection(PET, PetSummary.class).fetchList();
  }

  /** Reuse the immutable projection with different predicates and bound values. */
  public Optional<PetSummary> findSummaryById(long id) {
    return skisExecutor
        .selectProjection(PET, PetSummary.class)
        .where(PET.id().eq(id))
        .fetchOne();
  }

  /**
   * Use an independently aliased table expression. Aliases become more useful when later versions
   * add joins and self-referencing queries.
   */
  public List<Pet> findByNameUsingAlias(String name) {
    PetTable pet = PET.as("p");
    return skisExecutor.selectFrom(pet).where(pet.name().eq(name)).fetchList();
  }

  /**
   * Bind the registered projection to the same aliased table expression used by the predicate.
   */
  public List<PetSummary> findSummariesByNameUsingAlias(String name) {
    PetTable pet = PET.as("p");
    return skisExecutor
        .selectProjection(pet, PetSummary.class)
        .where(pet.name().eq(name))
        .fetchList();
  }

  /** Insert one complete entity through its generated Binder. */
  public int insert(Pet pet) {
    return skisExecutor.insert(PetMeta.ENTITY, pet);
  }

  /**
   * Update one complete entity. A non-null version enables optimistic checking; a null version only
   * advances the database version without performing a hidden read.
   */
  public int updateById(Pet pet) {
    return skisExecutor.updateById(PetMeta.ENTITY, pet);
  }

  /** Delete by the generated single-primary-key Fast Path. */
  public int deleteById(long id) {
    return skisExecutor.deleteById(PetMeta.ENTITY, id);
  }

  /**
   * Recommended simple transaction: insert and read back the initialized version on one connection.
   */
  public Pet insertAndReload(Pet pet) {
    return skisExecutor.inTransaction(
        session -> {
          session.insert(PetMeta.ENTITY, pet);
          return session.findById(PetMeta.ENTITY, pet.id()).orElseThrow();
        });
  }

  /** Insert and return only the fields needed by the caller, all on one transaction connection. */
  public PetSummary insertAndLoadSummary(Pet pet) {
    return skisExecutor.inTransaction(
        session -> {
          session.insert(PetMeta.ENTITY, pet);
          return session
              .selectProjection(PET, PetSummary.class)
              .where(PET.id().eq(pet.id()))
              .fetchOne()
              .orElseThrow();
        });
  }

  /**
   * Use an explicit Session when the caller needs a visible commit boundary. Closing before commit
   * or rollback would automatically roll this transaction back.
   */
  public Pet insertWithExplicitTransaction(Pet pet) {
    try (SkisSession session = skisExecutor.beginTransaction()) {
      session.insert(PetMeta.ENTITY, pet);
      Pet stored = session.findById(PetMeta.ENTITY, pet.id()).orElseThrow();
      session.commit();
      return stored;
    }
  }

  /**
   * Run an optimistic update and read on one transaction connection, publishing only after commit.
   */
  public Pet updateAndReload(Pet pet, Runnable afterCommit) {
    return skisExecutor.inTransaction(
        session -> {
          session.updateById(PetMeta.ENTITY, pet);
          session.afterCommit(afterCommit);
          return session.findById(PetMeta.ENTITY, pet.id()).orElseThrow();
        });
  }

  /**
   * Insert several entities atomically through repeated single-row Fast Paths, not a JDBC batch.
   * Any failed insert or read rolls back every earlier insert, and the callback runs only after the
   * whole transaction commits.
   */
  public List<Pet> insertAllAtomically(List<Pet> pets, Runnable afterCommit) {
    List<Pet> values = List.copyOf(pets);
    return skisExecutor.inTransaction(
        session -> {
          for (Pet pet : values) {
            session.insert(PetMeta.ENTITY, pet);
          }
          List<Pet> stored =
              values.stream()
                  .map(pet -> session.findById(PetMeta.ENTITY, pet.id()).orElseThrow())
                  .toList();
          session.afterCommit(afterCommit);
          return stored;
        });
  }

  /**
   * Combine different writes in one transaction. If the optimistic update conflicts, the earlier
   * insert is rolled back and the after-commit callback is discarded.
   */
  public List<Pet> insertAndUpdateAtomically(Pet newPet, Pet changedPet, Runnable afterCommit) {
    return skisExecutor.inTransaction(
        session -> {
          session.insert(PetMeta.ENTITY, newPet);
          session.updateById(PetMeta.ENTITY, changedPet);
          Pet inserted = session.findById(PetMeta.ENTITY, newPet.id()).orElseThrow();
          Pet updated = session.findById(PetMeta.ENTITY, changedPet.id()).orElseThrow();
          session.afterCommit(afterCommit);
          return List.of(inserted, updated);
        });
  }
}
