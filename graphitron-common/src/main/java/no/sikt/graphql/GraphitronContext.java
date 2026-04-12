package no.sikt.graphql;

import graphql.schema.DataFetchingEnvironment;
import org.jooq.DSLContext;

public interface GraphitronContext {
    /***
     * Used by Graphitron so it can access the database.
     *
     * @param env An object containing information about what is being fetched etc. See https://www.graphql-java.com/documentation/data-fetching/#the-interesting-parts-of-the-datafetchingenvironment for more information.
     * @return The jOOQ DSLContext that Graphitron should use.
     */
    DSLContext getDslContext(DataFetchingEnvironment env);

    /***
     * Used by Graphitron to get the values for contextArguments.
     * @param env An object containing information about what is being fetched etc. See https://www.graphql-java.com/documentation/data-fetching/#the-interesting-parts-of-the-datafetchingenvironment for more information.
     * @param name The name of the contextArgument we're looking for
     * @return The value of the contextArgument
     * @param <T> The type of the contextArgument
     */
    <T> T getContextArgument(DataFetchingEnvironment env, String name);

    /**
     * Returns the name under which Graphitron will register and look up the DataLoader for the
     * field currently being resolved.
     *
     * <p>The name must uniquely identify the <em>(parent type, field)</em> pair — for example
     * {@code "Film/actors"} or {@code "User/orders"}. Graphitron calls this method once per source
     * object (e.g. once per Film row) and uses {@code DataLoaderRegistry.computeIfAbsent} so that
     * all calls for the same field within a single request share one DataLoader instance, enabling
     * batching.
     *
     * <p><strong>Why the name must encode the field path:</strong> GraphQL-Java merges selection
     * sets for all occurrences of the same field within a request, so every DataLoader consumer
     * for a given (parent type, field) pair sees an identical selection set. A name that encodes
     * the field path therefore guarantees both correct batching and a consistent view of which
     * columns to fetch. Using the same name for different fields would cause them to share a
     * DataLoader and batch incorrectly.
     *
     * <p>The {@code env} parameter gives access to {@code env.getParentType().getName()} and
     * {@code env.getField().getName()} if you want to derive the name automatically.
     *
     * @param env the DataFetchingEnvironment for the field currently being resolved
     * @return a name that is unique per (parent type, field) pair
     */
    String getDataLoaderName(DataFetchingEnvironment env);
}
