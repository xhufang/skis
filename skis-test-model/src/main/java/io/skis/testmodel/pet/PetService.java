package io.skis.testmodel.pet;

import io.skis.runtime.SkisExecutor;
import io.skis.testmodel.pet.skis.PetMeta;
import io.skis.testmodel.pet.skis.PetTable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Examples of the 0.0.6 Fast Path and single-table query DSL, ordered by query complexity. */
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

  /**
   * Use an independently aliased table expression. Aliases become more useful when later versions
   * add joins and self-referencing queries.
   */
  public List<Pet> findByNameUsingAlias(String name) {
    PetTable pet = PET.as("p");
    return skisExecutor.selectFrom(pet).where(pet.name().eq(name)).fetchList();
  }
}
