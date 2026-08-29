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

/** Shared generated-mapping fixture for the PostgreSQL and H2 JDBC type contracts. */
@SkisEntity
@Table(schema = "skis_types", name = "jdbc_types")
public record JdbcTypes(
    @Id long id,
    @Column(nullable = false) boolean primitiveBoolean,
    Boolean nullableBoolean,
    @Column(nullable = false) byte primitiveByte,
    Byte nullableByte,
    @Column(nullable = false) short primitiveShort,
    Short nullableShort,
    @Column(nullable = false) int primitiveInteger,
    Integer nullableInteger,
    @Column(nullable = false) long primitiveLong,
    Long nullableLong,
    @Column(nullable = false) float primitiveFloat,
    Float nullableFloat,
    @Column(nullable = false) double primitiveDouble,
    Double nullableDouble,
    @Column(nullable = false) char primitiveCharacter,
    Character nullableCharacter,
    @Column(nullable = false, length = 200) String requiredString,
    String nullableString,
    BigInteger bigIntegerValue,
    BigDecimal bigDecimalValue,
    byte[] bytesValue,
    UUID uuidValue,
    Instant instantValue,
    LocalDate localDateValue,
    LocalTime localTimeValue,
    LocalDateTime localDateTimeValue,
    OffsetTime offsetTimeValue,
    OffsetDateTime offsetDateTimeValue,
    Date sqlDateValue,
    Time sqlTimeValue,
    Timestamp sqlTimestampValue) {}
