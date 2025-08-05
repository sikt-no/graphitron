package no.sikt.graphql.helpers.functions;

import org.jooq.DSLContext;

import java.util.Map;
import java.util.Set;

@FunctionalInterface
public interface DBCountMany<T> {
    Map<T, Integer> callDBMethod(DSLContext ctx, Set<T> idSet);
}
