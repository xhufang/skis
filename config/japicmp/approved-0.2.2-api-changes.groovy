import static japicmp.model.JApiCompatibilityChangeType.METHOD_ABSTRACT_ADDED_IN_IMPLEMENTED_INTERFACE
import static japicmp.model.JApiCompatibilityChangeType.METHOD_ADDED_TO_INTERFACE

// Exact source-incompatible interface additions approved for the internal 0.2.2 line.
def approvedMethods = [
    'io.skis.query.EntitySelectQuery#and(io.skis.query.QueryPredicate)',
    'io.skis.query.EntitySelectQuery#or(io.skis.query.QueryPredicate)',
    'io.skis.query.ProjectedSelectQuery#and(io.skis.query.QueryPredicate)',
    'io.skis.query.ProjectedSelectQuery#or(io.skis.query.QueryPredicate)',
    'io.skis.sql.ast.SqlExpression#sqlType()',
    'io.skis.sql.ast.SqlExpression#nullability()'
] as Set

// SqlPredicate extends SqlExpression and intentionally inherits the new nullability contract.
def approvedInheritedContracts = [
    'io.skis.sql.ast.SqlPredicate'
] as Set

def approve = { change ->
  change.setBinaryCompatible(true)
  change.setSourceCompatible(true)
}

jApiClasses.each { jApiClass ->
  def className = jApiClass.getFullyQualifiedName()

  jApiClass.getMethods().each { method ->
    def parameters = method.getParameters().collect { it.getType() }.join(',')
    def methodKey = "${className}#${method.getName()}(${parameters})"
    method.getCompatibilityChanges().each { change ->
      if (change.getType() == METHOD_ADDED_TO_INTERFACE && approvedMethods.contains(methodKey)) {
        approve(change)
      }
      if (change.getType() == METHOD_ABSTRACT_ADDED_IN_IMPLEMENTED_INTERFACE
          && approvedInheritedContracts.contains(className)) {
        approve(change)
      }
    }
  }

  jApiClass.getCompatibilityChanges().each { change ->
    if (change.getType() == METHOD_ABSTRACT_ADDED_IN_IMPLEMENTED_INTERFACE
        && approvedInheritedContracts.contains(className)) {
      approve(change)
    }
  }

  jApiClass.getInterfaces().each { implementedInterface ->
    implementedInterface.getCompatibilityChanges().each { change ->
      if (change.getType() == METHOD_ABSTRACT_ADDED_IN_IMPLEMENTED_INTERFACE
          && approvedInheritedContracts.contains(className)) {
        approve(change)
      }
    }
  }
}

return jApiClasses
