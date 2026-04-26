package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.generators.util.SchemaDirectiveRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Emits code that reconstructs survivor directive <em>definitions</em> from the loaded
 * schema. Used by {@link GraphitronSchemaClassGenerator} to call
 * {@code schemaBuilder.additionalDirective(...)} once per survivor so the programmatic
 * schema carries the definitions that its applications need for validation.
 *
 * <p>Translates via the public graphql-java builders ({@code GraphQLDirective.newDirective()},
 * {@code GraphQLArgument.newArgument()}, {@code GraphQLTypeReference.typeRef(...)},
 * {@code Introspection.DirectiveLocation.*}).
 */
public final class DirectiveDefinitionEmitter {

    private static final ClassName DIRECTIVE     = ClassName.get("graphql.schema", "GraphQLDirective");
    private static final ClassName ARGUMENT      = ClassName.get("graphql.schema", "GraphQLArgument");
    private static final ClassName DIR_LOCATION  = ClassName.get("graphql.introspection", "Introspection", "DirectiveLocation");

    private DirectiveDefinitionEmitter() {}

    /**
     * Returns the list of survivor directive definitions in the assembled schema, sorted by
     * name for stable output.
     */
    public static List<GraphQLDirective> survivors(GraphQLSchema assembled) {
        var result = new ArrayList<GraphQLDirective>();
        for (var dir : assembled.getDirectives()) {
            if (SchemaDirectiveRegistry.isSurvivor(dir.getName())) {
                result.add(dir);
            }
        }
        result.sort(Comparator.comparing(GraphQLDirective::getName));
        return result;
    }

    /**
     * Builds a CodeBlock that reconstructs {@code dir} as a {@link GraphQLDirective}
     * programmatic value (no trailing semicolon, no surrounding call).
     */
    public static CodeBlock buildDefinition(GraphQLDirective dir) {
        var block = CodeBlock.builder()
            .add("$T.newDirective()", DIRECTIVE)
            .add(".name($S)", dir.getName());
        if (dir.getDescription() != null && !dir.getDescription().isEmpty()) {
            block.add(".description($S)", dir.getDescription());
        }
        if (dir.isRepeatable()) {
            block.add(".repeatable(true)");
        }
        for (var loc : dir.validLocations()) {
            block.add(".validLocation($T.$L)", DIR_LOCATION, loc.name());
        }
        for (var arg : dir.getArguments()) {
            block.add(".argument(")
                .add("$T.newArgument()", ARGUMENT)
                .add(".name($S)", arg.getName())
                .add(".type(")
                .add(ObjectTypeGenerator.buildInputTypeRef(arg.getType()))
                .add(")");
            if (arg.getDescription() != null && !arg.getDescription().isEmpty()) {
                block.add(".description($S)", arg.getDescription());
            }
            block.add(".build())");
        }
        block.add(".build()");
        return block.build();
    }
}
