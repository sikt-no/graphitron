package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.MultiTablePolymorphicEmitter;
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
 * <p>Method body shape: one local {@code schemaBuilder} variable plus one statement per
 * registered element (root types, additional types, scalars, directive definitions, schema
 * applied directives). Non-trivial sub-values (synthesised scalars, directive definitions,
 * schema-level applied directives) reach the schemaBuilder through {@code private static}
 * factory methods on the emitted class, allocated by {@link HelperMethodSink}. The shape
 * keeps chain depth on every emitted expression-statement O(1) regardless of schema element
 * count; R254 motivates this against the chained-call attribution stack overflow in
 * incremental {@code javac}.
 *
 * <p>The generator collects type names from an assembled {@link GraphQLSchema} produced by
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilder}. Introspection and federation-
 * injected types are skipped to match the per-type emitters.
 */
public final class GraphitronSchemaClassGenerator {

    public static final String CLASS_NAME = "GraphitronSchema";

    private static final ClassName GRAPHQL_SCHEMA = ClassName.get("graphql.schema", "GraphQLSchema");
    private static final ClassName SCHEMA_BUILDER = ClassName.get("graphql.schema", "GraphQLSchema", "Builder");
    private static final ClassName CODE_REGISTRY  = ClassName.get("graphql.schema", "GraphQLCodeRegistry");
    private static final ClassName CODE_REGISTRY_BLDR = ClassName.get("graphql.schema", "GraphQLCodeRegistry", "Builder");

    /**
     * Synthetic result-set column carrying the participant typename, read off the jOOQ {@code Record}
     * inside the emitted polymorphic {@code typeResolver}. The {@code __}-wrapping is a deliberate
     * collision-avoidance device (the alias shares the column namespace with consumer-controlled
     * table columns), not the lazy dunder convention banned for Java locals; it reaches generated
     * code as a string literal. The spelling deliberately mirrors the GraphQL introspection
     * {@code __typename} meta-field the column ultimately feeds, but the two live in different
     * namespaces: this constant is the SQL column projected by {@code MultiTablePolymorphicEmitter},
     * whereas the federation {@code _entities} resolver below reads the GraphQL {@code __typename}
     * straight off the gateway's representation map (see that site's note).
     */
    private static final String TYPENAME_COLUMN = "__typename";

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

        var sink = new HelperMethodSink();
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
                    .filter(p -> p instanceof ParticipantRef.TableBacked tb && tb.discriminatorValue() != null)
                    .map(p -> (ParticipantRef.TableBacked) p)
                    .toList();
                if (tableBound.isEmpty()) return;
                var cb = CodeBlock.builder();
                cb.add("codeRegistry.typeResolver($S, env -> {\n", tit.name()).indent();
                cb.addStatement("$T record = ($T) env.getObject()", JOOQ_RECORD, JOOQ_RECORD);
                // Route off the synthetic discriminator alias the interface fetcher projects
                // (TypeFetcherGenerator.buildInterfaceFieldsList), not the raw discriminator column name:
                // when the interface exposes the discriminator as a queryable field, the real column is
                // also projected by the participant $fields, and a bare read of the column name matches
                // both ambiguously. The alias is distinct from any real column, so the read is unambiguous.
                cb.addStatement("String discriminatorValue = record.get($T.field($T.name($S)), String.class)",
                    JOOQ_DSL, JOOQ_DSL, MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN);
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
        // projected by the multi-table polymorphic emitter's stage-1 narrow union. The Node interface is
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
                    JOOQ_DSL, JOOQ_DSL, TYPENAME_COLUMN);
                cb.addStatement("return typeName == null ? null : env.getSchema().getObjectType(typeName)");
                cb.unindent().add("});\n");
                body.add(cb.build());
            });

        // @error-only union/interface TypeResolvers: source-class-instanceof dispatch.
        // The runtime source for each entry in a payload's errors list is the
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

        // Per-@error-type path/message DataFetchers. The SDL grammar restricts
        // @error types to {path: [String!]!, message: String!}; the @error type's source
        // class can be a Throwable (no getPath()) or a GraphQLError (has getPath()/getMessage()).
        // Synthesize the path field from the GraphQL execution context for non-GraphQLError
        // sources so the schema's non-null contract holds regardless of handler kind. Message
        // routes universally through getMessage(), defined on both Throwable and GraphQLError.
        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof ErrorType)
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> body.add(buildErrorTypeFieldFetchers((ErrorType) e.getValue(), outputPackage)));

        body.addStatement("$T schemaBuilder = $T.newSchema()", SCHEMA_BUILDER, GRAPHQL_SCHEMA);

        if (plan.hasQuery)        body.addStatement("schemaBuilder.query($T.type())",        ClassName.get(schemaPackage, "QueryType"));
        if (plan.hasMutation)     body.addStatement("schemaBuilder.mutation($T.type())",     ClassName.get(schemaPackage, "MutationType"));
        if (plan.hasSubscription) body.addStatement("schemaBuilder.subscription($T.type())", ClassName.get(schemaPackage, "SubscriptionType"));
        for (String name : plan.additionalTypeNames) {
            body.addStatement("schemaBuilder.additionalType($T.type())", ClassName.get(schemaPackage, name + "Type"));
        }
        // Built-in GraphQL scalars aren't auto-registered on a programmatic schema; the SDL
        // path in SchemaGenerator used to add them for us. The classifier resolves every SDL
        // scalar through ScalarTypeResolver, and the resulting ScalarType variants drive the
        // registration here. Resolved scalars (spec built-ins and @scalarType-declared) surface
        // as (owner, fieldName) pointing at a public-static-final GraphQLScalarType constant;
        // Synthesised scalars (federation-namespace names whose renamed forms have no constant
        // exposed on the federation-jvm public API) reach the builder through a per-scalar
        // factory method that constructs the GraphQLScalarType inline.
        for (var reg : plan.scalarRegistrations) {
            switch (reg.resolution()) {
                case no.sikt.graphitron.rewrite.model.ScalarResolution.Resolved r ->
                    body.addStatement("schemaBuilder.additionalType($T.$L)", r.scalarConstantOwner(), r.scalarConstantField());
                case no.sikt.graphitron.rewrite.model.ScalarResolution.Synthesised s -> {
                    String helper = sink.addSynthesisedScalar(s);
                    body.addStatement("schemaBuilder.additionalType($L())", helper);
                }
            }
        }
        for (var dir : DirectiveDefinitionEmitter.survivors(assembled)) {
            String helper = sink.addDirectiveDefinition(dir);
            body.addStatement("schemaBuilder.additionalDirective($L())", helper);
        }
        AppliedDirectiveEmitter.emitSchemaApplications(body, "schemaBuilder", assembled, sink);
        body.addStatement("schemaBuilder.codeRegistry(codeRegistry.build())");

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
                        // The federation gateway's entity representation is a wire-format map keyed
                        // by the GraphQL introspection __typename meta-field (federation spec), not
                        // the synthetic SQL TYPENAME_COLUMN above; left as the literal field name.
                        .addStatement("Object tn = m.get(\"__typename\")")
                        .addStatement("return tn != null ? ($T) env.getSchema().getObjectType(tn.toString()) : null", OBJ_TYPE)
                        .unindent().add("})\n")
                    .add(".fetchEntities(env -> java.util.List.of());\n")
                    .unindent();
            }
            twoArgBody.addStatement("federationCustomizer.accept(fb)");
            // R283: SchemaTransformer.build bakes the _Service.sdl value via
            // ServiceSDLPrinter.generateServiceSDLV2, which strips the spec-built-in @oneOf
            // definition. When the schema uses @oneOf, route the returned schema through the
            // generated OneOfDirectiveSdl helper to reinstate the definition on the served SDL;
            // otherwise emit the plain return verbatim (byte-identical to before).
            if (OneOfDirectiveSdl.usesOneOf(assembled)) {
                var ONE_OF_SDL = ClassName.get(outputPackage + ".util",
                    no.sikt.graphitron.rewrite.generators.util.OneOfDirectiveSdlGenerator.CLASS_NAME);
                twoArgBody.addStatement("return $T.$L(fb.build())", ONE_OF_SDL,
                    no.sikt.graphitron.rewrite.generators.util.OneOfDirectiveSdlGenerator.WITH_ONE_OF_DEFINITION_METHOD);
            } else {
                twoArgBody.addStatement("return fb.build()");
            }

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
        sink.contributeTo(classBuilder);

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
     * dispatch by runtime source class (Throwable / GraphQLError).
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
     * resulting {@code if} ladder mirrors the dispatcher's source-order-first-match semantics.
     * The first {@code instanceof} predicate that satisfies returns
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
     * Emits {@code codeRegistry.dataFetcher(...)} calls for one @error type. The required
     * {@code path} and {@code message} fields (both {@code [String!]!} / {@code String!}, so a
     * missing or null value would violate the schema's non-null contract) get registered fetchers;
     * any extra field carrying {@code @field(name:)} gets a {@code PropertyDataFetcher} keyed on the
     * override's accessor base so the runtime read matches the classify-time accessor check.
     * Extra fields without the directive keep resolving through graphql-java's default
     * {@code PropertyDataFetcher} (by SDL field name), so they need no registration here.
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
     *
     * <p>R303: the read bodies are reified onto {@code <ErrorType>Fetchers} by
     * {@link no.sikt.graphitron.rewrite.generators.util.ErrorTypeFetcherClassGenerator}; this site
     * only wires the {@code <ErrorType>Fetchers::path} / {@code ::message} references.
     */
    private static CodeBlock buildErrorTypeFieldFetchers(ErrorType errorType, String outputPackage) {
        String typeName       = errorType.name();
        var FIELD_COORDINATES = ClassName.get("graphql.schema", "FieldCoordinates");
        var PROPERTY_FETCHER  = ClassName.get("graphql.schema", "PropertyDataFetcher");
        var fetchers          = ClassName.get(outputPackage + ".fetchers", typeName + "Fetchers");
        var cb = CodeBlock.builder()
            .addStatement("codeRegistry.dataFetcher($T.coordinates($S, $S), $T::path)",
                FIELD_COORDINATES, typeName, "path", fetchers)
            .addStatement("codeRegistry.dataFetcher($T.coordinates($S, $S), $T::message)",
                FIELD_COORDINATES, typeName, "message", fetchers);
        for (var override : errorType.accessorOverrides()) {
            cb.addStatement("codeRegistry.dataFetcher($T.coordinates($S, $S), $T.fetching($S))",
                FIELD_COORDINATES, typeName, override.sdlFieldName(),
                PROPERTY_FETCHER, override.accessorBase());
        }
        return cb.build();
    }

    record Plan(
        boolean hasQuery,
        boolean hasMutation,
        boolean hasSubscription,
        List<String> additionalTypeNames,
        List<ScalarRegistration> scalarRegistrations
    ) {}

    /**
     * One scalar registration call in the emitted {@code GraphitronSchema.build()} body. Carries
     * the SDL name (for stable sort order) and the resolved variant: {@link ScalarResolution.Resolved}
     * emits {@code .additionalType(<owner>.<field>)}; {@link ScalarResolution.Synthesised} emits
     * a call to a generated factory method that constructs the {@code GraphQLScalarType} inline
     * for scalars (notably federation-namespace ones) that have no referenceable
     * public-static-final form.
     */
    record ScalarRegistration(String sdlName,
                              no.sikt.graphitron.rewrite.model.ScalarResolution.Successful resolution) {}

    /**
     * Enumerates the types that need registration in the emitted {@code GraphitronSchema.build()}.
     *
     * <p>Source of truth is {@link GraphitronSchema#types()}, which contains every
     * emittable type — objects, interfaces, unions, inputs, enums, SDL-declared and synthesised
     * alike. The assembled schema is no longer consulted here.
     *
     * <p>Scalars classified as {@link no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType}
     * are split out of {@code additionalTypeNames} into {@code scalarRegistrations}: the regular
     * loop emits {@code <Name>Type.type()} for object / enum / input types, while the scalar
     * loop emits {@code <Owner>.<FieldName>} pointing at the resolved {@code GraphQLScalarType}
     * constant. Replaces the literal {@code .additionalType(Scalars.GraphQLInt)} ... block that
     * lived here pre-R101.
     */
    static Plan planFor(GraphitronSchema schema, GraphQLSchema assembled) {
        var additional = new java.util.LinkedHashSet<String>();
        var scalars = new ArrayList<ScalarRegistration>();
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
            if (variant instanceof no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType scalar) {
                scalars.add(new ScalarRegistration(scalar.name(), scalar.resolution()));
                continue;
            }
            additional.add(name);
        }

        var sorted = new ArrayList<>(additional);
        sorted.sort(Comparator.naturalOrder());
        scalars.sort(Comparator.comparing(ScalarRegistration::sdlName));
        return new Plan(hasQuery, hasMutation, hasSubscription, List.copyOf(sorted), List.copyOf(scalars));
    }
}
