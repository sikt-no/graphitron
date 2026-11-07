package no.sikt.graphitron.emit.stub;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.SelectedField;

import java.util.List;
import java.util.Map;

/**
 * A selected field as graphql-java hands it over, reduced to what the generated selection adapters
 * read: its name, its result key and the object types it was selected on.
 */
public record Selected(String name, String resultKey, List<String> objectTypeNames) implements SelectedField {

    @Override public String getName() { return name; }
    @Override public String getResultKey() { return resultKey; }
    @Override public List<String> getObjectTypeNames() { return objectTypeNames; }
    @Override public String getQualifiedName() { return name; }
    @Override public String getFullyQualifiedName() { return name; }
    @Override public List<GraphQLObjectType> getObjectTypes() { return List.of(); }
    @Override public List<GraphQLFieldDefinition> getFieldDefinitions() { return List.of(); }
    @Override public GraphQLOutputType getType() { return null; }
    @Override public Map<String, Object> getArguments() { return Map.of(); }
    @Override public int getLevel() { return 1; }
    @Override public boolean isConditional() { return objectTypeNames.size() == 1; }
    @Override public String getAlias() { return resultKey.equals(name) ? null : resultKey; }
    @Override public SelectedField getParentField() { return null; }
    @Override public DataFetchingFieldSelectionSet getSelectionSet() { return Selection.of(); }
}
