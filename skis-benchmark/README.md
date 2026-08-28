# SKIS benchmarks

This reactor compares hand-written JDBC, the current SKIS reactor, and other ORM frameworks through
one JMH runner. Each framework owns its entity model and data-access implementation while all
implementations use the same PostgreSQL table, selected columns, row shape, connection-pool settings,
and benchmark parameters.

## Modules

- `skis-benchmark-jdbc`: hand-written JDBC-by-index baseline.
- `skis-benchmark-skis`: current SKIS entity and query implementation.
- `skis-benchmark-jimmer`: standalone Jimmer entity and query implementation.
- `skis-benchmark-mybatis`: standalone MyBatis annotation-mapper implementation.
- `skis-benchmark-mybaitisflex`: standalone MyBatis-Flex `BaseMapper` implementation.
- `skis-benchmark-mybatisplus`: standalone MyBatis-Plus `BaseMapper` implementation.
- `skis-benchmark-jooq`: standalone jOOQ dynamic-DSL implementation.
- `skis-benchmark-runner`: shared database configuration and JMH benchmarks.

The modules are plain Java projects. Spring Boot startup, auto-configuration, and proxy costs are not
part of these ORM hot-path measurements.

## Database configuration

The runner uses these environment variables:

| Variable | Required | Default |
|---|---:|---|
| `SKIS_BENCHMARK_JDBC_URL` | no | `jdbc:postgresql://localhost:5432/xhu?currentSchema=skis&stringtype=unspecified&lowercase=true` |
| `SKIS_BENCHMARK_DB_USERNAME` | no | `postgres` |
| `SKIS_BENCHMARK_DB_PASSWORD` | yes | none |
| `SKIS_BENCHMARK_POOL_SIZE` | no | `4` |

Credentials are intentionally not stored in the repository. The current benchmark expects an existing
`skis.skis_user` row with id `888`; the JMH trial setup fails before measurement if that row is absent.

## Running

Build the runner and its reactor dependencies, then launch the shaded JMH jar:

```powershell
$env:SKIS_BENCHMARK_DB_PASSWORD = Read-Host "PostgreSQL password"
.\mvnw.cmd -pl skis-benchmark/skis-benchmark-runner -am package
java -jar skis-benchmark/skis-benchmark-runner/target/benchmarks.jar
```

JMH runs the `jdbc`, `skis`, `jimmer`, `mybatis`, `mybatisFlex`, `mybatisPlus`, and `jooq` methods
independently. Use a JMH include expression to run only one implementation when diagnosing it, but
use the unified run for comparison reports.

## Comparison framework versions

| Framework | Version |
|---|---:|
| Jimmer | `0.11.4` |
| MyBatis | `3.5.19` |
| MyBatis-Flex | `1.11.4` |
| MyBatis-Plus | `3.5.17` |
| jOOQ | `3.21.7` |
