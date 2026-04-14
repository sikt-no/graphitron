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
     * <p>Graphitron calls this method once per source object (e.g. once per Film row) and uses
     * {@code DataLoaderRegistry.computeIfAbsent} so that all calls for the same field position
     * within a single request share one DataLoader instance, enabling batching.
     *
     * <p><strong>The name must encode the full field path, not just the parent type and field
     * name.</strong> Different parts of the same query can reach the same GraphQL type through
     * different paths with different arguments and selection sets:
     *
     * <pre>{@code
     * {
     *   user   { friends { orders(status: "open")   { id total  } } }
     *   topUser {          orders(status: "closed")  { id status } }
     * }
     * }</pre>
     *
     * Both resolve {@code User.orders}, but with different arguments and different selected fields.
     * Using {@code "User/orders"} as the name for both would batch them into one DataLoader,
     * causing incorrect results. The path — available as
     * {@code env.getExecutionStepInfo().getPath()} — distinguishes them:
     * {@code /user/friends/orders} vs {@code /topUser/orders}.
     *
     * <p>Strip integer segments (list indices) from the path before using it as the name:
     * {@code /user/friends/0/orders} and {@code /user/friends/1/orders} are the same field
     * position and must share a DataLoader for batching to work.
     *
     * @param env the DataFetchingEnvironment for the field currently being resolved
     * @return a name that is unique per field path (without list indices) within the query
     */
    String getDataLoaderName(DataFetchingEnvironment env);
}
