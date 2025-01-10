package no.sikt.graphql.helpers.functions;

import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

@FunctionalInterface
public interface DBQueryRoot<T> {
    T callDBMethod(DSLContext ctx, SelectionSet selectionSet);
}
