package no.fellesstudentsystem.graphql.helpers.resolvers;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;

import java.util.Set;

@FunctionalInterface
public interface DBCount {
    Integer callDBMethod(Set<String> idSet, SelectionSet selectionSet);
}
