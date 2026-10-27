package io.skis.processor;

import io.skis.annotations.IdStrategy;
import javax.lang.model.element.Element;

record PropertyModel(
    int ordinal,
    String name,
    String fieldName,
    String tableMethodName,
    String typeName,
    String classLiteral,
    JdbcValueKind valueKind,
    boolean primitive,
    ColumnModel column,
    boolean id,
    IdStrategy idStrategy,
    boolean version,
    PropertyAccessModel access,
    Element element) {}
