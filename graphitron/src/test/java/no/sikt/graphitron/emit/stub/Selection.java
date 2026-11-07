package no.sikt.graphitron.emit.stub;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A selection set of immediate fields only, in the order given. */
public record Selection(List<SelectedField> fields) implements DataFetchingFieldSelectionSet {

    public static Selection of(SelectedField... fields) {
        return new Selection(List.of(fields));
    }

    @Override public List<SelectedField> getImmediateFields() { return fields; }
    @Override public List<SelectedField> getFields() { return fields; }
    @Override public boolean contains(String fieldGlobPattern) { return false; }
    @Override public boolean containsAnyOf(String fieldGlobPattern, String... fieldGlobPatterns) { return false; }
    @Override public boolean containsAllOf(String fieldGlobPattern, String... fieldGlobPatterns) { return false; }
    @Override public List<SelectedField> getFields(String fieldGlobPattern, String... fieldGlobPatterns) { return fields; }

    @Override
    public Map<String, List<SelectedField>> getFieldsGroupedByResultKey() {
        Map<String, List<SelectedField>> grouped = new LinkedHashMap<>();
        fields.forEach(f -> grouped.computeIfAbsent(f.getResultKey(), k -> new java.util.ArrayList<>()).add(f));
        return grouped;
    }

    @Override
    public Map<String, List<SelectedField>> getFieldsGroupedByResultKey(String fieldGlobPattern, String... fieldGlobPatterns) {
        return getFieldsGroupedByResultKey();
    }
}
