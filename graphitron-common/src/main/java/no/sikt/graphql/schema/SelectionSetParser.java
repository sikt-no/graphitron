package no.sikt.graphql.schema;

import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;

import java.util.List;

/**
 * Parses a string containing GraphQL field selections using graphql-java's {@link Parser}.
 * This supports the standard GraphQL selection set syntax including aliases and nesting.
 */
public class SelectionSetParser {
    private SelectionSetParser() {}

    /**
     * Parses a selection string into a list of {@link Field} AST nodes.
     * The input should contain fields as they would appear inside a GraphQL selection set,
     * e.g. {@code "firstName: FIRST_NAME, lastName: LAST_NAME"} or {@code "id name address { city }"}.
     *
     * @param selectionString the field selection content, with or without surrounding braces
     * @return list of parsed {@link Field} nodes
     */
    public static List<Field> parseFields(String selectionString) {
        var stripped = selectionString.strip();
        var wrapped = stripped.startsWith("{") && stripped.endsWith("}") ? stripped : "{ " + selectionString + " }";
        var document = Parser.parse(wrapped);
        return ((OperationDefinition) document.getDefinitions().get(0))
                .getSelectionSet()
                .getSelections()
                .stream()
                .map(Field.class::cast)
                .toList();
    }
}
