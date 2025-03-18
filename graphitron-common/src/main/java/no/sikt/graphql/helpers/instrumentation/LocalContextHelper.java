package no.sikt.graphql.helpers.instrumentation;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DelegatingDataFetchingEnvironment;
import org.jooq.DSLContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphql.naming.LocalContextNames.DSL_CONTEXT;

public final class LocalContextHelper {
    public static DataFetchingEnvironment addDSLContextToLocalContext(DSLContext context, DataFetchingEnvironment env) {
        return addToLocalContext(DSL_CONTEXT.getName(), context, env);
    }

    public static DataFetchingEnvironment addToLocalContext(String name, Object object, DataFetchingEnvironment env) {
        return new DelegatingDataFetchingEnvironment(env) {
            @Override
            public <T> T getLocalContext() {
                var localContext = env.getLocalContext();
                var localContextMap = Optional.of((Map<String, Object>) localContext).orElse(new HashMap<>());
                localContextMap.put(name, object);
                return (T) localContextMap;
            }
        };
    }
}
