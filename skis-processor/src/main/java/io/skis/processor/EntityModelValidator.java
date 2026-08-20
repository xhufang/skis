package io.skis.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;

final class EntityModelValidator {

  List<ProcessingProblem> validate(EntityModel model) {
    List<ProcessingProblem> problems = new ArrayList<>();
    validateOptionalName(model.table().catalog(), "catalog name", model.type(), problems);
    validateOptionalName(model.table().schema(), "schema name", model.type(), problems);
    validateRequiredName(model.table().name(), "table name", model.type(), problems);
    if (model.properties().isEmpty()) {
      problems.add(
          problem(
              "SKIS013", "an entity must contain at least one persistent property", model.type()));
    }
    if (!model.readOnly() && model.primaryKey().isEmpty()) {
      problems.add(
          problem(
              "SKIS014", "a writable entity must declare at least one @Id property", model.type()));
    }
    if (model.primaryKey().size() > 1) {
      problems.add(
          problem(
              "SKIS020",
              "multiple @Id properties require an explicit composite-key declaration",
              model.type()));
    }
    int versionCount = 0;
    Map<String, PropertyModel> writableColumns = new HashMap<>();
    Set<String> generatedFields = new HashSet<>();
    Set<String> generatedMethods = new HashSet<>();
    for (PropertyModel property : model.properties()) {
      ColumnModel column = property.column();
      validateRequiredName(column.name(), "column name", property.element(), problems);
      validateColumnShape(property, problems);
      if (!generatedFields.add(property.fieldName())) {
        problems.add(
            problem(
                "SKIS016",
                "property names produce a duplicate generated field",
                property.element()));
      }
      if (!generatedMethods.add(property.tableMethodName())) {
        problems.add(
            problem(
                "SKIS021", "property names produce a duplicate table method", property.element()));
      }
      if (property.valueKind() == JdbcValueKind.UNSUPPORTED) {
        problems.add(
            problem(
                "SKIS022",
                "persistent property type '" + property.typeName() + "' has no built-in JDBC codec",
                property.element()));
      }
      if (property.primitive() && column.nullable()) {
        problems.add(
            problem(
                "SKIS023",
                "a primitive property cannot map to a nullable column",
                property.element()));
      }
      if (property.id() && (column.nullable() || column.explicitlyNullable())) {
        problems.add(
            problem(
                "SKIS008",
                "an @Id property is implicitly non-null and must not declare @Column(nullable = true)",
                property.element()));
      }
      if (property.id() && property.version()) {
        problems.add(
            problem(
                "SKIS024",
                "a version property must not be part of the primary key",
                property.element()));
      }
      if (property.version()) {
        versionCount++;
        if (column.explicitlyNullable()) {
          problems.add(
              problem(
                  "SKIS011",
                  "a @Version property is implicitly non-null and must not declare @Column(nullable = true)",
                  property.element()));
        }
        if (!property.valueKind().numeric()) {
          problems.add(
              problem(
                  "SKIS010",
                  "@Version with NUMERIC_INCREMENT requires Byte, Short, Integer, Long, BigInteger, or BigDecimal",
                  property.element()));
        }
        if (!column.insertable() || !column.updatable()) {
          problems.add(
              problem(
                  "SKIS030",
                  "a NUMERIC_INCREMENT version column must be insertable and updatable",
                  property.element()));
        }
      }
      if (column.insertable() || column.updatable()) {
        PropertyModel previous = writableColumns.putIfAbsent(column.name(), property);
        if (previous != null) {
          problems.add(
              problem(
                  "SKIS012",
                  "properties '"
                      + previous.name()
                      + "' and '"
                      + property.name()
                      + "' map to the same writable column '"
                      + column.name()
                      + "'",
                  property.element()));
        }
      }
    }
    if (versionCount > 1) {
      problems.add(
          problem("SKIS009", "an entity may declare only one @Version property", model.type()));
    }
    if (model.readOnly() && versionCount != 0) {
      problems.add(
          problem(
              "SKIS015", "a read-only entity must not declare a @Version property", model.type()));
    }
    for (RecordComponentModel component : model.components()) {
      if (!component.element().getAccessor().getModifiers().contains(Modifier.PUBLIC)) {
        problems.add(
            problem(
                "SKIS025",
                "the generated row decoder cannot access record component '"
                    + component.name()
                    + "'",
                component.element()));
      }
    }
    return List.copyOf(problems);
  }

  private static void validateColumnShape(
      PropertyModel property, List<ProcessingProblem> problems) {
    ColumnModel column = property.column();
    if (column.length() < 0) {
      problems.add(problem("SKIS026", "column length must not be negative", property.element()));
    }
    if (column.precision() < 0) {
      problems.add(problem("SKIS027", "column precision must not be negative", property.element()));
    }
    if (column.scale() < 0) {
      problems.add(problem("SKIS028", "column scale must not be negative", property.element()));
    }
    if (column.precision() > 0 && column.scale() > column.precision()) {
      problems.add(
          problem("SKIS029", "column scale must not exceed precision", property.element()));
    }
  }

  private static void validateOptionalName(
      String value, String label, Element element, List<ProcessingProblem> problems) {
    if (!value.isEmpty() && value.isBlank()) {
      problems.add(problem("SKIS019", label + " must not contain only whitespace", element));
    }
  }

  private static void validateRequiredName(
      String value, String label, Element element, List<ProcessingProblem> problems) {
    if (value.isBlank()) {
      problems.add(problem("SKIS019", label + " must not be blank", element));
    }
  }

  private static ProcessingProblem problem(String code, String message, Element element) {
    return new ProcessingProblem(code, message, element);
  }
}
