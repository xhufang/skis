# SKIS 0.1 JDBC type mappings

This document defines the JDBC mapping contract implemented by SKIS 0.1. It is intentionally
limited to the Java types already recognized by the annotation processor. The contract tests use
the Spring Boot 4.1.0 BOM baselines: PostgreSQL JDBC 42.7.11, PostgreSQL 16, and H2 2.4.240.

Applications still own their DDL. A Java type is supported only when the database column has a
compatible type and sufficient precision, scale, or length.

## Portable entity mappings

| Java type | JDBC null type | PostgreSQL column | H2 column | Contract |
| --- | --- | --- | --- | --- |
| `boolean` / `Boolean` | `BOOLEAN` | `BOOLEAN` | `BOOLEAN` | Uses `getBoolean` / `setBoolean`. |
| `byte` / `Byte` | `TINYINT` | `SMALLINT` | `TINYINT` | PostgreSQL JDBC maps `TINYINT` parameters to `int2`; reads outside the Java byte range fail. |
| `short` / `Short` | `SMALLINT` | `SMALLINT` | `SMALLINT` | Reads outside the Java short range fail. |
| `int` / `Integer` | `INTEGER` | `INTEGER` | `INTEGER` | Reads outside the Java integer range fail. |
| `long` / `Long` | `BIGINT` | `BIGINT` | `BIGINT` | Reads outside the Java long range fail. |
| `float` / `Float` | `REAL` | `REAL` | `REAL` | Approximate binary floating-point mapping; exact decimal equality is not promised. |
| `double` / `Double` | `DOUBLE` | `DOUBLE PRECISION` | `DOUBLE PRECISION` | Approximate binary floating-point mapping; exact decimal equality is not promised. |
| `char` / `Character` | `CHAR` | `CHARACTER(1)` | `CHARACTER(1)` | The returned Java string must contain exactly one UTF-16 code unit. Use `String` for supplementary code points or longer text. |
| `String` | `VARCHAR` | `VARCHAR` or `TEXT` | `VARCHAR` | Uses `getString` / `setString`; column length remains an application DDL constraint. |
| `BigInteger` | `NUMERIC` | `NUMERIC(p, 0)` | `NUMERIC(p, 0)` | Reads through `BigDecimal.toBigIntegerExact`; fractional values fail instead of being truncated. |
| `BigDecimal` | `DECIMAL` | `NUMERIC(p, s)` | `NUMERIC(p, s)` | SKIS does not round values; database precision and scale determine accepted values and stored scale. |
| `byte[]` | `VARBINARY` | `BYTEA` | `VARBINARY` | Uses `getBytes` / `setBytes`; `null` and an empty array remain distinct. |
| `UUID` | `OTHER` | `UUID` | `UUID` | Uses JDBC typed `getObject` and native UUID binding, without string conversion. |
| `Instant` | `TIMESTAMP_WITH_TIMEZONE` | `TIMESTAMP(p) WITH TIME ZONE` | `TIMESTAMP(p) WITH TIME ZONE` | Bound as a UTC `OffsetDateTime` and read back as an instant. |
| `LocalDate` | `DATE` | `DATE` | `DATE` | JDBC 4.2 `LocalDate`; no time-zone conversion is part of the mapping. |
| `LocalTime` | `TIME` | `TIME(p) WITHOUT TIME ZONE` | `TIME(p)` | JDBC 4.2 `LocalTime`; database precision controls fractional-second truncation. |
| `LocalDateTime` | `TIMESTAMP` | `TIMESTAMP(p) WITHOUT TIME ZONE` | `TIMESTAMP(p)` | JDBC 4.2 wall-clock timestamp with no time-zone conversion. |
| `OffsetTime` | `TIME_WITH_TIMEZONE` | `TIME(p) WITH TIME ZONE` | `TIME(p) WITH TIME ZONE` | Preserves local time and offset to the precision supported by the column and driver. |
| `OffsetDateTime` | `TIMESTAMP_WITH_TIMEZONE` | `TIMESTAMP(p) WITH TIME ZONE` | `TIMESTAMP(p) WITH TIME ZONE` | The portable contract preserves the instant. PostgreSQL normalizes the returned offset to UTC. |
| `java.sql.Date` | `DATE` | `DATE` | `DATE` | Legacy JDBC compatibility mapping using `getDate` / `setDate`. Prefer `LocalDate`. |
| `java.sql.Time` | `TIME` | `TIME WITHOUT TIME ZONE` | `TIME` | Legacy JDBC compatibility mapping using `getTime` / `setTime`; fractional seconds are not promised. Prefer `LocalTime`. |
| `java.sql.Timestamp` | `TIMESTAMP` | `TIMESTAMP(p) WITHOUT TIME ZONE` | `TIMESTAMP(p)` | Legacy JDBC compatibility mapping using `getTimestamp` / `setTimestamp`. Prefer `LocalDateTime` or `Instant` according to the column semantics. |

`FLOAT` in the table means the Java `float` codec, not the PostgreSQL `FLOAT(p)` alias. Use the
listed concrete column types when writing portable DDL.

## Nullability

- Primitive Java properties cannot represent SQL `NULL`. Except for `@Id` and `@Version`, which
  already imply non-null columns, they must declare `@Column(nullable = false)`. Otherwise APT
  reports `SKIS023`.
- Wrapper and other reference types may map nullable columns. A SQL `NULL` is decoded as Java
  `null`, and a Java `null` is bound with the concrete JDBC type listed above.
- A reference property declared `nullable = false` is checked by generated code on both read and
  bind. A database `NULL` or Java `null` fails with `SQLException` before it can be mistaken for a
  normal value.
- Primitive reads check `ResultSet.wasNull()` so SQL `NULL` never becomes `0`, `false`, or another
  primitive default.

## Numeric and character boundaries

Narrow primitive getters are used on the generated hot path. PostgreSQL and H2 contract tests
require out-of-range conversions to raise `SQLException`. `BigInteger` additionally rejects any
fractional value explicitly. `BigDecimal`, `float`, and `double` do not apply an ORM-side precision
or rounding policy; applications must choose appropriate column definitions.

`char` and `Character` represent one Java UTF-16 code unit. Empty strings, padded multi-character
values, and longer strings fail decoding. This avoids silently taking the first character.

## Time and time-zone semantics

JSR-310 types use JDBC 4.2 typed `ResultSet.getObject(index, Type.class)` reads and native
`PreparedStatement.setObject(index, value)` writes. This avoids the default-time-zone conversions
introduced by converting `LocalDate` or `LocalDateTime` through legacy `java.sql` values.

Database column precision remains visible. PostgreSQL commonly stores microseconds; contract values
therefore use six fractional digits. SKIS does not pad or round temporal values in memory.
PostgreSQL `TIMESTAMP WITH TIME ZONE` stores an instant rather than the original textual offset, so
`OffsetDateTime` round trips compare by instant. `Instant` is normalized through UTC explicitly.

## PostgreSQL JSON and JSONB text

`PostgreSqlCodecs.JSON` is a low-level `JdbcTypeCodec<String>` for both PostgreSQL `JSON` and
`JSONB` columns. It uses `getString` and binds the complete text as one `Types.OTHER` parameter.
PostgreSQL performs syntax validation and target-column conversion. JSONB may normalize whitespace
and object-key ordering, so its returned text is not guaranteed to be byte-for-byte identical.

This codec does not depend on `PGobject` and does not introduce a PostgreSQL driver dependency into
`skis-mapping` or other core modules. SKIS 0.1 has no public annotation or registration API for
selecting this codec on an entity property; generated `String` properties continue to use the
portable string codec. A JSON mapping annotation or codec registration SPI is a later-version API.

## Unsupported mappings

APT continues to report `SKIS022` for mappings without a built-in codec, including:

- enums;
- arbitrary user value objects and converters;
- `Blob`, `Clob`, streams, and LOB annotations;
- arrays other than primitive `byte[]`, including `Byte[]`;
- structured JSON object mapping;
- database-specific arrays and proprietary driver objects.

Adding any of these requires a later minor version. A new public codec registration SPI also
requires an ADR.
