package io.skis.query;

/**
 * Opaque independent count plan used by {@link ExecutableSelectQuery#fetchPage(PageRequest,
 * CountQuery)}.
 *
 * <p>Create one with {@link ExecutableSelectQuery#countQuery()}; only framework-created plans are
 * accepted. The source query contributes it's FROM/JOIN/ON/WHERE structure, distinct shape,
 * parameters, and execution options; ordering is not part of the count plan. When it counts a
 * different shape than the page content, the caller is responsible for ensuring both cardinalities
 * are equivalent.
 */
public interface CountQuery {}
