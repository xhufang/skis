package io.skis.processor;

import java.util.List;
import java.util.Objects;

record EntityInstantiationModel(Kind kind, List<ConstructorArgument> constructorArguments) {

  EntityInstantiationModel {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(constructorArguments, "constructorArguments");
    constructorArguments = List.copyOf(constructorArguments);
    if (kind == Kind.BEAN && !constructorArguments.isEmpty()) {
      throw new IllegalArgumentException(
          "bean instantiation must not declare constructor arguments");
    }
  }

  static EntityInstantiationModel recordConstructor(List<ConstructorArgument> arguments) {
    return new EntityInstantiationModel(Kind.RECORD_CONSTRUCTOR, arguments);
  }

  static EntityInstantiationModel bean() {
    return new EntityInstantiationModel(Kind.BEAN, List.of());
  }

  record ConstructorArgument(PropertyModel property, String defaultExpression) {

    ConstructorArgument {
      if ((property == null) == (defaultExpression == null || defaultExpression.isBlank())) {
        throw new IllegalArgumentException(
            "a constructor argument must contain exactly one property or default expression");
      }
    }

    static ConstructorArgument property(PropertyModel property) {
      return new ConstructorArgument(Objects.requireNonNull(property, "property"), null);
    }

    static ConstructorArgument defaultValue(String expression) {
      return new ConstructorArgument(null, expression);
    }
  }

  enum Kind {
    RECORD_CONSTRUCTOR,
    BEAN
  }
}
