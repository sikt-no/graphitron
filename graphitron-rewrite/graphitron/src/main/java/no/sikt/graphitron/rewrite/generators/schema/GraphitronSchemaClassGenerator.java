package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.util.QueryNodeFetcherClassGenerator;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

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
                                          Set<String> typesWithFetchers, String outputPackage,
                                          boolean federationLink) {
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

        // Node interface TypeResolver — only when the schema actually has at least one
        // NodeType. Routes via the synthetic __typename column projected by every
        // QueryNodeFetcher.getNode dispatch arm.
        boolean hasNodeTypes = schema.types().values().stream().anyMatch(t -> t instanceof NodeType);
        if (hasNodeTypes) {
            var queryNodeFetcher = ClassName.get(outputPackage + ".fetchers",
                QueryNodeFetcherClassGenerator.CLASS_NAME);
            body.addStatement("$T.$L(codeRegistry)", queryNodeFetcher,
                QueryNodeFetcherClassGenerator.REGISTER_RESOLVER_METHOD);
        }

        // TableInterfaceType TypeResolvers — one per discriminated single-table interface.
        var JOOQ_DSL    = ClassName.get("org.jooq.impl", "DSL");
        var JOOQ_RECORD = ClassName.get("org.jooq", "Record");
        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof TableInterfaceType)
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                var tit = (TableInterfaceType) e.getValue();
                var tableBound = tit.participants().stream()
                    .filter(p -> p instanceof ParticipantRef.TableBound tb && tb.discriminatorValue() != null)
                    .map(p -> (ParticipantRef.TableBound) p)
                    .toList();
                if (tableBound.isEmpty()) return;
                var cb = CodeBlock.builder();
                cb.add("codeRegistry.typeResolver($S, env -> {\n", tit.name()).indent();
                cb.addStatement("$T record = ($T) env.getObject()", JOOQ_RECORD, JOOQ_RECORD);
                cb.addStatement("String discriminatorValue = record.get($T.field($T.name($S)), String.class)",
                    JOOQ_DSL, JOOQ_DSL, tit.discriminatorColumn());
                cb.add("return switch (discriminatorValue) {\n").indent();
                for (var p : tableBound) {
                    cb.add("case $S -> env.getSchema().getObjectType($S);\n",
                        p.discriminatorValue(), p.typeName());
                }
                cb.add("default -> null;\n");
                cb.unindent().add("};\n");
                cb.unindent().add("});\n");
                body.add(cb.build());
            });

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

        var classBuilder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        if (federationLink) {
            // One-arg form delegates to two-arg form.
            var oneArgMethod = MethodSpec.methodBuilder("build")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(GRAPHQL_SCHEMA)
                .addParameter(customizerType, "customizer")
                .addStatement("return build(customizer, fed -> {})")
                .build();

            // Two-arg form: base schema + federation post-step.
            var FEDERATION         = ClassName.get("com.apollographql.federation.graphqljava", "Federation");
            var SCHEMA_TRANSFORMER = ClassName.get("com.apollographql.federation.graphqljava", "SchemaTransformer");
            var OBJ_TYPE           = ClassName.get("graphql.schema", "GraphQLObjectType");
            var fedCustomizerType  = ParameterizedTypeName.get(ClassName.get(Consumer.class), SCHEMA_TRANSFORMER);

            var twoArgBody = CodeBlock.builder().add(body.build());
            twoArgBody.addStatement("schemaCustomizer.accept(schemaBuilder)");
            twoArgBody.addStatement("$T base = schemaBuilder.build()", GRAPHQL_SCHEMA);
            boolean hasEntities = !schema.entitiesByType().isEmpty();
            if (hasEntities) {
                var ENTITY_DISPATCH = ClassName.get(outputPackage + ".util",
                    no.sikt.graphitron.rewrite.generators.util.EntityFetcherDispatchClassGenerator.CLASS_NAME);
                twoArgBody.add(
                    "$T fb = $T.transform(base)\n", SCHEMA_TRANSFORMER, FEDERATION).indent()
                    .add(".setFederation2(true)\n")
                    .add(".resolveEntityType($T::$L)\n", ENTITY_DISPATCH,
                        no.sikt.graphitron.rewrite.generators.util.EntityFetcherDispatchClassGenerator.RESOLVE_TYPE_METHOD)
                    .add(".fetchEntities($T::$L);\n", ENTITY_DISPATCH,
                        no.sikt.graphitron.rewrite.generators.util.EntityFetcherDispatchClassGenerator.FETCH_ENTITIES_METHOD)
                    .unindent();
            } else {
                // No classified entities; keep the placeholder fetcher so federation still
                // wraps the schema cleanly. _entities([]) returns []; _entities([rep])
                // surfaces federation's "entity resolution failed" since the placeholder
                // returns an empty list.
                twoArgBody.add(
                    "$T fb = $T.transform(base)\n", SCHEMA_TRANSFORMER, FEDERATION).indent()
                    .add(".setFederation2(true)\n")
                    .add(".resolveEntityType(env -> {\n").indent()
                        .addStatement("Object rep = env.getObject()")
                        .addStatement("if (!(rep instanceof java.util.Map<?, ?> m)) return null")
                        .addStatement("Object tn = m.get(\"__typename\")")
                        .addStatement("return tn != null ? ($T) env.getSchema().getObjectType(tn.toString()) : null", OBJ_TYPE)
                        .unindent().add("})\n")
                    .add(".fetchEntities(env -> java.util.List.of());\n")
                    .unindent();
            }
            twoArgBody.addStatement("federationCustomizer.accept(fb)");
            twoArgBody.addStatement("return fb.build()");

            var twoArgMethod = MethodSpec.methodBuilder("build")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(GRAPHQL_SCHEMA)
                .addParameter(customizerType, "schemaCustomizer")
                .addParameter(fedCustomizerType, "federationCustomizer")
                .addJavadoc("Builds the schema and wraps it with {@code Federation.transform}.\n"
                    + "The {@code federationCustomizer} receives the pre-configured\n"
                    + "{@link $T} builder after Graphitron's defaults are attached;\n"
                    + "call {@code .fetchEntities(...)} to override the default no-op fetcher.\n"
                    + "Do not call {@code .build()} from the customizer.\n", SCHEMA_TRANSFORMER)
                .addCode(twoArgBody.build())
                .build();

            classBuilder.addMethod(oneArgMethod).addMethod(twoArgMethod);
        } else {
            body.addStatement("customizer.accept(schemaBuilder)");
            body.addStatement("return schemaBuilder.build()");

            var buildMethod = MethodSpec.methodBuilder("build")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(GRAPHQL_SCHEMA)
                .addParameter(customizerType, "customizer")
                .addCode(body.build())
                .build();
            classBuilder.addMethod(buildMethod);
        }

        return List.of(classBuilder.build());
    }

    /** Convenience overload for tests — empty fetcher set, no output-package prefix. */
    public static List<TypeSpec> generate(GraphitronSchema schema, GraphQLSchema assembled) {
        return generate(schema, assembled, Set.of(), "", false);
    }

    /** Convenience overload for tests that pass a fetcher set but not an output-package. */
    public static List<TypeSpec> generate(GraphitronSchema schema, GraphQLSchema assembled,
                                          Set<String> typesWithFetchers) {
        return generate(schema, assembled, typesWithFetchers, "", false);
    }

    /** Convenience overload for tests that pass a fetcher set and an output-package but no federation. */
    public static List<TypeSpec> generate(GraphitronSchema schema, GraphQLSchema assembled,
                                          Set<String> typesWithFetchers, String outputPackage) {
        return generate(schema, assembled, typesWithFetchers, outputPackage, false);
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
     * <p>Source of truth is {@link GraphitronSchema#types()}, which by Phase 6 contains every
     * emittable type — objects, interfaces, unions, inputs, enums, SDL-declared and synthesised
     * alike. The assembled schema is no longer consulted here.
     */
    static Plan planFor(GraphitronSchema schema, GraphQLSchema assembled) {
        var additional = new java.util.LinkedHashSet<String>();
        boolean hasQuery = false, hasMutation = false, hasSubscription = false;

        for (var entry : schema.types().entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("_")) continue;
            var variant = entry.getValue();
            if (variant instanceof RootType) {
                if ("Query".equals(name))             hasQuery = true;
                else if ("Mutation".equals(name))     hasMutation = true;
                else if ("Subscription".equals(name)) hasSubscription = true;
                continue;
            }
            if (variant instanceof UnclassifiedType) continue;
            additional.add(name);
        }

        var sorted = new ArrayList<>(additional);
        sorted.sort(Comparator.naturalOrder());
        return new Plan(hasQuery, hasMutation, hasSubscription, List.copyOf(sorted));
    }
}
