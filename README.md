# SKIS ORM

> **Status: In Development**

SKIS is a Java JDBC ORM currently under active development. APIs, documentation,
and behavior may change before the first stable release. It is not yet recommended
for production use.

## Requirements

- Java 21+
- Maven 3.9+

## Documentation

- [0.0.5 JDBC and dialect foundation](docs/0.0.5-foundation.md)
- [0.0.6 injected executor and reflection-free read slice](docs/0.0.6-read-slice.md)

## 0.0.6 query preview

Applications keep one thread-safe executor as a constructor-injected dependency:

```java
public final class PetService {

  private final SkisExecutor skisExecutor;

  public PetService(SkisExecutor skisExecutor) {
    this.skisExecutor = skisExecutor;
  }

  public Optional<Pet> find(long id) {
    return skisExecutor.findById(PetMeta.ENTITY, id);
  }

  public List<Pet> findByName(String name) {
    PetTable pet = PetTable.PET;
    return skisExecutor.selectFrom(pet).where(pet.name().eq(name)).fetchList();
  }
}
```

## License

Licensed under the [Apache License 2.0](LICENSE).
