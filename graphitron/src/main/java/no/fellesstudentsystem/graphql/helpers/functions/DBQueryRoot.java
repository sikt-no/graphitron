package no.fellesstudentsystem.graphql.helpers.functions;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

@FunctionalInterface
public interface DBQueryRoot<T> {
    T callDBMethod(DSLContext ctx, SelectionSet selectionSet);
}
