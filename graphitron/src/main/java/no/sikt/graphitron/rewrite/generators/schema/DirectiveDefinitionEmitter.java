package no.sikt.graphitron.rewrite.generators.schema;

import graphql.Directives;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLSchema;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Emits a {@code private static GraphQLDirective directiveDefinition_<sdlName>()} factory
 * method per directive definition in the emitted schema. Used by
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
     * The directive definitions in the emitted schema, sorted by name for stable output. Graphitron's
     * own directives are not among them: the emitted schema never publishes them.
     */
    public static List<GraphQLDirective> definitions(GraphQLSchema emitted) {
        var result = new ArrayList<>(emitted.getDirectives());
        result.sort(Comparator.comparing(GraphQLDirective::getName));
        return result;
    }

    /**
     * Whether graphql-java's {@code GraphQLSchema.Builder} already holds {@code dir}: the builder
     * seeds {@code @include} and {@code @skip} into every schema and, unlike the other spec
     * directives it supplies, does not drop them when a same-named definition is registered, so
     * registering either name defines it twice and the schema prints SDL a parser
     * rejects.
     */
    public static boolean isSeededByGraphqlJava(GraphQLDirective dir) {
        return dir.getName().equals(Directives.IncludeDirective.getName())
            || dir.getName().equals(Directives.SkipDirective.getName());
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
            body.addStatement("$L.type($L)", argVar, AppliedDirectiveEmitter.emitDefinitionInputType(arg.getType()));
            if (arg.getDescription() != null && !arg.getDescription().isEmpty()) {
                body.addStatement("$L.description($S)", argVar, arg.getDescription());
            }
            if (arg.hasSetDefaultValue()) {
                body.addStatement("$L.$L", argVar, GraphQLValueEmitter.emitDefault(arg.getArgumentDefaultValue(),
                    "@" + dir.getName() + "(" + arg.getName() + ":)"));
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
