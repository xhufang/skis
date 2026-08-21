package io.skis.processor;

import java.util.Objects;

record PropertyAccessModel(Access read, Access write) {

  private static final String ENTITY_VARIABLE = "entity";

  PropertyAccessModel {
    Objects.requireNonNull(read, "read");
  }

  static PropertyAccessModel recordAccessor(String methodName) {
    return new PropertyAccessModel(Access.method(methodName), null);
  }

  static PropertyAccessModel bean(Access read, Access write) {
    return new PropertyAccessModel(read, Objects.requireNonNull(write, "write"));
  }

  String readEntityExpression() {
    return read.expression();
  }

  String writeEntityStatement(String valueExpression) {
    if (write == null) {
      throw new IllegalStateException("the property is not writable after construction");
    }
    return write.assignment(valueExpression);
  }

  record Access(Kind kind, String memberName) {

    Access {
      Objects.requireNonNull(kind, "kind");
      if (memberName == null || memberName.isBlank()) {
        throw new IllegalArgumentException("memberName must not be blank");
      }
    }

    static Access method(String methodName) {
      return new Access(Kind.METHOD, methodName);
    }

    static Access field(String fieldName) {
      return new Access(Kind.FIELD, fieldName);
    }

    String expression() {
      return switch (kind) {
        case METHOD -> ENTITY_VARIABLE + "." + memberName + "()";
        case FIELD -> ENTITY_VARIABLE + "." + memberName;
      };
    }

    String assignment(String valueExpression) {
      return switch (kind) {
        case METHOD -> ENTITY_VARIABLE + "." + memberName + "(" + valueExpression + ");";
        case FIELD -> ENTITY_VARIABLE + "." + memberName + " = " + valueExpression + ";";
      };
    }
  }

  enum Kind {
    METHOD,
    FIELD
  }
}
