package no.fellesstudentsystem.schema_transformer.schema;

import graphql.language.*;
import no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirective;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Helper methods for extracting directive information from the schema.
 */
public class SchemaHelpers {
    public static List<String> getOptionalDirectiveArgumentStringList(DirectivesContainer<?> container, GraphQLDirective directive, String arg) {
        return getOptionalDirectiveArgumentStringList(container, directive.getName(), arg);
    }

    private static List<String> getOptionalDirectiveArgumentStringList(DirectivesContainer<?> container, String directiveName, String arg) {
        var dir = container.getDirectives(directiveName);
        if (dir == null || dir.isEmpty()) {
            return List.of();
        }

        var args = dir.get(0).getArgument(arg);
        if (args == null) {
            return List.of();
        }

        var argsValue = args.getValue();

        if (argsValue instanceof StringValue) {
            return List.of(((StringValue) argsValue).getValue());
        }
        return ((ArrayValue) argsValue).getValues().stream()
                .map(stringValue -> stringValue instanceof NullValue ? null : ((StringValue) stringValue).getValue())
                .collect(Collectors.toList());
    }
}
