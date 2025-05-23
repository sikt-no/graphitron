package no.fellesstudentsystem.schema_transformer.schema;

import graphql.language.ArrayValue;
import graphql.language.DirectivesContainer;
import graphql.language.NullValue;
import graphql.language.StringValue;
import graphql.schema.GraphQLNamedSchemaElement;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.idl.DirectiveInfo;
import graphql.util.TraverserContext;
import no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirective;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_LINK;

/**
 * Helper methods for extracting directive information from the schema.
 */
public class SchemaHelpers {
    public static final Set<String> BUILT_IN_DIRECTIVES = Stream.concat(
            DirectiveInfo.GRAPHQL_SPECIFICATION_DIRECTIVE_MAP.keySet().stream(),
            Stream.of(FEDERATION_LINK.getName())
    ).collect(Collectors.toSet());

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

    public static boolean isInternal(TraverserContext<GraphQLSchemaElement> context) {
        return Stream
                .concat(Stream.of(context.thisNode()), context.getParentNodes().stream())
                .filter(it -> it instanceof GraphQLNamedSchemaElement)
                .map(it -> (GraphQLNamedSchemaElement) it)
                .anyMatch(it ->
                        it.getName().startsWith("__")
                                || it.getName().startsWith("link__")
                                || it.getName().startsWith("federation__")
                                || BUILT_IN_DIRECTIVES.contains(it.getName())
                );
    }
}
