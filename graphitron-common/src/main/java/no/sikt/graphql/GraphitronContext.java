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
     * Returns the tenant identifier for the current request, or an empty string when tenant
     * scoping does not apply. Graphitron combines this with the field path (list indices
     * stripped) to build DataLoader names — two tenants issuing the same query must not share
     * a DataLoader cache, otherwise one tenant can observe the other's data.
     *
     * <p>Default implementation returns an empty string. Applications with multi-tenant data
     * separation override this to return a per-request tenant identifier (extracted from
     * {@code env.getGraphQlContext()}, a JWT claim, or similar).
     *
     * <p>Unlike {@link #getDataLoaderName}, this method's output is concatenated by Graphitron
     * with a path segment that Graphitron controls — implementations cannot accidentally
     * produce a DataLoader name that collides across field paths or aliases.
     */
    default String getTenantId(DataFetchingEnvironment env) {
        return "";
    }

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
     * <p><strong>Legacy only.</strong> The rewrite emitter (argres Phase 2b onwards) no longer
     * calls this method — it constructs the DataLoader name from {@link #getTenantId} combined
     * with {@code env.getExecutionStepInfo().getPath().getKeysOnly()} directly, so the path
     * handling is always correct and only the tenant prefix is pluggable. Legacy generated
     * code and legacy helpers ({@code EnvironmentHandler}) still consume this method.
     *
     * @param env the DataFetchingEnvironment for the field currently being resolved
     * @return a name that is unique per field path (without list indices) within the query
     */
    String getDataLoaderName(DataFetchingEnvironment env);
}
