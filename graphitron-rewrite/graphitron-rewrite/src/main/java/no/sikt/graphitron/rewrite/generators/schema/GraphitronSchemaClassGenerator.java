package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Generates {@code GraphitronSchema.java} in {@code <outputPackage>.rewrite.schema}. This is the
 * internal schema assembler invoked by the emitted {@code Graphitron} facade. Its single
 * {@code build(Consumer<GraphQLSchema.Builder> customizer)} method:
 *
 * <ol>
 *   <li>Creates a {@link graphql.schema.GraphQLCodeRegistry.Builder}. Fetcher registration
 *       calls (one per emitted object type that owns data fetchers) are a follow-up sub-commit
 *       within Commit B; today the registry is handed to the schema builder empty.</li>
 *   <li>Creates a {@link graphql.schema.GraphQLSchema.Builder}. Routes root operation types
 *       ({@code Query}, {@code Mutation}, {@code Subscription}) through the corresponding
 *       {@code .query(...)} / {@code .mutation(...)} / {@code .subscription(...)} entry points
 *       when present; passes every other emitted type through {@code .additionalType(...)}.</li>
 *   <li>Attaches the code registry via {@code .codeRegistry(GraphQLCodeRegistry)}. User
 *       customizers that need to append to the registry must use the
 *       {@code .codeRegistry(UnaryOperator)} overload on the schema builder; see
 *       {@link no.sikt.graphitron.rewrite.generators.schema.GraphitronFacadeGenerator
 *       GraphitronFacadeGenerator} javadoc.</li>
 *   <li>Invokes the user's {@code customizer} on the schema builder to register scalars, extra
 *       types, or additional directives.</li>
 *   <li>Calls {@code .build()} and returns the result.</li>
 * </ol>
 *
 * <p>The generator collects type names from an assembled {@link GraphQLSchema} produced by
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilder}. Introspection and federation-
 * injected types are skipped to match the per-type emitters. Survivor directive definitions
 * (federation directives plus user-declared custom directives) are emitted via
 * {@code .additionalDirective(...)} in a follow-up sub-commit when the SDL surface for
 * them lands; today the emitted facade does not call {@code additionalDirective}.
 */
public final class GraphitronSchemaClassGenerator {

    public static final String CLASS_NAME = "GraphitronSchema";

    private static final ClassName GRAPHQL_SCHEMA = ClassName.get("graphql.schema", "GraphQLSchema");
    private static final ClassName SCHEMA_BUILDER = ClassName.get("graphql.schema", "GraphQLSchema", "Builder");
    private static final ClassName CODE_REGISTRY  = ClassName.get("graphql.schema", "GraphQLCodeRegistry");
    private static final ClassName CODE_REGISTRY_BLDR = ClassName.get("graphql.schema", "GraphQLCodeRegistry", "Builder");
    private static final ClassName SCALARS        = ClassName.get("graphql", "Scalars");

    private GraphitronSchemaClassGenerator() {}

    public static List<TypeSpec> generate(GraphitronSchema schema, GraphQLSchema assembled,
                                          Set<String> typesWithFetchers, String outputPackage) {
        var plan = planFor(schema, assembled);
        String schemaPackage = outputPackage + ".schema";

        var builderType = ClassName.get("graphql.schema", "GraphQLSchema", "Builder");
        var customizerType = ParameterizedTypeName.get(
            ClassName.get(Consumer.class),
            builderType);

        var body = CodeBlock.builder()
            .addStatement("$T codeRegistry = $T.newCodeRegistry()", CODE_REGISTRY_BLDR, CODE_REGISTRY);

        var sortedFetcherTypes = new ArrayList<>(typesWithFetchers);
        sortedFetcherTypes.sort(Comparator.naturalOrder());
        for (String name : sortedFetcherTypes) {
            body.addStatement("$T.registerFetchers(codeRegistry)", ClassName.get(schemaPackage, name + "Type"));
        }

        body.add("$T schemaBuilder = $T.newSchema()", SCHEMA_BUILDER, GRAPHQL_SCHEMA).indent();

        if (plan.hasQuery)        body.add("\n.query($T.type())",        ClassName.get(schemaPackage, "QueryType"));
        if (plan.hasMutation)     body.add("\n.mutation($T.type())",     ClassName.get(schemaPackage, "MutationType"));
        if (plan.hasSubscription) body.add("\n.subscription($T.type())", ClassName.get(schemaPackage, "SubscriptionType"));
        for (String name : plan.additionalTypeNames) {
            body.add("\n.additionalType($T.type())", ClassName.get(schemaPackage, name + "Type"));
        }
        // Built-in GraphQL scalars aren't auto-registered on a programmatic schema; the SDL
        // path in SchemaGenerator used to add them for us. Register the five graphql-spec
        // scalars so every typeRef("Int") / typeRef("String") / … resolves at build time.
        body.add("\n.additionalType($T.GraphQLInt)",     SCALARS);
        body.add("\n.additionalType($T.GraphQLFloat)",   SCALARS);
        body.add("\n.additionalType($T.GraphQLString)",  SCALARS);
        body.add("\n.additionalType($T.GraphQLBoolean)", SCALARS);
        body.add("\n.additionalType($T.GraphQLID)",      SCALARS);
        for (var dir : DirectiveDefinitionEmitter.survivors(assembled)) {
            body.add("\n.additionalDirective(").add(DirectiveDefinitionEmitter.buildDefinition(dir)).add(")");
        }
        body.add("\n.codeRegistry(codeRegistry.build());\n").unindent();
        body.addStatement("customizer.accept(schemaBuilder)");
        body.addStatement("return schemaBuilder.build()");

        var buildMethod = MethodSpec.methodBuilder("build")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(GRAPHQL_SCHEMA)
            .addParameter(customizerType, "customizer")
            .addCode(body.build())
            .build();

        return List.of(TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(buildMethod)
            .build());
    }

    /** Convenience overload for tests — empty fetcher set, no output-package prefix. */
    public static List<TypeSpec> generate(GraphitronSchema schema, GraphQLSchema assembled) {
        return generate(schema, assembled, Set.of(), "");
    }

    /** Convenience overload for tests that pass a fetcher set but not an output-package. */
    public static List<TypeSpec> generate(GraphitronSchema schema, GraphQLSchema assembled,
                                          Set<String> typesWithFetchers) {
        return generate(schema, assembled, typesWithFetchers, "");
    }

    record Plan(
        boolean hasQuery,
        boolean hasMutation,
        boolean hasSubscription,
        List<String> additionalTypeNames
    ) {}

    /**
     * Enumerates the types that need registration in the emitted {@code GraphitronSchema.build()}.
     *
     * <p>Source of truth is {@link GraphitronSchema#types()}, which by Phase 1 of the first-class
     * Connection plan contains every emittable type — SDL-declared ones and directive-synthesised
     * {@link ConnectionType} / {@link EdgeType} / {@link PageInfoType} alike. The assembled schema
     * is consulted only for enum and input-object entries that the classifier doesn't record in
     * its model.
     */
    static Plan planFor(GraphitronSchema schema, GraphQLSchema assembled) {
        var additional = new java.util.LinkedHashSet<String>();
        boolean hasQuery = false, hasMutation = false, hasSubscription = false;
        var seen = new java.util.LinkedHashSet<String>();

        for (var entry : schema.types().entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("_")) continue;
            seen.add(name);
            var variant = entry.getValue();
            if (variant instanceof RootType) {
                if ("Query".equals(name))        hasQuery = true;
                else if ("Mutation".equals(name))     hasMutation = true;
                else if ("Subscription".equals(name)) hasSubscription = true;
                continue;
            }
            if (variant instanceof UnclassifiedType) continue;
            if ((variant instanceof InputType || variant instanceof TableInputType)
                    && InputDirectiveInputTypes.NAMES.contains(name)) {
                continue;
            }
            additional.add(name);
        }

        // Fall back to the assembled schema for types the classifier didn't record:
        // plain SDL types without directives, enums, and any SDL-declared scalars.
        for (var t : assembled.getAllTypesAsList()) {
            String name = t.getName();
            if (name.startsWith("_")) continue;
            if (seen.contains(name)) continue;
            if (t instanceof graphql.schema.GraphQLScalarType) continue;
            if (t instanceof graphql.schema.GraphQLInputObjectType
                    && InputDirectiveInputTypes.NAMES.contains(name)) continue;
            if (t instanceof graphql.schema.GraphQLObjectType obj) {
                if ("Query".equals(name))        { hasQuery = true; continue; }
                if ("Mutation".equals(name))     { hasMutation = true; continue; }
                if ("Subscription".equals(name)) { hasSubscription = true; continue; }
            }
            additional.add(name);
        }

        var sorted = new ArrayList<>(additional);
        sorted.sort(Comparator.naturalOrder());
        return new Plan(hasQuery, hasMutation, hasSubscription, List.copyOf(sorted));
    }
}
