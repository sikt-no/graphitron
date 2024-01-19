package no.fellesstudentsystem.graphql.helpers.functions;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;

import java.util.List;

@FunctionalInterface
public interface DBQueryRoot<T> {
    List<T> callDBMethod(SelectionSet selectionSet);
}
