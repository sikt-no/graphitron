package no.fellesstudentsystem.graphql.helpers.functions;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;

import java.util.Set;

@FunctionalInterface
public interface DBCount<T> {
    Integer callDBMethod(Set<T> idSet, SelectionSet selectionSet);
}
