package no.fellesstudentsystem.graphql.helpers.functions;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

import java.util.Set;

@FunctionalInterface
public interface DBCount<T> {
    Integer callDBMethod(DSLContext ctx, Set<T> idSet, SelectionSet selectionSet);
}
