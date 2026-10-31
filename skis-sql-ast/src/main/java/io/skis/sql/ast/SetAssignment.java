package io.skis.sql.ast;

/** One ordered SET item, which may assign one column or a complete column group. */
public sealed interface SetAssignment permits UpdateAssignment, RowAssignment {}
