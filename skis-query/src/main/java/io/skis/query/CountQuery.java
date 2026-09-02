package io.skis.query;

/**
 * Opaque independent count plan used by {@link SelectQuery#fetchPage(PageRequest, CountQuery)}.
 *
 * <p>Create one with {@link SelectQuery#countQuery()} or
 * {@link NullableScalarQuery#countQuery()}. The source query contributes its table, predicate,
 * distinct shape, parameters, and execution options; ordering is not part of the count plan.
 * When it counts a different shape than the page content, the caller is responsible for ensuring
 * both cardinalities are equivalent.
 */
public sealed interface CountQuery permits DefaultCountQuery {}
