package no.sikt.graphitron.emit.stub;

import graphql.schema.DataFetchingEnvironment;
import org.jooq.DSLContext;

/** The generated runtime a call's fills read through: the request context and the session handle. */
public final class FillRuntime {

    private FillRuntime() {}

    public static Object contextArgument(DataFetchingEnvironment env, String key) {
        return env.getGraphQlContext().get(key);
    }

    public static Handle sessionHandle(DSLContext dsl, String coordinate) {
        return new Handle("handle for " + coordinate);
    }

    public static String sessionMember_label(DSLContext dsl, String coordinate) {
        return "label for " + coordinate;
    }
}
