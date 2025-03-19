package no.sikt.graphql.helpers.instrumentation;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DelegatingDataFetchingEnvironment;
import no.sikt.graphql.helpers.resolvers.EnvironmentHandler;
import org.jooq.DSLContext;

import static no.sikt.graphql.naming.LocalContextNames.DSL_CONTEXT;

public final class LocalContextHelper {
    public static DataFetchingEnvironment addDSLContextToLocalContext(DSLContext context, DataFetchingEnvironment env) {
        return addToLocalContext(DSL_CONTEXT.getName(), context, env);
    }

    public static DataFetchingEnvironment addToLocalContext(String name, Object object, DataFetchingEnvironment env) {
        return new DelegatingDataFetchingEnvironment(env) {
            @Override
            public <T> T getLocalContext() {
                var localContextMap = new EnvironmentHandler(env).getLocalContext();
                localContextMap.put(name, object);
                return (T) localContextMap;
            }
        };
    }
}
