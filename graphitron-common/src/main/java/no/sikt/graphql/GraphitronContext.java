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
}
