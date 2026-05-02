package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.util.QueryNodeFetcherClassGenerator;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.SqlStateHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.VendorCodeHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
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

        // Plain InterfaceType / UnionType TypeResolvers — read the synthetic __typename column
        // projected by R36 Track B's stage-1 narrow-union emitter. The Node interface is
        // registered separately via QueryNodeFetcher.registerTypeResolver above; skip it here
        // so we don't double-register. Federation's _Entity union is injected by
        // Federation.transform() after schemaBuilder.build(), so it does not appear in
        // schema.types() and needs no explicit skip. @error-only unions/interfaces are
        // registered separately below: their runtime sources are throwables and
        // GraphQLErrors, never jOOQ records, so they can't read a __typename column.
        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof InterfaceType || e.getValue() instanceof UnionType)
            .filter(e -> !"Node".equals(e.getKey()))
            .filter(e -> !isErrorOnlyPolymorphic(e.getValue(), schema))
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                var cb = CodeBlock.builder();
                cb.add("codeRegistry.typeResolver($S, env -> {\n", e.getKey()).indent();
                cb.addStatement("$T record = ($T) env.getObject()", JOOQ_RECORD, JOOQ_RECORD);
                cb.addStatement("if (record == null) return null");
                cb.addStatement("String typeName = record.get($T.field($T.name($S)), String.class)",
                    JOOQ_DSL, JOOQ_DSL, "__typename");
                cb.addStatement("return typeName == null ? null : env.getSchema().getObjectType(typeName)");
                cb.unindent().add("});\n");
                body.add(cb.build());
            });

        // @error-only union/interface TypeResolvers: source-class-instanceof dispatch
        // (R12 §2c). The runtime source for each entry in a payload's errors list is the
        // matched object itself (Throwable for GENERIC/DATABASE handlers, GraphQLError for
        // VALIDATION); no developer-supplied data class. Each such union or interface gets a
        // resolver that walks every (participant @error type, handler) pair in source order
        // (declaration order, then the handler array's order within each @error type) and
        // dispatches each runtime source to the @error type whose handler discriminator
        // matches. Mirrors the dispatcher's source-order-first-match semantics.
        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof InterfaceType || e.getValue() instanceof UnionType)
            .filter(e -> isErrorOnlyPolymorphic(e.getValue(), schema))
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> body.add(buildErrorPolymorphicResolver(e.getKey(), e.getValue(), schema)));

        // Per-@error-type path/message DataFetchers (R12 §2c). The SDL grammar restricts
        // @error types to {path: [String!]!, message: String!}; the @error type's source
        // class can be a Throwable (no getPath()) or a GraphQLError (has getPath()/getMessage()).
        // Synthesize the path field from the GraphQL execution context for non-GraphQLError
        // sources so the schema's non-null contract holds regardless of handler kind. Message
        // routes universally through getMessage(), defined on both Throwable and GraphQLError.
        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof ErrorType)
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> body.add(buildErrorTypeFieldFetchers(e.getKey())));

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

    /**
     * True for a {@link UnionType} or {@link InterfaceType} whose participants are all classified
     * as {@link ErrorType}. Used to fork the TypeResolver emission: jOOQ-record-backed
     * polymorphic types read a {@code __typename} column; @error-only polymorphic types
     * dispatch by runtime source class (Throwable / GraphQLError) per R12 §2c.
     *
     * <p>A polymorphic type with mixed @error and non-@error participants does not occur in
     * practice: the carrier classifier only lifts a payload's errors-shaped field to
     * {@code ErrorsField} when every member is @error (see {@code FieldBuilder.liftToErrorsField}),
     * and the SDL grammar disallows polymorphic types whose members can't be jOOQ-record-backed
     * either. This helper returns false for the empty-participant case (degenerate types).
     */
    private static boolean isErrorOnlyPolymorphic(GraphitronType type, GraphitronSchema schema) {
        List<ParticipantRef> participants = switch (type) {
            case UnionType u -> u.participants();
            case InterfaceType i -> i.participants();
            default -> List.of();
        };
        if (participants.isEmpty()) return false;
        for (var p : participants) {
            if (!(schema.type(p.typeName()) instanceof ErrorType)) return false;
        }
        return true;
    }

    /**
     * Emits the {@code codeRegistry.typeResolver(...)} call for an @error-only union or
     * interface. Walks every (@error-typed participant, handler) pair in source order; the
     * resulting {@code if} ladder mirrors the dispatcher's source-order-first-match semantics
     * (R12 §3 dispatch order). The first {@code instanceof} predicate that satisfies returns
     * the corresponding @error SDL type; falls through to {@code null} when no predicate
     * matches (unreachable in practice; the dispatcher's match step has already filtered).
     */
    private static CodeBlock buildErrorPolymorphicResolver(String typeName, GraphitronType type,
                                                           GraphitronSchema schema) {
        var participants = switch (type) {
            case UnionType u -> u.participants();
            case InterfaceType i -> i.participants();
            default -> List.<ParticipantRef>of();
        };

        var GRAPHQL_ERROR = ClassName.get("graphql", "GraphQLError");
        var SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
        var STRING_CN     = ClassName.get(String.class);

        var cb = CodeBlock.builder();
        cb.add("codeRegistry.typeResolver($S, env -> {\n", typeName).indent();
        cb.addStatement("Object src = env.getObject()");
        for (var p : participants) {
            var et = (ErrorType) schema.type(p.typeName());
            for (var handler : et.handlers()) {
                String et_name = et.name();
                if (handler instanceof ValidationHandler) {
                    cb.beginControlFlow("if (src instanceof $T)", GRAPHQL_ERROR);
                    cb.addStatement("return env.getSchema().getObjectType($S)", et_name);
                    cb.endControlFlow();
                } else if (handler instanceof ExceptionHandler eh) {
                    var excClass = ClassName.bestGuess(eh.exceptionClassName());
                    if (eh.matches().isPresent()) {
                        cb.beginControlFlow("if (src instanceof $T thr)", excClass);
                        cb.addStatement("$T msg = thr.getMessage()", STRING_CN);
                        cb.beginControlFlow("if (msg != null && msg.contains($S))", eh.matches().get());
                        cb.addStatement("return env.getSchema().getObjectType($S)", et_name);
                        cb.endControlFlow();
                        cb.endControlFlow();
                    } else {
                        cb.beginControlFlow("if (src instanceof $T)", excClass);
                        cb.addStatement("return env.getSchema().getObjectType($S)", et_name);
                        cb.endControlFlow();
                    }
                } else if (handler instanceof SqlStateHandler sh) {
                    cb.beginControlFlow("if (src instanceof $T sqlEx && $S.equals(sqlEx.getSQLState()))",
                        SQL_EXCEPTION, sh.sqlState());
                    if (sh.matches().isPresent()) {
                        cb.addStatement("$T msg = sqlEx.getMessage()", STRING_CN);
                        cb.beginControlFlow("if (msg != null && msg.contains($S))", sh.matches().get());
                        cb.addStatement("return env.getSchema().getObjectType($S)", et_name);
                        cb.endControlFlow();
                    } else {
                        cb.addStatement("return env.getSchema().getObjectType($S)", et_name);
                    }
                    cb.endControlFlow();
                } else if (handler instanceof VendorCodeHandler vh) {
                    cb.beginControlFlow("if (src instanceof $T sqlEx && $S.equals($T.valueOf(sqlEx.getErrorCode())))",
                        SQL_EXCEPTION, vh.vendorCode(), STRING_CN);
                    if (vh.matches().isPresent()) {
                        cb.addStatement("$T msg = sqlEx.getMessage()", STRING_CN);
                        cb.beginControlFlow("if (msg != null && msg.contains($S))", vh.matches().get());
                        cb.addStatement("return env.getSchema().getObjectType($S)", et_name);
                        cb.endControlFlow();
                    } else {
                        cb.addStatement("return env.getSchema().getObjectType($S)", et_name);
                    }
                    cb.endControlFlow();
                }
            }
        }
        cb.addStatement("return null");
        cb.unindent().add("});\n");
        return cb.build();
    }

    /**
     * Emits {@code codeRegistry.dataFetcher(...)} calls for the {@code path} and {@code message}
     * fields of one @error type (R12 §2c). The SDL grammar restricts @error types to exactly
     * these two fields; both are always {@code [String!]!} / {@code String!} so a missing or
     * null value would violate the schema's non-null contract.
     *
     * <ul>
     *   <li>{@code message} routes through {@code getMessage()} on the source: defined on
     *       both {@code Throwable} (for GENERIC/DATABASE handlers' matched exceptions) and
     *       {@code GraphQLError} (for VALIDATION-derived sources).</li>
     *   <li>{@code path} synthesises the value from the GraphQL execution context's path
     *       (the SDL field path where the error fired) for non-{@code GraphQLError} sources.
     *       VALIDATION sources route through {@code GraphQLError.getPath()} so the per-element
     *       paths recorded by {@code ConstraintViolations.toGraphQLError} survive intact.</li>
     * </ul>
     */
    private static CodeBlock buildErrorTypeFieldFetchers(String typeName) {
        var FIELD_COORDINATES = ClassName.get("graphql.schema", "FieldCoordinates");
        var GRAPHQL_ERROR     = ClassName.get("graphql", "GraphQLError");
        var THROWABLE         = ClassName.get(Throwable.class);
        var STRING_CN         = ClassName.get(String.class);

        var cb = CodeBlock.builder();
        // path
        cb.add("codeRegistry.dataFetcher($T.coordinates($S, $S), env -> {\n",
            FIELD_COORDINATES, typeName, "path").indent();
        cb.addStatement("Object src = env.getObject()");
        cb.beginControlFlow("if (src instanceof $T ge)", GRAPHQL_ERROR);
        cb.addStatement("return ge.getPath() == null ? java.util.List.of() : "
            + "ge.getPath().stream().map($T::valueOf).toList()", STRING_CN);
        cb.endControlFlow();
        cb.addStatement("return env.getExecutionStepInfo().getPath().toList().stream()"
            + ".map($T::valueOf).toList()", STRING_CN);
        cb.unindent().add("});\n");
        // message
        cb.add("codeRegistry.dataFetcher($T.coordinates($S, $S), env -> {\n",
            FIELD_COORDINATES, typeName, "message").indent();
        cb.addStatement("Object src = env.getObject()");
        cb.beginControlFlow("if (src instanceof $T ge)", GRAPHQL_ERROR);
        cb.addStatement("return ge.getMessage()");
        cb.endControlFlow();
        cb.beginControlFlow("if (src instanceof $T thr)", THROWABLE);
        cb.addStatement("return thr.getMessage()");
        cb.endControlFlow();
        cb.addStatement("return null");
        cb.unindent().add("});\n");
        return cb.build();
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
