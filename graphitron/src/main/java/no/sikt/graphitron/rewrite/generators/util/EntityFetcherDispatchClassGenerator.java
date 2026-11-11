package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.emit.EntityKeyQuery;
import no.sikt.graphitron.rewrite.model.TenantBinding;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code EntityFetcherDispatch} class, the runtime dispatcher for the
 * Apollo Federation {@code Query._entities(representations:)} field.
 *
 * <p>Rendered from the store's entities ({@link no.sikt.graphitron.emit.EntityKeyQuery}), and
 * nothing when there are none. The generated class:
 * <ol>
 *   <li>{@code fetchEntities(env)}: reads {@code representations}, answers every one this
 *       subgraph cannot resolve (no {@code __typename}, or one outside
 *       {@code RESOLVABLE_TYPENAMES}) with a {@code null} slot and a client error at that slot's
 *       path, dispatches the rest per {@code __typename} to a per-type handler, and returns
 *       {@code CompletableFuture<DataFetcherResult<List<Object>>>} preserving exact rep order.</li>
 *   <li>{@code handle<TypeName>(reps, indices, env, result)}: for each rep, selects the
 *       most-specific resolvable key alternative, decodes the rep into a column-value
 *       row (DIRECT: {@code ColumnValues.coerce}; NODE_ID: {@code NodeIdEncoder.decodeColumns}), and groups
 *       by alternative-index for batching.</li>
 *   <li>{@code select<TypeName>Alt<N>(bindings, env, dsl, result)}: issues one SELECT per
 *       group via a {@code VALUES (idx, col1, col2, ...)} derived table joined to the type's
 *       jOOQ table, projecting {@code <TypeName>.$project(...)} plus the literal
 *       {@code __typename} column. Result rows scatter back to original positions via the
 *       {@code idx} column.</li>
 * </ol>
 *
 * <p>Each rep gets a per-rep DFE (with {@code arguments(rep)}) so any in-rep argument reads
 * inside the dispatched alternative resolve against the individual rep, not the outer
 * representations list.
 *
 * <p>Slot contract of {@code resolveByReps}, one shape per build mode: a single-tenant build's
 * resolved entries are jOOQ {@code Record}s; a multi-tenant build's are
 * {@code DataFetcherResult<Record>}s, stamped with the tenant their group was routed on for a
 * tenant-scoped entity (so an inherited child below the entity reads that tenant) and unstamped
 * for a global one. {@code fetchEntities}, {@code Query.node} and {@code Query.nodes} pass the
 * entries through as field values or list elements, which graphql-java unwraps one level each.
 *
 * <p>{@code resolveType} is a separate static helper reading the synthetic {@code __typename}
 * column projected on every entity row (graphql-java has unwrapped any {@code DataFetcherResult}
 * by then). Consumers who override {@code fetchEntities} and return a non-{@link org.jooq.Record}
 * shape get {@code null} back, and graphql-java reports that it could not resolve the entity's type.
 */
public final class EntityFetcherDispatchClassGenerator {

    public static final String CLASS_NAME = "EntityFetcherDispatch";
    public static final String FETCH_ENTITIES_METHOD = "fetchEntities";
    public static final String RESOLVE_BY_REPS_METHOD = "resolveByReps";
    public static final String RESOLVE_TYPE_METHOD = "resolveType";
    public static final String TYPENAME_FOR_TYPE_ID_METHOD = "typenameForTypeId";
    /** The emitted set of the entity typenames this subgraph resolves: the {@code fetchEntities} gate. */
    public static final String RESOLVABLE_TYPENAMES_FIELD = "RESOLVABLE_TYPENAMES";
    /**
     * The client error for a representation whose {@code __typename} this subgraph does not resolve,
     * a {@link String#format} pattern over the typename as received. Graphitron 9's text verbatim.
     */
    public static final String UNRESOLVABLE_TYPENAME_MESSAGE = "Cannot resolve entity with __typename '%s'.";
    /** The client error for a representation that is not an object carrying a string {@code __typename}. */
    public static final String MISSING_TYPENAME_MESSAGE = "Cannot resolve entity: the representation has no __typename.";
    /**
     * Synthetic result-set column carrying the participant typename. The {@code __}-wrapping is a
     * deliberate collision-avoidance device (the alias shares the column namespace with
     * consumer-controlled table columns), not the dunder convention banned for Java identifiers;
     * it reaches generated code only as a string literal, which the dunder guard
     * ({@code DunderFreeEmissionPipelineTest}) masks before scanning. The literal's one home is
     * {@link no.sikt.graphitron.command.ReservedAliases#TYPENAME}.
     */
    public static final String TYPENAME_COLUMN = no.sikt.graphitron.command.ReservedAliases.TYPENAME;

    private static final ClassName ENV          = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName ENV_IMPL     = ClassName.get("graphql.schema", "DataFetchingEnvironmentImpl");
    private static final ClassName OBJ_TYPE     = ClassName.get("graphql.schema", "GraphQLObjectType");
    private static final ClassName LIST         = ClassName.get("java.util", "List");
    private static final ClassName MAP          = ClassName.get("java.util", "Map");
    private static final ClassName SET          = ClassName.get("java.util", "Set");
    private static final ClassName DATA_FETCHER_RESULT = ClassName.get("graphql.execution", "DataFetcherResult");
    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphQLError");
    private static final ClassName ARRAYS       = ClassName.get("java.util", "Arrays");
    private static final ClassName ARRAY_LIST   = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName CF           = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName RECORD       = ClassName.get("org.jooq", "Record");
    private static final ClassName TABLE        = ClassName.get("org.jooq", "Table");
    private static final ClassName FIELD        = ClassName.get("org.jooq", "Field");
    private static final ClassName DSL_CONTEXT  = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL          = ClassName.get("org.jooq.impl", "DSL");

    private EntityFetcherDispatchClassGenerator() {}

    /**
     * The dispatcher over {@code entities}, the store's entities in type name order. {@code tenantKey}
     * is the request tenant set's element type in a multi-tenant build and empty in a single-tenant
     * one, and {@code tenantRouting} says which entities route per tenant, by what; a single-tenant
     * build passes {@link EntityTenantRouting#none()}.
     */
    public static List<TypeSpec> generate(List<EntityKeyQuery.Entity> entities, EntityTenantRouting tenantRouting,
                                          java.util.Optional<TypeName> tenantKey, String outputPackage) {
        if (entities.isEmpty()) {
            return List.of();
        }

        var graphitronContext = ClassName.get(outputPackage + ".schema", "GraphitronContext");
        var nodeIdEncoder = ClassName.get(outputPackage + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);
        // Multi-tenant routing for the dispatch surface: each tenant-scoped entity's grouping
        // widens per tenant at its classified tenant sources; global entities read the
        // default source. Null in single-tenant builds (byte-identical emission).
        boolean multiTenant = tenantKey.isPresent();
        var tenantConnections = ClassName.get(outputPackage + ".schema",
            ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Runtime dispatcher for the Apollo Federation\n"
                + "{@code Query._entities(representations:)} field. See\n"
                + "{@link EntityFetcherDispatchClassGenerator} for emission semantics.\n")
            .addField(buildResolvableTypenamesField(entities))
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(buildFetchEntitiesMethod(outputPackage))
            .addMethod(buildResolveByRepsMethod(entities, multiTenant))
            .addMethod(buildResolveTypeMethod())
            .addMethod(buildTypenameForTypeIdMethod(entities))
            .addMethod(buildContextHelper(graphitronContext));

        for (var entity : entities) {
            HandleMethodBody.TenantRouting routing = multiTenant
                ? new HandleMethodBody.TenantRouting(tenantConnections,
                    tenantRouting.boundOf(entity.typeName()).orElse(null), tenantKey.orElseThrow())
                : null;
            spec.addMethod(buildHandleMethod(entity, outputPackage, nodeIdEncoder, routing));
            var bound = routing == null ? null : routing.bound();
            for (int i = 0; i < entity.alternatives().size(); i++) {
                var alt = entity.alternatives().get(i);
                if (!alt.resolvable()) continue;
                var source = bound == null ? null : HandleMethodBody.tenantSource(entity, bound, alt, i);
                spec.addMethod(buildSelectMethod(entity, alt, i, outputPackage, source));
            }
        }

        return List.of(spec.build());
    }

    // ----- Method emitters -----

    /**
     * {@code RESOLVABLE_TYPENAMES}: the entities with at least one resolvable key alternative,
     * sorted. A type outside it is one the router should never send here.
     */
    private static FieldSpec buildResolvableTypenamesField(List<EntityKeyQuery.Entity> entities) {
        var names = entities.stream()
            .filter(EntityKeyQuery.Entity::resolvable)
            .map(EntityKeyQuery.Entity::typeName)
            .sorted()
            .map(name -> CodeBlock.of("$S", name))
            .collect(CodeBlock.joining(", "));
        return FieldSpec.builder(ParameterizedTypeName.get(SET, ClassName.get(String.class)),
                RESOLVABLE_TYPENAMES_FIELD, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.of($L)", SET, names)
            .build();
    }

    private static MethodSpec buildFetchEntitiesMethod(String outputPackage) {
        var units = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage);
        var clientExceptionUnit = units.clientException();
        var clientException = ClassName.get(clientExceptionUnit.packageName(), clientExceptionUnit.simpleName());
        var errorRouter = no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.routerClass(outputPackage);
        var listOfObject = ParameterizedTypeName.get(LIST, ClassName.get(Object.class));

        var b = CodeBlock.builder();
        b.addStatement("$T<?> received = env.getArgument($S)", LIST, "representations");
        b.beginControlFlow("if (received == null || received.isEmpty())");
        b.addStatement("return $T.completedFuture($T.<$T>newResult().data($T.of()).build())",
            CF, DATA_FETCHER_RESULT, listOfObject, LIST);
        b.endControlFlow();
        b.add("// A representation this subgraph does not resolve keeps its slot null and gets an error\n");
        b.add("// there; it reaches the dispatch as null, so it takes no connection and fires no SQL.\n");
        b.addStatement("$T<$T<String, Object>> reps = new $T<>(received.size())", LIST, MAP, ARRAY_LIST);
        b.addStatement("$T<$T> errors = new $T<>()", LIST, GRAPHQL_ERROR, ARRAY_LIST);
        b.beginControlFlow("for (int i = 0; i < received.size(); i++)");
        b.addStatement("Object rep = received.get(i)");
        b.addStatement("Object tn = rep instanceof $T<?, ?> fields ? fields.get($S) : null", MAP, TYPENAME_COLUMN);
        b.beginControlFlow("if (tn instanceof String typeName && $L.contains(typeName))", RESOLVABLE_TYPENAMES_FIELD);
        b.addStatement("reps.add(($T<String, Object>) rep)", MAP);
        b.addStatement("continue");
        b.endControlFlow();
        b.addStatement("reps.add(null)");
        b.addStatement("String message = tn instanceof String named ? String.format($S, named) : $S",
            UNRESOLVABLE_TYPENAME_MESSAGE, MISSING_TYPENAME_MESSAGE);
        b.addStatement("errors.add($T.clientErrorAt(new $T(message), env, i))", errorRouter, clientException);
        b.endControlFlow();
        b.addStatement("Object[] result = $L(reps, env)", RESOLVE_BY_REPS_METHOD);
        b.addStatement("return $T.completedFuture($T.<$T>newResult().data($T.asList(result)).errors(errors).build())",
            CF, DATA_FETCHER_RESULT, listOfObject, ARRAYS);
        return MethodSpec.methodBuilder(FETCH_ENTITIES_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "$S", "unchecked").build())
            .returns(ParameterizedTypeName.get(CF, ParameterizedTypeName.get(DATA_FETCHER_RESULT, listOfObject)))
            .addParameter(ENV, "env")
            .addJavadoc("The {@code _entities} data fetcher. Answers one slot per representation, in the\n"
                + "order received. A representation that is not an object with a string\n"
                + "{@code __typename}, or whose {@code __typename} is not in {@link #$L} (a type this\n"
                + "schema does not declare, or an entity whose every key is {@code resolvable: false}),\n"
                + "gets {@code null} and a client error located at its slot\n"
                + "({@code [\"_entities\", <index>]}); the router only sends a subgraph the types it\n"
                + "declared resolvable, so either means the supergraph and this subgraph disagree. Every\n"
                + "other representation goes to {@link #$L}, which answers {@code null} with no error\n"
                + "for one that names no row.\n",
                RESOLVABLE_TYPENAMES_FIELD, RESOLVE_BY_REPS_METHOD)
            .addCode(b.build())
            .build();
    }

    private static MethodSpec buildResolveByRepsMethod(List<EntityKeyQuery.Entity> entities, boolean multiTenant) {
        var listOfMap = ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class)));
        return MethodSpec.methodBuilder(RESOLVE_BY_REPS_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(com.palantir.javapoet.ArrayTypeName.of(ClassName.get(Object.class)))
            .addParameter(listOfMap, "reps")
            .addParameter(ENV, "env")
            .addJavadoc("Synchronous dispatch core. Takes a representations list directly\n"
                + "(not via {@code DataFetchingEnvironment.getArgument}) so callers like\n"
                + "{@code QueryNodeFetcher.rowsNodes} can synthesise reps from a list of\n"
                + "Relay node ids and reuse the same SELECT path.\n"
                + "Returns an {@code Object[]} sized to {@code reps.size()}; "
                + (multiTenant
                    ? "entries are\n"
                        + "{@code DataFetcherResult<Record>}s for resolved reps or {@code null} for unresolved.\n"
                        + "Each is the field value graphql-java completes for its rep: a tenant-scoped\n"
                        + "entity's carries the tenant its rep names as {@code localContext}, so the\n"
                        + "entity's subtree reads that tenant; a global entity's carries none. Return\n"
                        + "entries as received; wrapping one again hides its row from type resolution.\n"
                    : "entries are\n"
                        + "jOOQ {@code Record}s for resolved reps or {@code null} for unresolved.\n"))
            .addCode(resolveByRepsBody(entities))
            .build();
    }

    private static MethodSpec buildTypenameForTypeIdMethod(List<EntityKeyQuery.Entity> entities) {
        // Map keyed by each @node entity's NodeId expectedTypeId (differs from the typename when
        // the consumer sets @node(typeId: ...)). Used by QueryNodeFetcher.rowsNodes after
        // peekTypeId to recover the GraphQL typename for synthesising reps.
        var b = CodeBlock.builder();
        b.add("$T<String, String> $L = $T.ofEntries(",
            MAP, "MAP", MAP);
        boolean first = true;
        for (var entity : entities) {
            // Only @node entities carry a NodeId alternative (exactly one: required == ["id"]);
            // @key-only entities have none. Its expectedTypeId is the wire prefix, keyed to the
            // typename here. Inclusion deliberately ignores resolvable.
            var nodeId = entity.alternatives().stream()
                .filter(a -> a instanceof EntityKeyQuery.Alternative.NodeId)
                .map(a -> (EntityKeyQuery.Alternative.NodeId) a)
                .findFirst()
                .orElse(null);
            if (nodeId == null) continue;
            if (!first) b.add(",\n        ");
            else b.add("\n        ");
            first = false;
            b.add("$T.entry($S, $S)", MAP, nodeId.typeId(), entity.typeName());
        }
        b.add(");\n");
        var fieldInit = b.build();

        var holder = MethodSpec.methodBuilder(TYPENAME_FOR_TYPE_ID_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ClassName.get(String.class))
            .addParameter(String.class, "typeId")
            .addJavadoc("Returns the GraphQL typename for a given NodeId typeId prefix, or\n"
                + "{@code null} when no {@code @node} type matches. Used by\n"
                + "{@code QueryNodeFetcher.rowsNodes} to synthesise representations from\n"
                + "Relay ids before dispatching to {@link #" + RESOLVE_BY_REPS_METHOD + "}.\n")
            .addStatement("if (typeId == null) return null")
            .addCode(fieldInit)
            .addStatement("return MAP.get(typeId)")
            .build();
        return holder;
    }

    private static CodeBlock resolveByRepsBody(List<EntityKeyQuery.Entity> entities) {
        var b = CodeBlock.builder();
        b.addStatement("Object[] result = new Object[reps.size()]");
        b.addStatement("$T<String, $T> byType = new $T<>()",
            MAP, ParameterizedTypeName.get(LIST, ClassName.get(Integer.class)), LINKED_HASH_MAP);
        b.beginControlFlow("for (int i = 0; i < reps.size(); i++)");
        b.addStatement("$T<String, Object> rep = reps.get(i)", MAP);
        b.addStatement("if (rep == null) continue");
        b.addStatement("Object tn = rep.get($S)", TYPENAME_COLUMN);
        b.addStatement("if (!(tn instanceof String typeName)) continue");
        b.addStatement("byType.computeIfAbsent(typeName, k -> new $T<>()).add(i)", ARRAY_LIST);
        b.endControlFlow();
        for (var entity : entities) {
            b.beginControlFlow("if (byType.containsKey($S))", entity.typeName());
            b.addStatement("handle$L(reps, byType.get($S), env, result)",
                entity.typeName(), entity.typeName());
            b.endControlFlow();
        }
        b.addStatement("return result");
        return b.build();
    }

    private static MethodSpec buildResolveTypeMethod() {
        return MethodSpec.methodBuilder(RESOLVE_TYPE_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(OBJ_TYPE)
            .addParameter(ClassName.get("graphql", "TypeResolutionEnvironment"), "env")
            .addStatement("Object o = env.getObject()")
            .addStatement("if (!(o instanceof $T r)) return null", RECORD)
            .addStatement("Object tn = r.get($S)", TYPENAME_COLUMN)
            .addStatement("if (!(tn instanceof String typeName)) return null")
            .addStatement("return env.getSchema().getObjectType(typeName)")
            .build();
    }

    private static MethodSpec buildContextHelper(ClassName graphitronContext) {
        return MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(graphitronContext)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($T.class)", graphitronContext)
            .build();
    }

    private static MethodSpec buildHandleMethod(
        EntityKeyQuery.Entity entity, String outputPackage, ClassName nodeIdEncoder,
        HandleMethodBody.TenantRouting routing
    ) {
        return new HandleMethodEmitter(entity, outputPackage, nodeIdEncoder, routing).build();
    }

    private static MethodSpec buildSelectMethod(
        EntityKeyQuery.Entity entity, EntityKeyQuery.Alternative alt, int altIndex,
        String outputPackage, TenantBinding.EntityRepBound.TenantSource source
    ) {
        return new SelectMethodEmitter(entity, alt, altIndex, outputPackage, source).build();
    }

    // ----- Inner emitter classes -----

    private static final class HandleMethodEmitter {
        final EntityKeyQuery.Entity entity;
        final String outputPackage;
        final ClassName nodeIdEncoder;
        final HandleMethodBody.TenantRouting routing;

        HandleMethodEmitter(EntityKeyQuery.Entity entity, String outputPackage, ClassName nodeIdEncoder,
                            HandleMethodBody.TenantRouting routing) {
            this.entity = entity;
            this.outputPackage = outputPackage;
            this.nodeIdEncoder = nodeIdEncoder;
            this.routing = routing;
        }

        MethodSpec build() {
            var listOfInteger = ParameterizedTypeName.get(LIST, ClassName.get(Integer.class));
            var listOfMap = ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class)));
            return MethodSpec.methodBuilder("handle" + entity.typeName())
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(listOfMap, "reps")
                .addParameter(listOfInteger, "indices")
                .addParameter(ENV, "env")
                .addParameter(ArrayTypeName.of(ClassName.get(Object.class)), "result")
                .addCode(body())
                .build();
        }

        CodeBlock body() {
            return HandleMethodBody.emit(entity, nodeIdEncoder, routing);
        }
    }

    private static final class SelectMethodEmitter {
        final EntityKeyQuery.Entity entity;
        final EntityKeyQuery.Alternative alt;
        final int altIndex;
        final String outputPackage;
        final TenantBinding.EntityRepBound.TenantSource source;

        SelectMethodEmitter(EntityKeyQuery.Entity entity, EntityKeyQuery.Alternative alt, int altIndex,
                            String outputPackage, TenantBinding.EntityRepBound.TenantSource source) {
            this.entity = entity;
            this.alt = alt;
            this.altIndex = altIndex;
            this.outputPackage = outputPackage;
            this.source = source;
        }

        MethodSpec build() {
            return SelectMethodBody.buildMethod(entity, alt, altIndex, outputPackage, source);
        }
    }
}
