package no.fellesstudentsystem.graphql.helpers.selection;

import graphql.schema.DataFetchingFieldSelectionSet;
import org.jooq.Field;

import java.util.List;

import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noField;

public class SelectionSets {
    protected final List<DataFetchingFieldSelectionSet> selectionSets;
    private final String prefix;

    public SelectionSets(List<DataFetchingFieldSelectionSet> selectionSets) {
        this.selectionSets = selectionSets;
        prefix = "";
    }

    public SelectionSets(DataFetchingFieldSelectionSet selectionSet) {
        this.selectionSets = List.of(selectionSet);
        prefix = "";
    }

    private SelectionSets(List<DataFetchingFieldSelectionSet> selectionSets, String prefix) {
        this.selectionSets = selectionSets;
        this.prefix = prefix + "/";
    }

    public SelectionSets withPrefix(String prefix) {
        return new SelectionSets(selectionSets, prefix);
    }

    public <T> Field<T> optional(String path, Field<T> field, T _default) {
        return contains(path) ? field : noField(inline(_default));
    }

    public <T> Field<T> optional(String path, Field<T> field) {
        return contains(path) ? field : noField(inline((T) null));
    }

    public boolean contains(String path) {
        return selectionSets.stream().anyMatch(it -> it.contains(prefix + path));
    }
}