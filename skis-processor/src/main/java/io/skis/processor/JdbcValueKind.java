package io.skis.processor;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.type.TypeKind;

/** Compile-time JDBC mapping descriptor shared by scanning and source generation. */
enum JdbcValueKind {
  BOOLEAN(
      TypeKind.BOOLEAN,
      "java.lang.Boolean",
      "readBoolean",
      "readNullableBoolean",
      "bindBoolean",
      "bindNullableBoolean",
      false),
  BYTE(
      TypeKind.BYTE,
      "java.lang.Byte",
      "readByte",
      "readNullableByte",
      "bindByte",
      "bindNullableByte",
      true),
  SHORT(
      TypeKind.SHORT,
      "java.lang.Short",
      "readShort",
      "readNullableShort",
      "bindShort",
      "bindNullableShort",
      true),
  INTEGER(
      TypeKind.INT,
      "java.lang.Integer",
      "readInt",
      "readNullableInt",
      "bindInt",
      "bindNullableInt",
      true),
  LONG(
      TypeKind.LONG,
      "java.lang.Long",
      "readLong",
      "readNullableLong",
      "bindLong",
      "bindNullableLong",
      true),
  FLOAT(
      TypeKind.FLOAT,
      "java.lang.Float",
      "readFloat",
      "readNullableFloat",
      "bindFloat",
      "bindNullableFloat",
      false),
  DOUBLE(
      TypeKind.DOUBLE,
      "java.lang.Double",
      "readDouble",
      "readNullableDouble",
      "bindDouble",
      "bindNullableDouble",
      false),
  CHARACTER(
      TypeKind.CHAR,
      "java.lang.Character",
      "readChar",
      "readNullableChar",
      "bindChar",
      "bindNullableChar",
      false),
  STRING(null, "java.lang.String", "", "readString", "", "bindString", false),
  BIG_INTEGER(null, "java.math.BigInteger", "", "readBigInteger", "", "bindBigInteger", true),
  BIG_DECIMAL(null, "java.math.BigDecimal", "", "readBigDecimal", "", "bindBigDecimal", true),
  BYTES(null, "byte[]", "", "readBytes", "", "bindBytes", false),
  UUID(null, "java.util.UUID", "", "readUuid", "", "bindUuid", false),
  INSTANT(null, "java.time.Instant", "", "readInstant", "", "bindInstant", false),
  LOCAL_DATE(null, "java.time.LocalDate", "", "readLocalDate", "", "bindLocalDate", false),
  LOCAL_TIME(null, "java.time.LocalTime", "", "readLocalTime", "", "bindLocalTime", false),
  LOCAL_DATE_TIME(
      null, "java.time.LocalDateTime", "", "readLocalDateTime", "", "bindLocalDateTime", false),
  OFFSET_TIME(null, "java.time.OffsetTime", "", "readOffsetTime", "", "bindOffsetTime", false),
  OFFSET_DATE_TIME(
      null, "java.time.OffsetDateTime", "", "readOffsetDateTime", "", "bindOffsetDateTime", false),
  SQL_DATE(null, "java.sql.Date", "", "readSqlDate", "", "bindSqlDate", false),
  SQL_TIME(null, "java.sql.Time", "", "readSqlTime", "", "bindSqlTime", false),
  SQL_TIMESTAMP(null, "java.sql.Timestamp", "", "readSqlTimestamp", "", "bindSqlTimestamp", false),
  UNSUPPORTED(null, "", "", "", "", "", false);

  private static final Map<TypeKind, JdbcValueKind> PRIMITIVE_KINDS;
  private static final Map<String, JdbcValueKind> DECLARED_TYPES;

  static {
    Map<TypeKind, JdbcValueKind> primitiveKinds = new EnumMap<>(TypeKind.class);
    Map<String, JdbcValueKind> declaredTypes = new HashMap<>();
    for (JdbcValueKind valueKind : values()) {
      if (valueKind.primitiveKind != null) {
        primitiveKinds.put(valueKind.primitiveKind, valueKind);
      }
      if (!valueKind.javaTypeName.isEmpty()) {
        declaredTypes.put(valueKind.javaTypeName, valueKind);
      }
    }
    PRIMITIVE_KINDS = Map.copyOf(primitiveKinds);
    DECLARED_TYPES = Map.copyOf(declaredTypes);
  }

  private final TypeKind primitiveKind;
  private final String javaTypeName;
  private final String primitiveReadMethod;
  private final String referenceReadMethod;
  private final String primitiveBindMethod;
  private final String referenceBindMethod;
  private final boolean numericVersion;

  JdbcValueKind(
      TypeKind primitiveKind,
      String javaTypeName,
      String primitiveReadMethod,
      String referenceReadMethod,
      String primitiveBindMethod,
      String referenceBindMethod,
      boolean numericVersion) {
    this.primitiveKind = primitiveKind;
    this.javaTypeName = javaTypeName;
    this.primitiveReadMethod = primitiveReadMethod;
    this.referenceReadMethod = referenceReadMethod;
    this.primitiveBindMethod = primitiveBindMethod;
    this.referenceBindMethod = referenceBindMethod;
    this.numericVersion = numericVersion;
  }

  static JdbcValueKind forPrimitive(TypeKind typeKind) {
    return PRIMITIVE_KINDS.getOrDefault(typeKind, UNSUPPORTED);
  }

  static JdbcValueKind forDeclared(String qualifiedName) {
    return DECLARED_TYPES.getOrDefault(qualifiedName, UNSUPPORTED);
  }

  String readMethod(boolean primitive) {
    return requireMethod(primitive ? primitiveReadMethod : referenceReadMethod, "read");
  }

  String bindMethod(boolean primitive) {
    return requireMethod(primitive ? primitiveBindMethod : referenceBindMethod, "bind");
  }

  boolean numeric() {
    return numericVersion;
  }

  private String requireMethod(String method, String operation) {
    if (method.isEmpty()) {
      throw new IllegalStateException(
          "JDBC value kind " + this + " has no " + operation + " method for this Java type");
    }
    return method;
  }
}
