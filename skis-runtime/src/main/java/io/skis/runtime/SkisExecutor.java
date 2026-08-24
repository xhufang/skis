package io.skis.runtime;

import io.skis.query.QueryOperations;

/**
 * Thread-safe injected facade for all SKIS database operations.
 *
 * <p>0.0.6 exposes the query facet. Mutation and graph facets will be added to this same facade so
 * application services keep one constructor-injected dependency.
 */
public interface SkisExecutor extends QueryOperations {}
