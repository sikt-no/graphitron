package no.sikt.graphitron.emit.stub;

import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphitron.rewrite.test.services.FilmKeySummary;
import org.jooq.DSLContext;

/** The generated runtime a call's fills read through: the request context and the session handle. */
public final class FillRuntime {

    private FillRuntime() {}

    public static Object contextArgument(DataFetchingEnvironment env, String key) {
        return env.getGraphQlContext().get(key);
    }

    public static FilmKeySummary sessionHandle(DSLContext dsl, String coordinate) {
        return new FilmKeySummary(null, "handle for " + coordinate);
    }

    public static String sessionMember_label(DSLContext dsl, String coordinate) {
        return "label for " + coordinate;
    }
}
