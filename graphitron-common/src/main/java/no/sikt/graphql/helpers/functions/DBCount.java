package no.sikt.graphql.helpers.functions;

import org.jooq.DSLContext;

import java.util.Set;

@FunctionalInterface
public interface DBCount<T> {
    Object callDBMethod(DSLContext ctx, Set<T> idSet);
}
