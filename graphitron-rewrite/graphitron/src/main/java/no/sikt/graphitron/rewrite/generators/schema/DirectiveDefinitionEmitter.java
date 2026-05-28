package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.generators.util.SchemaDirectiveRegistry;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Emits a {@code private static GraphQLDirective directiveDefinition_<sdlName>()} factory
 * method per survivor directive definition in the assembled schema. Used by
 * {@link GraphitronSchemaClassGenerator} so each {@code schemaBuilder.additionalDirective(...)}
 * statement reduces to a single helper call instead of a fluent sub-chain whose length scales
 * with locations + arguments per directive.
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
     * Builds a {@code private static GraphQLDirective <methodName>()} factory method whose
     * body is statement-flattened: one local builder variable plus a statement per builder
     * call. Chain depth on every statement is bounded by construction.
     */
    static MethodSpec buildDefinitionMethod(String methodName, GraphQLDirective dir) {
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newDirective()", DIRECTIVE, DIRECTIVE);
        body.addStatement("b.name($S)", dir.getName());
        if (dir.getDescription() != null && !dir.getDescription().isEmpty()) {
            body.addStatement("b.description($S)", dir.getDescription());
        }
        if (dir.isRepeatable()) {
            body.addStatement("b.repeatable(true)");
        }
        for (var loc : dir.validLocations()) {
            body.addStatement("b.validLocation($T.$L)", DIR_LOCATION, loc.name());
        }
        int argIdx = 0;
        for (var arg : dir.getArguments()) {
            String argVar = "a" + argIdx++;
            body.addStatement("$T.Builder $L = $T.newArgument()", ARGUMENT, argVar, ARGUMENT);
            body.addStatement("$L.name($S)", argVar, arg.getName());
            body.addStatement("$L.type($L)", argVar, AppliedDirectiveEmitter.emitInputType(arg.getType()));
            if (arg.getDescription() != null && !arg.getDescription().isEmpty()) {
                body.addStatement("$L.description($S)", argVar, arg.getDescription());
            }
            if (arg.hasSetDefaultValue()) {
                Object defaultValue = graphql.schema.GraphQLArgument.getArgumentDefaultValue(arg);
                body.addStatement("$L.defaultValueProgrammatic($L)", argVar, GraphQLValueEmitter.emit(defaultValue));
            }
            body.addStatement("b.argument($L.build())", argVar);
        }
        body.addStatement("return b.build()");
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(DIRECTIVE)
            .addCode(body.build())
            .build();
    }
}
