package io.skis.testmodel.types;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Shared generated-mapping fixture for the PostgreSQL and H2 JDBC type contracts. */
@SkisEntity
@Table(schema = "skis_types", name = "jdbc_types")
public record JdbcTypes(
    @Id long id,
    @Column(nullable = false) boolean primitiveBoolean,
    @Nullable Boolean nullableBoolean,
    @Column(nullable = false) byte primitiveByte,
    @Nullable Byte nullableByte,
    @Column(nullable = false) short primitiveShort,
    @Nullable Short nullableShort,
    @Column(nullable = false) int primitiveInteger,
    @Nullable Integer nullableInteger,
    @Column(nullable = false) long primitiveLong,
    @Nullable Long nullableLong,
    @Column(nullable = false) float primitiveFloat,
    @Nullable Float nullableFloat,
    @Column(nullable = false) double primitiveDouble,
    @Nullable Double nullableDouble,
    @Column(nullable = false) char primitiveCharacter,
    @Nullable Character nullableCharacter,
    @Column(nullable = false, length = 200) String requiredString,
    @Nullable String nullableString,
    @Nullable BigInteger bigIntegerValue,
    @Nullable BigDecimal bigDecimalValue,
    byte @Nullable [] bytesValue,
    @Nullable UUID uuidValue,
    @Nullable Instant instantValue,
    @Nullable LocalDate localDateValue,
    @Nullable LocalTime localTimeValue,
    @Nullable LocalDateTime localDateTimeValue,
    @Nullable OffsetTime offsetTimeValue,
    @Nullable OffsetDateTime offsetDateTimeValue,
    @Nullable Date sqlDateValue,
    @Nullable Time sqlTimeValue,
    @Nullable Timestamp sqlTimestampValue) {}
