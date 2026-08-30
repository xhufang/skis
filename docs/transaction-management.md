# Transaction management

SKIS 0.2 supports two deliberately separate transaction ownership models. Choose one model for a
configured `SkisExecutor`; do not nest one inside the other.

| Environment | Connection provider | Transaction boundary owner | Application entry point |
| --- | --- | --- | --- |
| Plain Java | `DataSourceConnectionProvider` | SKIS | `inTransaction` or `beginTransaction` |
| Spring Framework | `SpringConnectionProvider` | Spring `PlatformTransactionManager` | `@Transactional` or `TransactionTemplate` |

## Plain Java local transactions

The callback form is the recommended default. Returning normally requests a JDBC commit; an
unchecked application or SKIS failure requests a rollback.

```java
Pet stored =
    executor.inTransaction(
        session -> {
          session.insert(PetMeta.ENTITY, pet);
          session.afterCommit(() -> publishPetCreated(pet.id()));
          return session.findById(PetMeta.ENTITY, pet.id()).orElseThrow();
        });
```

`afterCommit` callbacks run in registration order, exactly once, and only after the JDBC driver
returns successfully from `Connection.commit()`. They are discarded on rollback and when the
commit result is unknown. If a callback throws a runtime exception, SKIS continues through the
remaining callbacks and throws `TransactionException` after all callbacks have been attempted; an
`Error` remains an `Error`. A callback failure explicitly means the database transaction is already
committed, so catching it must not trigger a retry of the database work.

Use an explicit session only when the application needs a visible completion boundary:

```java
try (SkisSession session = executor.beginTransaction()) {
  session.insert(PetMeta.ENTITY, pet);
  session.commit();
}
```

Closing an active session rolls it back. After a successful commit or rollback, SKIS restores the
original auto-commit mode and then releases the Connection. A commit or rollback failure has an
unknown database outcome: SKIS does not attempt another completion operation and does not switch
auto-commit back on, because either action could change an outcome that the driver could not
report. The Connection is still offered back to its provider. Restoration and release failures are
retained as primary or suppressed causes without replacing the earlier transaction failure.

Do not call `commit` or `rollback` inside an `inTransaction` callback, reuse a `SkisSession` after a
terminal operation, or share a session across threads.

## Spring Framework transactions

`skis-spring` is a Spring Framework integration module, not Spring Boot auto-configuration. Add it
explicitly and assemble the executor with `SpringConnectionProvider`:

```xml
<dependency>
  <groupId>io.github.xhufang</groupId>
  <artifactId>skis-spring</artifactId>
</dependency>
```

```java
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
class SkisConfiguration {

  @Bean
  PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new JdbcTransactionManager(dataSource);
  }

  @Bean
  SkisExecutor skisExecutor(DataSource dataSource) {
    return SkisExecutorFactory.create(
        new SpringConnectionProvider(dataSource), PostgreSqlDialect.INSTANCE);
  }
}
```

Application services use the normal Spring boundary:

```java
@Transactional
public void renamePet(Pet changedPet) {
  skisExecutor.updateById(PetMeta.ENTITY, changedPet);
}
```

`SpringConnectionProvider` obtains and releases connections through Spring `DataSourceUtils`.
Within one Spring transaction, SKIS operations reuse the transaction-bound Connection; releasing an
operation reference does not close that Connection. Spring alone commits, rolls back, and closes it
at transaction completion. Outside a transaction, the connection is released immediately after the
operation.

The provider deliberately reports that it does not support SKIS local transactions, so
`beginTransaction` and `inTransaction` fail before acquiring a Connection. Use Spring transaction
synchronization for post-commit work instead of `SkisSession.afterCommit`:

```java
TransactionSynchronizationManager.registerSynchronization(
    new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        publishPetChanged(petId);
      }
    });
```

Spring exception translation, SKIS statement options, nested transactions, savepoints, reactive
transactions, Spring Boot auto-configuration, and a starter are outside the 0.2 scope.
