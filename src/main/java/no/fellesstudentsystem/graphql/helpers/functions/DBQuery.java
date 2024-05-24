package no.fellesstudentsystem.graphql.helpers.functions;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

import java.util.Map;
import java.util.Set;

@FunctionalInterface
public interface DBQuery<K, V> {
    Map<K, V> callDBMethod(DSLContext ctx, Set<K> keys, SelectionSet selectionSet);
}
