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

    /***
     * Used by Graphitron when it needs to use a DataLoader to carry out the operation.
     * @param env An object containing information about what is being fetched etc. See https://www.graphql-java.com/documentation/data-fetching/#the-interesting-parts-of-the-datafetchingenvironment for more information.
     * @return The name of the DataLoader that should be used
     */
    String getDataLoaderName(DataFetchingEnvironment env);

    /***
     * Used by Graphitron to limit how many keys a DataLoader passes to a single database query. When a batch has more
     * keys than this, it is split into several queries of at most this many keys each.
     * <p>
     * A cap bounds the length of the SQL, the number of bind variables and the cost of each query. It also limits how
     * many fields fail when one query fails. The cost is more round trips to the database.
     * @param env An object containing information about what is being fetched etc. See https://www.graphql-java.com/documentation/data-fetching/#the-interesting-parts-of-the-datafetchingenvironment for more information.
     * @return The maximum number of keys per batch, or a value less than 1 for no limit (the default).
     */
    default int getDataLoaderMaxBatchSize(DataFetchingEnvironment env) {
        return -1;
    }
}
