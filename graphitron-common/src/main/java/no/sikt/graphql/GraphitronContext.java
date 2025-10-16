package no.sikt.graphql;

import graphql.schema.DataFetchingEnvironment;
import org.jooq.DSLContext;

public interface GraphitronContext {
    DSLContext getDslContext(DataFetchingEnvironment env);

    <T> T getContextArgument(DataFetchingEnvironment env, String name);

    String getDataLoaderName(DataFetchingEnvironment env);
}
