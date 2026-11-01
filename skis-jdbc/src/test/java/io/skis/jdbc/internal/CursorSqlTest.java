package io.skis.jdbc.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CursorSqlTest {
  @Test void recognizesOneStatementWithoutTreatingQuotedOrCommentedSemicolonsAsSeparators() {
    for (String sql : List.of("SELECT 1", "SELECT 1; -- done", "SELECT \"semi;column\" FROM t",
        "SELECT 'it''s;quoted'", "SELECT $$body;here$$", "SELECT $tag$body;here$tag$",
        "/* outer /* nested; */ comment */ SELECT 1; /* tail */")) {
      assertTrue(CursorSql.isSingleStatement(sql), sql);
    }
  }

  @Test void rejectsMultipleEmptyUnclosedOrAmbiguousStatements() {
    for (String sql : List.of("", "-- comment", ";", "SELECT 1;;", "SELECT 1; SELECT 2",
        "SELECT 1 -- line\r; SELECT 2",
        "SELECT 'unclosed", "SELECT \"unclosed", "SELECT $$unclosed", "SELECT 1 /* unclosed",
        "SELECT E'back\\slash'")) {
      assertFalse(CursorSql.isSingleStatement(sql), sql);
    }
  }
}
