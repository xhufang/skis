package io.skis.mapping;

import io.skis.metadata.PropertyMeta;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Generated, reflection-free JDBC behavior for one persistent property. */
public final class PropertyRuntime<E, V> {

  private final PropertyMeta<E, V> property;
  private final JdbcTypeCodec<V> codec;

  /** Creates a runtime property mapping from canonical metadata and its JDBC codec. */
  public PropertyRuntime(PropertyMeta<E, V> property, JdbcTypeCodec<V> codec) {
    this.property = Objects.requireNonNull(property, "property");
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  public PropertyMeta<E, V> property() {
    return property;
  }

  public JdbcTypeCodec<V> codec() {
    return codec;
  }

  /** Binds a dynamically supplied value after checking it against generated property metadata. */
  public void bind(
      PreparedStatement statement, int index, @Nullable Object value, JdbcWriteContext context)
      throws SQLException {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(context, "context");
    if (index < 1) {
      throw new IllegalArgumentException("JDBC parameter index must be positive");
    }
    if (value == null) {
      if (!property.column().nullable()) {
        throw new SQLException(
            "required property '" + property.name() + "' is null at JDBC parameter index " + index);
      }
      codec.bind(statement, index, null, context);
      return;
    }
    if (!property.javaType().isInstance(value)) {
      throw new IllegalArgumentException(
          "property '"
              + property.name()
              + "' requires "
              + property.javaType().getTypeName()
              + " but received "
              + value.getClass().getTypeName());
    }
    codec.bind(statement, index, property.javaType().cast(value), context);
  }
}
