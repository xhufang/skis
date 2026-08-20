package io.skis.processor;

record ColumnModel(
    String name,
    boolean nullable,
    boolean explicitlyNullable,
    boolean insertable,
    boolean updatable,
    int length,
    int precision,
    int scale,
    String comment) {}
