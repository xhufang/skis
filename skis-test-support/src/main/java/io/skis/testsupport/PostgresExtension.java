package io.skis.testsupport;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.postgresql.ds.PGSimpleDataSource;

/** JUnit root owns the server; class stores close their databases before the root closes. */
public final class PostgresExtension implements BeforeAllCallback, ParameterResolver {
  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(PostgresExtension.class);

  @Override
  public void beforeAll(ExtensionContext context) {
    PostgresEnvironment environment = context.getRoot().getStore(NAMESPACE).computeIfAbsent(
        PostgresEnvironment.class, ignored -> PostgresEnvironment.open(), PostgresEnvironment.class);
    context.getStore(NAMESPACE).computeIfAbsent(
        PostgresEnvironment.Database.class, ignored -> environment.database(),
        PostgresEnvironment.Database.class);
  }

  @Override
  public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
    return parameter.getParameter().getType() == PGSimpleDataSource.class
        || parameter.getParameter().getType() == PostgresEnvironment.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
    if (parameter.getParameter().getType() == PostgresEnvironment.class) {
      PostgresEnvironment environment = context.getRoot().getStore(NAMESPACE).get(
          PostgresEnvironment.class, PostgresEnvironment.class);
      if (environment == null) {
        throw new IllegalStateException("PostgreSQL root environment was not initialized");
      }
      return environment;
    }
    PostgresEnvironment.Database database = context.getStore(NAMESPACE).get(
        PostgresEnvironment.Database.class, PostgresEnvironment.Database.class);
    if (database == null) {
      throw new IllegalStateException("PostgreSQL class environment was not initialized");
    }
    return database.dataSource();
  }
}
