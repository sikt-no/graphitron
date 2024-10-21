package no.fellesstudentsystem.graphql.helpers.selection;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import org.jooq.Field;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noField;

/**
 * A helper class for generated code. Helps with checking the selection set from the {@link graphql.schema.DataFetchingEnvironment}
 * for fields used in queries and mutations.
 */
public class SelectionSet {
    protected final Set<String> selectionSet;
    private final String prefix;

    public SelectionSet(List<DataFetchingFieldSelectionSet> selectionSets) {
        this.selectionSet = selectionSets.stream()
                .flatMap(selectionSet -> selectionSet.getFields().stream())
                .map(SelectedField::getQualifiedName)
                .collect(Collectors.toSet());
        prefix = "";
    }

    public SelectionSet(DataFetchingFieldSelectionSet selectionSet) {
        this(List.of(selectionSet));
    }

    private SelectionSet(Set<String> selectionSet, String prefix) {
        this.selectionSet = selectionSet;
        this.prefix = prefix + "/";
    }

    public SelectionSet withPrefix(String prefix) {
        return new SelectionSet(selectionSet, prefix);
    }

    public <T> Field<T> optional(String path, Field<T> field, T _default) {
        return contains(path) ? field : noField(inline(_default));
    }

    public <T> Field<T> optional(String path, Field<T> field) {
        return contains(path) ? field : noField(inline((T) null));
    }

    public boolean contains(String path) {
        return selectionSet.stream().anyMatch(it -> it.contains(prefix + path));
    }
}