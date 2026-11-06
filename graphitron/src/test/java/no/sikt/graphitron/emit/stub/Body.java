package no.sikt.graphitron.emit.stub;

import graphql.schema.DataFetchingEnvironment;
import org.jooq.DSLContext;
import org.jooq.Record1;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** A producer's body, as the frame calls it: at a root on the fetch, under an object on a batch. */
public final class Body {

    private Body() {}

    /** How many batches {@link #upper} has run, which a test reads to see the loader batched. */
    public static int batches;

    /** The jOOQ context the frame reaches; none here, the bodies reading no database. */
    public static DSLContext dsl(DataFetchingEnvironment env) {
        return null;
    }

    public static Integer count(DataFetchingEnvironment env, DSLContext dsl) {
        return 42;
    }

    public static Integer fail(DataFetchingEnvironment env, DSLContext dsl) {
        throw new IllegalStateException("body failed");
    }

    public static String lookup(DataFetchingEnvironment env, DSLContext dsl) {
        return "found";
    }

    public static String refuse(DataFetchingEnvironment env, DSLContext dsl) {
        throw new IllegalStateException("refused");
    }

    public static Map<Record1<Integer>, String> upper(Set<Record1<Integer>> keys, DataFetchingEnvironment env,
                                                      DSLContext dsl) {
        batches++;
        return keys.stream().collect(Collectors.toMap(k -> k, k -> "film " + k.value1()));
    }
}
