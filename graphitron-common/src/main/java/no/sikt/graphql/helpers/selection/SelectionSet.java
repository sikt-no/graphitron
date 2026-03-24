package no.sikt.graphql.helpers.selection;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import no.sikt.graphql.helpers.resolvers.ArgumentPresence;
import org.jooq.Field;
import org.jooq.SelectField;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
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
    private ArgumentPresence argumentPresence;

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

    public <T> SelectField<T> ifRequested(String path, Supplier<SelectField<T>> fieldSupplier) {
        return contains(path) ? fieldSupplier.get() : noField(inline((T) null));
    }

    public boolean contains(String path) {
        return selectionSet.stream().anyMatch(it -> it.contains(prefix + path));
    }

    public void setArgumentPresence(ArgumentPresence argumentPresence) {
        this.argumentPresence = argumentPresence;
    }

    public ArgumentPresence getArgumentPresence() {
        return argumentPresence;
    }

    /** Check if a field is present at a specific list index in the argument presence tree. */
    public boolean hasArgumentAtIndex(String listName, int index, String fieldName) {
        return argumentPresence != null
                && argumentPresence.child(listName).itemAt(index).hasField(fieldName);
    }
}
