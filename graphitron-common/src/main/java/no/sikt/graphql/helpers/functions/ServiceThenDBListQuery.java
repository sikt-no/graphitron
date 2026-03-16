package no.sikt.graphql.helpers.functions;

import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

import java.util.Map;
import java.util.Set;

/**
 * Functional interface for a batch DB query that fetches multiple records by a set of keys.
 * Used when a service returns a list of records, each with a primary key populated.
 * The keys are extracted from the service results and passed as a set to the DB method,
 * which returns a map from key to result.
 *
 * @param <K> The key type (e.g., a jOOQ Row1 representing the primary key).
 * @param <U> The type returned by the DB query (e.g., a GraphQL DTO).
 */
@FunctionalInterface
public interface ServiceThenDBListQuery<K, U> {
    Map<K, U> callDBMethod(DSLContext ctx, Set<K> keys, SelectionSet selectionSet);
}
