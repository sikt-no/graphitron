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
import no.sikt.graphitron.emit.ConnectionKeyShape;
import no.sikt.graphitron.emit.EntityKeyQuery;
import no.sikt.graphitron.render.ConnectionKeyHandDown;
import no.sikt.graphitron.render.LoaderConstruction;
import no.sikt.graphitron.rewrite.model.TenantBinding;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code EntityFetcherDispatch} class, the runtime dispatcher for the
 * Apollo Federation {@code Query._entities(representations:)} field, and the one way
 * {@code Query.node} and {@code Query.nodes} read an entity too.
 *
 * <p>Rendered from the store's entities ({@link no.sikt.graphitron.emit.EntityKeyQuery}), and
 * nothing when there are none. In a build without a tenant column the generated class reads every
 * entity through DataLoaders:
 * <ol>
 *   <li>{@code fetchEntities(env)}: reads {@code representations}, answers every one this
 *       subgraph cannot resolve (no {@code __typename}, or one outside
 *       {@code RESOLVABLE_TYPENAMES}) with a {@code null} slot and a client error at that slot's
 *       path, reads the rest through {@code loadSlot}, and combines the slots in representation
 *       order ({@code inOrder}) into a {@code CompletableFuture<DataFetcherResult<List<Object>>>}.</li>
 *   <li>{@code load(rep, env)}: dispatches on {@code __typename} to {@code load<TypeName>}, which
 *       selects the resolvable key alternative (a voucher first, then the most specific), decodes the
 *       rep into the alternative's key values (DIRECT: {@code ColumnValues.coerce}; NODE_ID:
 *       {@code NodeIdEncoder.decodeColumns}) and loads them, with a per-rep environment, from the
 *       positional loader for (field path, type, alternative, connection key), named by
 *       {@code OwnedConnections.entityLoaderName} and built with the request's
 *       {@code DataLoaderOptions}, so a {@code BatchSizeLimit} bounds it. A plain key and a node id
 *       give the connection key with no parts.</li>
 *   <li>In a build that accepts vouchers, a representation carrying a voucher is verified: the codec
 *       decodes and checks it ({@code VoucherCodec.Verifier.verify}: format, signature over the token
 *       and the {@code __typename}, expiry, scope, and the fields each section names), the dispatcher
 *       coerces each vouched-for value once and each lookup value to its column through the column's
 *       {@code DataType}, fills the authored key's columns each from the section that supplies it, and
 *       admits the vouched-for values into the operation's set ({@code OwnedConnections.admitVouched}):
 *       the first values in representation order fill it, up to the runtime's
 *       {@code VouchedValueLimit}. An admitted representation answers a pending load, and once
 *       {@code fetchEntities} has walked every representation it closes the set and registers the
 *       pending loads under the operation's one vouched key, in the loader for the authored key each
 *       voucher decodes onto, reusing that key's batch and SELECT: one lease for every admitted value.
 *       A voucher that fails any check, or carries a value no column takes, rejects the request
 *       through the carrier's one rejection slot; registration happens before any loader dispatches,
 *       so nothing is acquired after it. A verified voucher whose value is past the cap fails its own
 *       slot alone with the generated client exception, naming the limit and no value, and takes no
 *       connection. Every redemption, rejection and representation the cap turns away is logged on
 *       {@value #VOUCHER_AUDIT_LOGGER} at INFO by vouch id, scope and outcome, never the token, its
 *       values or the connection key.</li>
 *   <li>{@code batch<TypeName>Alt<N>(keys, batchEnv, key)}: the loader's batch, acquiring through
 *       {@code OwnedConnections.dslForIfPermitted} (a key the source refuses answers every slot
 *       {@code null} with no error) and running {@code select<TypeName>Alt<N>}; a throw fails only
 *       the batch's own slots.</li>
 *   <li>{@code select<TypeName>Alt<N>(bindings, env, dsl, result)}: issues one SELECT per
 *       batch via a {@code VALUES (idx, col1, col2, ...)} derived table joined to the type's
 *       jOOQ table, projecting {@code <TypeName>.$project(...)} plus the literal
 *       {@code __typename} column. Result rows scatter back to original positions via the
 *       {@code idx} column.</li>
 * </ol>
 *
 * <p>A multi-tenant build keeps the synchronous dispatch: {@code fetchEntities} hands the
 * representations to {@code resolveByReps}, which groups them per type in
 * {@code handle<TypeName>} ({@link HandleMethodBody}), per alternative and per decoded tenant,
 * and runs the same {@code select<TypeName>Alt<N>} per group. Its resolved slots are
 * {@code DataFetcherResult<Record>}s, stamped with the tenant their group was routed on for a
 * tenant-scoped entity (so an inherited child below the entity reads that tenant) and unstamped
 * for a global one. {@code fetchEntities}, {@code Query.node} and {@code Query.nodes} pass the
 * entries through as field values or list elements, which graphql-java unwraps one level each.
 *
 * <p>Each rep gets a per-rep DFE (with {@code arguments(rep)}) so any in-rep argument reads
 * inside the dispatched alternative resolve against the individual rep, not the outer
 * representations list.
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
    /** A build without a tenant column: one representation's entity, read through its loader. */
    public static final String LOAD_METHOD = "load";
    /** The keyed sibling of {@link #LOAD_METHOD}, in a build that accepts vouchers. */
    public static final String LOAD_KEYED_METHOD = "loadKeyed";
    /** A build without a tenant column: one list slot's answer, its failure routed at the slot. */
    public static final String LOAD_SLOT_METHOD = "loadSlot";
    /** A build without a tenant column: list slots combined, in order, into the list field's result. */
    public static final String IN_ORDER_METHOD = "inOrder";
    public static final String RESOLVE_TYPE_METHOD = "resolveType";
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
    /**
     * The logger an accepting build's dispatch writes its voucher audit lines to, at INFO: the vouch id,
     * scope and outcome of every redemption and rejection, never the token, its values or the key.
     */
    public static final String VOUCHER_AUDIT_LOGGER = "no.sikt.graphitron.voucher.audit";
    /**
     * The message a representation carrying a voucher fails with where it reaches the dispatch other than
     * through {@code _entities}, the one field that closes an operation's vouched-for set: a generator
     * fault, since a representation built from a node id carries no voucher.
     */
    public static final String VOUCHER_OUTSIDE_ENTITIES_MESSAGE =
        "A representation carrying a voucher reached the entity dispatch outside _entities, where no"
            + " vouched-for set closes";
    /** The simple name of the dispatch's nested holder of an {@code _entities} field's pending loads. */
    static final String PENDING_LOADS_CLASS_NAME = "PendingLoads";

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
    private static final ClassName DATA_LOADER  = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName BATCH_LOADER_ENV = ClassName.get("org.dataloader", "BatchLoaderEnvironment");
    private static final ClassName STEP_INFO    = ClassName.get("graphql.execution", "ExecutionStepInfo");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
    private static final ClassName UUID_CLASS   = ClassName.get("java.util", "UUID");

    private EntityFetcherDispatchClassGenerator() {}

    /**
     * The dispatcher over {@code entities}, the store's entities in type name order. {@code shape} is
     * the build's connection key shape, whose tenant part decides the read path: a build without one
     * reads every entity through loaders per (type, key alternative, connection key), and a multi-tenant
     * build keeps the synchronous per-tenant grouping, with {@code tenantRouting} saying which entities
     * route per tenant, by what. A build without a tenant part passes {@link EntityTenantRouting#none()}.
     */
    public static List<TypeSpec> generate(List<EntityKeyQuery.Entity> entities, EntityTenantRouting tenantRouting,
                                          ConnectionKeyShape shape, String outputPackage) {
        if (entities.isEmpty()) {
            return List.of();
        }

        var graphitronContext = ClassName.get(outputPackage + ".schema", "GraphitronContext");
        var nodeIdEncoder = ClassName.get(outputPackage + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);
        boolean multiTenant = shape.hasTenant();
        var ownedConnections = ClassName.get(outputPackage + ".schema",
            ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Runtime dispatcher for the Apollo Federation\n"
                + "{@code Query._entities(representations:)} field. See\n"
                + "{@link EntityFetcherDispatchClassGenerator} for emission semantics.\n")
            .addField(buildResolvableTypenamesField(entities))
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());

        if (!multiTenant) {
            boolean accepts = entities.stream().flatMap(e -> e.alternatives().stream())
                .anyMatch(EntityKeyQuery.Alternative.Voucher.class::isInstance);
            var loaders = new EntityLoaders(outputPackage, nodeIdEncoder, ownedConnections, accepts);
            if (accepts) {
                spec.addField(FieldSpec.builder(SLF4J_LOGGER, "VOUCHER_AUDIT",
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($S)", SLF4J_LOGGER_FACTORY, VOUCHER_AUDIT_LOGGER)
                        .addJavadoc("The voucher audit log: vouch id, scope and outcome, never the token or its values.\n")
                        .build())
                    .addMethod(loaders.rejectVoucher())
                    .addType(loaders.pendingLoads());
            }
            spec.addMethod(loaders.fetchEntities())
                .addMethod(loaders.load(entities));
            if (accepts) {
                spec.addMethod(loaders.loadKeyed(entities))
                    .addMethod(loaders.loadKeyedPending(entities));
            }
            spec.addMethod(loaders.loadSlot());
            if (accepts) {
                spec.addMethod(loaders.loadSlotPending());
            }
            spec
                .addMethod(loaders.inOrder())
                .addMethod(loaders.slotEnv())
                .addMethod(buildResolveTypeMethod());
            for (var entity : entities) {
                spec.addMethod(loaders.loadType(entity));
                for (int i = 0; i < entity.alternatives().size(); i++) {
                    var alt = entity.alternatives().get(i);
                    // A voucher alternative loads through the authored key it decodes onto, with that
                    // key's batch and SELECT, so it has none of its own.
                    if (!alt.resolvable() || alt instanceof EntityKeyQuery.Alternative.Voucher) continue;
                    spec.addMethod(loaders.batch(entity, i));
                    spec.addMethod(buildSelectMethod(entity, alt, i, outputPackage, null));
                }
            }
            return List.of(spec.build());
        }

        var tenantKey = shape.tenant().orElseThrow();
        spec.addMethod(buildFetchEntitiesMethod(outputPackage))
            .addMethod(buildResolveByRepsMethod(entities))
            .addMethod(buildResolveTypeMethod())
            .addMethod(buildContextHelper(graphitronContext));

        for (var entity : entities) {
            // Each tenant-scoped entity's grouping widens per tenant at its classified tenant
            // sources; global entities read the default source.
            var routing = new HandleMethodBody.TenantRouting(ownedConnections,
                tenantRouting.boundOf(entity.typeName()).orElse(null), tenantKey);
            spec.addMethod(buildHandleMethod(entity, outputPackage, nodeIdEncoder, routing));
            var bound = routing.bound();
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

    private static MethodSpec buildResolveByRepsMethod(List<EntityKeyQuery.Entity> entities) {
        var listOfMap = ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class)));
        return MethodSpec.methodBuilder(RESOLVE_BY_REPS_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(com.palantir.javapoet.ArrayTypeName.of(ClassName.get(Object.class)))
            .addParameter(listOfMap, "reps")
            .addParameter(ENV, "env")
            .addJavadoc("Synchronous dispatch core of a multi-tenant build. Takes a representations list\n"
                + "directly (not via {@code DataFetchingEnvironment.getArgument}) so callers like\n"
                + "{@code QueryNodeFetcher.rowsNodes} can synthesise reps from a list of\n"
                + "Relay node ids and reuse the same SELECT path.\n"
                + "Returns an {@code Object[]} sized to {@code reps.size()}; entries are\n"
                + "{@code DataFetcherResult<Record>}s for resolved reps or {@code null} for unresolved.\n"
                + "Each is the field value graphql-java completes for its rep: a tenant-scoped\n"
                + "entity's carries the tenant its rep names as {@code localContext}, so the\n"
                + "entity's subtree reads that tenant; a global entity's carries none. Return\n"
                + "entries as received; wrapping one again hides its row from type resolution.\n")
            .addCode(resolveByRepsBody(entities))
            .build();
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

    /**
     * The read path of a build without a tenant column: every representation's entity is read through
     * the loader for its (type, key alternative, connection key), so {@code _entities},
     * {@code Query.node} and {@code Query.nodes} read an entity one way and batch per type and
     * alternative under the request's {@code DataLoaderOptions}.
     */
    private static final class EntityLoaders {
        final String outputPackage;
        final ClassName nodeIdEncoder;
        final ClassName ownedConnections;
        final ClassName connectionKey;
        final ClassName errorRouter;
        final ClassName clientException;
        /** The private nested holder of an {@code _entities} field's admitted, not yet registered loads. */
        final ClassName pendingLoads;
        /**
         * Whether each entity row carries the connection key it was read on: in a build that accepts
         * vouchers, so a field below a vouched entity runs on the vouched key.
         */
        final boolean keyed;

        EntityLoaders(String outputPackage, ClassName nodeIdEncoder, ClassName ownedConnections, boolean keyed) {
            this.keyed = keyed;
            this.outputPackage = outputPackage;
            this.nodeIdEncoder = nodeIdEncoder;
            this.ownedConnections = ownedConnections;
            this.pendingLoads = ClassName.get(nodeIdEncoder.packageName(), CLASS_NAME, PENDING_LOADS_CLASS_NAME);
            this.connectionKey = ConnectionKeyShape.className(outputPackage);
            this.errorRouter = no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.routerClass(outputPackage);
            var clientExceptionUnit = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage).clientException();
            this.clientException = ClassName.get(clientExceptionUnit.packageName(), clientExceptionUnit.simpleName());
        }

        private static TypeName listOf(TypeName element) {
            return ParameterizedTypeName.get(LIST, element);
        }

        private static TypeName slotResult() {
            return ParameterizedTypeName.get(DATA_FETCHER_RESULT, ClassName.get(Object.class));
        }

        private static TypeName listResult() {
            return ParameterizedTypeName.get(DATA_FETCHER_RESULT, listOf(ClassName.get(Object.class)));
        }

        private static TypeName repType() {
            return ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class));
        }

        MethodSpec fetchEntities() {
            var listOfObject = listOf(ClassName.get(Object.class));
            var b = CodeBlock.builder();
            b.addStatement("$T<?> received = env.getArgument($S)", LIST, "representations");
            b.beginControlFlow("if (received == null || received.isEmpty())");
            b.addStatement("return $T.completedFuture($T.<$T>newResult().data($T.of()).build())",
                CF, DATA_FETCHER_RESULT, listOfObject, LIST);
            b.endControlFlow();
            b.addStatement("$T<$T<$T>> slots = new $T<>(received.size())", LIST, CF, slotResult(), ARRAY_LIST);
            if (keyed) {
                b.add("// Every voucher is verified and admitted, in representation order, before any loads: the\n");
                b.add("// admitted ones wait here, and register under the operation's vouched key after the loop.\n");
                b.addStatement("$T pending = new $T()", pendingLoads, pendingLoads);
            }
            b.beginControlFlow("for (int i = 0; i < received.size(); i++)");
            b.addStatement("Object rep = received.get(i)");
            b.addStatement("Object tn = rep instanceof $T<?, ?> fields ? fields.get($S) : null", MAP, TYPENAME_COLUMN);
            b.beginControlFlow("if (tn instanceof String typeName && $L.contains(typeName))", RESOLVABLE_TYPENAMES_FIELD);
            if (keyed) {
                b.addStatement("slots.add($L(($T) rep, env, i, pending))", LOAD_SLOT_METHOD, repType());
            } else {
                b.addStatement("slots.add($L(($T) rep, env, i))", LOAD_SLOT_METHOD, repType());
            }
            b.addStatement("continue");
            b.endControlFlow();
            b.add("// A representation this subgraph does not resolve keeps its slot null and gets an error\n");
            b.add("// there; it reaches no loader, so it takes no connection and fires no SQL.\n");
            b.addStatement("String message = tn instanceof String named ? String.format($S, named) : $S",
                UNRESOLVABLE_TYPENAME_MESSAGE, MISSING_TYPENAME_MESSAGE);
            b.addStatement("slots.add($T.completedFuture($T.<Object>newResult()"
                + ".error($T.clientErrorAt(new $T(message), env, i)).build()))",
                CF, DATA_FETCHER_RESULT, errorRouter, clientException);
            b.endControlFlow();
            if (keyed) {
                b.addStatement("pending.register(env)");
            }
            b.addStatement("return $L(slots)", IN_ORDER_METHOD);
            return MethodSpec.methodBuilder(FETCH_ENTITIES_METHOD)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "$S", "unchecked").build())
                .returns(ParameterizedTypeName.get(CF, listResult()))
                .addParameter(ENV, "env")
                .addJavadoc("The {@code _entities} data fetcher. Answers one slot per representation, in the\n"
                    + "order received. A representation that is not an object with a string\n"
                    + "{@code __typename}, or whose {@code __typename} is not in {@link #$L} (a type this\n"
                    + "schema does not declare, or an entity whose every key is {@code resolvable: false}),\n"
                    + "gets {@code null} and a client error located at its slot\n"
                    + "({@code [\"_entities\", <index>]}); the router only sends a subgraph the types it\n"
                    + "declared resolvable, so either means the supergraph and this subgraph disagree. Every\n"
                    + "other representation is read through {@link #$L}, which answers {@code null} with no\n"
                    + "error for one that names no row; a batch that fails answers {@code null} and an error\n"
                    + "at each of its own slots, and no other.\n",
                    RESOLVABLE_TYPENAMES_FIELD, LOAD_METHOD)
                .addCode(b.build())
                .build();
        }

        /** The row an entity is read as together with the key it was read on, in a keyed build. */
        private TypeName keyedRow() {
            return ParameterizedTypeName.get(DATA_FETCHER_RESULT, RECORD);
        }

        /** What {@code load<Type>} completes with: the row, or the row with its key in a keyed build. */
        private TypeName loaded() {
            return keyed ? keyedRow() : RECORD;
        }

        MethodSpec load(List<EntityKeyQuery.Entity> entities) {
            var b = CodeBlock.builder();
            if (keyed) {
                b.addStatement("return $L(rep, env).thenApply(keyed -> keyed == null ? null : keyed.getData())",
                    LOAD_KEYED_METHOD);
                return MethodSpec.methodBuilder(LOAD_METHOD)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ParameterizedTypeName.get(CF, RECORD))
                    .addParameter(repType(), "rep")
                    .addParameter(ENV, "env")
                    .addJavadoc("Reads one representation's entity, as {@link #$L} does, without the key it was\n"
                        + "read on: {@code Query.node} hands no key down, so a field below the node runs on\n"
                        + "the request's own connection.\n"
                        + "@param rep the representation, {@code __typename} and the key fields\n"
                        + "@param env the dispatching field's environment\n", LOAD_KEYED_METHOD)
                    .addCode(b.build())
                    .build();
            }
            b.addStatement("Object tn = rep == null ? null : rep.get($S)", TYPENAME_COLUMN);
            b.beginControlFlow("if (!(tn instanceof String typeName))");
            b.addStatement("return $T.completedFuture(null)", CF);
            b.endControlFlow();
            for (var entity : entities) {
                b.beginControlFlow("if ($S.equals(typeName))", entity.typeName());
                b.addStatement("return load$L(rep, env)", entity.typeName());
                b.endControlFlow();
            }
            b.addStatement("return $T.completedFuture(null)", CF);
            return MethodSpec.methodBuilder(LOAD_METHOD)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(CF, RECORD))
                .addParameter(repType(), "rep")
                .addParameter(ENV, "env")
                .addJavadoc("Reads one representation's entity through the loader for its type, its\n"
                    + "most-specific key alternative and its connection key, the one way {@code _entities},\n"
                    + "{@code Query.node} and {@code Query.nodes} read an entity. Completes with the row, or\n"
                    + "{@code null} when the representation names no row: no known {@code __typename}, no\n"
                    + "matching alternative, a key value that is not a value of its column, or a connection\n"
                    + "key the request's source refuses.\n"
                    + "@param rep the representation, {@code __typename} and the key fields\n"
                    + "@param env the dispatching field's environment\n")
                .addCode(b.build())
                .build();
        }

        /**
         * {@code loadKeyed(rep, env)}: the row one representation names together with the connection
         * key it was read on, as its {@code localContext}, so a field below a vouched entity runs on
         * the vouched key. In a build that accepts vouchers only.
         */
        MethodSpec loadKeyed(List<EntityKeyQuery.Entity> entities) {
            return MethodSpec.methodBuilder(LOAD_KEYED_METHOD)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(CF, keyedRow()))
                .addParameter(repType(), "rep")
                .addParameter(ENV, "env")
                .addStatement("return $L(rep, env, null)", LOAD_KEYED_METHOD)
                .addJavadoc("Reads one representation's entity as {@link #$L} does, completing with the row\n"
                    + "and, as its {@code localContext}, the connection key it was read on, so the entity's\n"
                    + "subtree reads on that key; {@code null} where the representation names no row. Outside\n"
                    + "{@code _entities} no operation's vouched-for set closes, so a representation carrying a\n"
                    + "voucher fails here, as the generator fault it would be.\n"
                    + "@param rep the representation, {@code __typename} and the key fields\n"
                    + "@param env the dispatching field's environment\n", LOAD_METHOD)
                .build();
        }

        /**
         * {@code loadKeyed(rep, env, pending)}: the dispatch on {@code __typename}, with the
         * {@code _entities} field's pending loads, {@code null} where no set closes.
         */
        MethodSpec loadKeyedPending(List<EntityKeyQuery.Entity> entities) {
            var b = CodeBlock.builder();
            b.addStatement("Object tn = rep == null ? null : rep.get($S)", TYPENAME_COLUMN);
            b.beginControlFlow("if (!(tn instanceof String typeName))");
            b.addStatement("return $T.completedFuture(null)", CF);
            b.endControlFlow();
            for (var entity : entities) {
                b.beginControlFlow("if ($S.equals(typeName))", entity.typeName());
                b.addStatement("return load$L(rep, env, pending)", entity.typeName());
                b.endControlFlow();
            }
            b.addStatement("return $T.completedFuture(null)", CF);
            return MethodSpec.methodBuilder(LOAD_KEYED_METHOD)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(CF, keyedRow()))
                .addParameter(repType(), "rep")
                .addParameter(ENV, "env")
                .addParameter(pendingLoads, "pending")
                .addCode(b.build())
                .build();
        }

        MethodSpec loadType(EntityKeyQuery.Entity entity) {
            var b = CodeBlock.builder();
            var skip = CodeBlock.of("return $T.completedFuture(null)", CF);
            HandleMethodBody.emitAlternativeCascade(b, entity, nodeIdEncoder, skip, (arm, alt, altIndex) -> {
                // A plain key's and a node id's connection key has no parts.
                arm.addStatement("$T key = $T.defaultSource()", connectionKey, connectionKey);
                emitLoad(arm, entity, altIndex);
            }, (arm, voucher) -> emitVoucherArm(arm, entity, voucher));
            b.addStatement("return $T.completedFuture(null)", CF);
            var method = MethodSpec.methodBuilder("load" + entity.typeName())
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(CF, loaded()))
                .addParameter(repType(), "rep")
                .addParameter(ENV, "env");
            if (keyed) {
                method.addParameter(pendingLoads, "pending");
            }
            return method.addCode(b.build()).build();
        }

        /**
         * Loads {@code cols}, with {@code repEnv} and {@code key} in scope, from the loader for (field
         * path, type, alternative {@code altIndex}, {@code key}), whose batch runs on {@code key}'s lease.
         */
        private void emitLoad(CodeBlock.Builder arm, EntityKeyQuery.Entity entity, int altIndex) {
            var loaderType = ParameterizedTypeName.get(DATA_LOADER, listOf(ClassName.get(Object.class)), RECORD);
            var batchLambda = CodeBlock.of("($T keys, $T batchEnv) -> batch$LAlt$L(keys, batchEnv, key)",
                listOf(listOf(ClassName.get(Object.class))), BATCH_LOADER_ENV, entity.typeName(), altIndex);
            arm.add("$T loader = repEnv.getDataLoaderRegistry().computeIfAbsent(\n", loaderType);
            arm.add("    $T.entityLoaderName(repEnv, $S, $L, key),\n", ownedConnections, entity.typeName(), altIndex);
            arm.add("    name -> $L);\n", LoaderConstruction.positional(batchLambda, "repEnv", errorRouter));
            if (keyed) {
                arm.addStatement("return loader.load($T.asList(cols), repEnv)\n"
                    + "    .thenApply(row -> row == null ? null : $T.<$T>newResult().data(row)$L.build())",
                    ARRAYS, DATA_FETCHER_RESULT, RECORD, ConnectionKeyHandDown.ofKey("key").builderTail());
            } else {
                arm.addStatement("return loader.load($T.asList(cols), repEnv)", ARRAYS);
            }
        }

        /**
         * A representation carrying a voucher, {@code rep} and {@code pending} in scope: the codec decodes
         * and checks it, each vouched-for value is coerced once to its column through the column's
         * {@code DataType}, the authored key's columns are filled each from the section that supplies it
         * (a vouched-for key field from its coerced value, every other from the lookup section), and the
         * vouched-for values are admitted into the operation's set. An admitted representation answers a
         * pending load, which {@code fetchEntities} registers under the operation's vouched key once every
         * representation was admitted, in the loader for the authored key the voucher decodes onto. A
         * failed check, or a value no column takes, rejects the request ({@code rejectVoucher}); a value
         * past the cap fails this representation alone; a null voucher is none, and its slot is
         * {@code null}, as a key value that is null matches no row. The per-type data (scope, the fields
         * each section names, their columns) is read off the voucher alternative, its key fields in the
         * order the authored key's SELECT binds them.
         */
        private void emitVoucherArm(CodeBlock.Builder arm, EntityKeyQuery.Entity entity,
                                    EntityKeyQuery.Alternative.Voucher voucher) {
            var authored = entity.alternative(voucher.decodesOnto());
            if (!(authored instanceof EntityKeyQuery.Alternative.Direct lookup)) {
                throw new IllegalStateException("Entity '" + entity.typeName() + "' accepts vouchers decoding onto "
                    + voucher.decodesOnto() + ", which is not a key over its columns");
            }
            if (lookup.fields().size() != voucher.key().size()) {
                throw new IllegalStateException("Entity '" + entity.typeName() + "' accepts vouchers decoding onto "
                    + voucher.decodesOnto() + ", whose " + lookup.fields().size() + " fields the voucher fills "
                    + voucher.key().size() + " of");
            }
            int lookupIndex = entity.indexOf(voucher.decodesOnto());
            var pkg = outputPackage + ".schema";
            var codec = ClassName.get(pkg, no.sikt.graphitron.emit.VoucherRuntime.CODEC);
            var rejected = ClassName.get(pkg, no.sikt.graphitron.emit.VoucherRuntime.REJECTED);
            var reason = rejected.nestedClass("Reason");
            var columnValues = ClassName.get(nodeIdEncoder.packageName(), ColumnValuesClassGenerator.CLASS_NAME);
            var set = ClassName.get("java.util", "Set");
            var vouchedForNames = voucher.vouchedFor().stream().map(f -> CodeBlock.of("$S", f.fieldName()))
                .collect(CodeBlock.joining(", "));
            var lookupNames = voucher.lookupFields().stream().map(name -> CodeBlock.of("$S", name))
                .collect(CodeBlock.joining(", "));
            arm.addStatement("Object presented = rep.get($S)", voucher.voucherField());
            arm.add("// A null voucher is none: the issuing side gives a row it cannot sign none, and a key value\n");
            arm.add("// that is null matches no row, so the slot is null and nothing is checked or acquired.\n");
            arm.beginControlFlow("if (presented == null)");
            arm.addStatement("return $T.completedFuture(null)", CF);
            arm.endControlFlow();
            arm.add("// Only _entities closes an operation's vouched-for set; a representation built from a node id\n");
            arm.add("// carries no voucher, so one reaching here by another path is a generator fault.\n");
            arm.beginControlFlow("if (pending == null)");
            arm.addStatement("throw new $T($S)", IllegalStateException.class, VOUCHER_OUTSIDE_ENTITIES_MESSAGE);
            arm.endControlFlow();
            arm.add("// A voucher is verified, never matched: checked whole, or the request is rejected.\n");
            arm.addStatement("$T token", codec.nestedClass("Token"));
            arm.beginControlFlow("try");
            arm.beginControlFlow("if (!(presented instanceof String voucher))");
            arm.addStatement("throw new $T($T.MALFORMED)", rejected, reason);
            arm.endControlFlow();
            arm.addStatement("token = $T.of(env).voucherVerifier().verify($S, voucher, $S, $T.of($L), $T.of($L))",
                ownedConnections, entity.typeName(), voucher.scope(), set, vouchedForNames, set, lookupNames);
            arm.nextControlFlow("catch ($T failed)", rejected);
            arm.addStatement("return rejectVoucher(env, $S, null, failed)", voucher.scope());
            arm.endControlFlow();
            arm.add("// Each value takes its column's coercion, a representation's own; one no column takes is a\n");
            arm.add("// failed check, not a row that does not exist. A vouched-for value is coerced once, for both\n");
            arm.add("// the connection key and, where it is a key field, the row.\n");
            var parts = new java.util.ArrayList<CodeBlock>();
            for (int i = 0; i < voucher.vouchedFor().size(); i++) {
                var field = voucher.vouchedFor().get(i);
                var column = HandleMethodBody.columnRef(entity, EntityKeys.columnRef(field.column()));
                String local = "vouched" + i;
                var type = no.sikt.graphitron.render.CatalogRefs.decodeBindingType(field.column().bindingType());
                if (type == null) {
                    throw new IllegalStateException("The vouched-for field '" + field.fieldName() + "' of '"
                        + entity.typeName() + "' reads a column whose binding type names no Java type");
                }
                arm.addStatement("$T $L = $T.coerce($L.getDataType(), token.vouchedFor().get($S))",
                    type.isPrimitive() ? type.box() : type, local, columnValues, column, field.fieldName());
                arm.beginControlFlow("if ($L == null)", local);
                arm.addStatement("return rejectVoucher(env, $S, token.vouchId(), new $T($T.VALUE))",
                    voucher.scope(), rejected, reason);
                arm.endControlFlow();
                parts.add(CodeBlock.of("$L", local));
            }
            arm.add("// The authored key's columns, in the order its SELECT binds them, each from the section that\n");
            arm.add("// supplies it: a vouched-for key field finds the row and decides access, the rest navigate.\n");
            arm.addStatement("Object[] cols = new Object[$L]", voucher.key().size());
            for (int i = 0; i < voucher.key().size(); i++) {
                switch (voucher.key().get(i)) {
                    case EntityKeyQuery.KeySource.VouchedFor vouched ->
                        arm.addStatement("cols[$L] = vouched$L", i, vouched.component());
                    case EntityKeyQuery.KeySource.Lookup field -> {
                        arm.addStatement("cols[$L] = $T.coerce($L.getDataType(), token.lookup().get($S))", i,
                            columnValues, HandleMethodBody.columnRef(entity, EntityKeys.columnRef(field.column())),
                            field.fieldName());
                        arm.beginControlFlow("if (cols[$L] == null)", i);
                        arm.addStatement("return rejectVoucher(env, $S, token.vouchId(), new $T($T.VALUE))",
                            voucher.scope(), rejected, reason);
                        arm.endControlFlow();
                    }
                }
            }
            arm.add("// The vouched-for values are admitted into the operation's set here, in representation\n");
            arm.add("// order, and only once the voucher verified: one past the cap fails alone, naming no value,\n");
            arm.add("// and takes no connection.\n");
            arm.beginControlFlow("if (!$T.of(env).admitVouched(new $T($L), env))", ownedConnections,
                ConnectionKeyShape.vouchedForClassName(outputPackage), CodeBlock.join(parts, ", "));
            arm.addStatement("VOUCHER_AUDIT.info($S, token.vouchId(), $S)",
                "Voucher not admitted: vouch id {}, scope {}, reason LIMIT", voucher.scope());
            arm.addStatement("return $T.failedFuture(new $T($S))", CF, clientException,
                no.sikt.graphitron.emit.VoucherRuntime.LIMIT_MESSAGE);
            arm.endControlFlow();
            arm.addStatement("VOUCHER_AUDIT.info($S, token.vouchId(), $S)", "Voucher redeemed: vouch id {}, scope {}",
                voucher.scope());
            arm.addStatement("$T repEnv = $T.newDataFetchingEnvironment(env).arguments(rep).build()", ENV, ENV_IMPL);
            arm.add("// Loaded under the operation's vouched key once the field closes the set: every admitted\n");
            arm.add("// representation of every accepting type shares that key, one loader per type, one lease.\n");
            arm.add("return pending.defer(key -> {\n");
            arm.indent();
            emitLoad(arm, entity, lookupIndex);
            arm.unindent();
            arm.add("});\n");
        }

        /**
         * The dispatch's private holder of one {@code _entities} field's admitted loads, which wait while the
         * field walks its representations: {@code defer} answers a load to register later, and
         * {@code register} closes the operation's vouched-for set, through the carrier, and registers every
         * deferred load under the operation's vouched key, completing each answer from it. A field that
         * deferred nothing registers nothing and leaves the set open.
         */
        TypeSpec pendingLoads() {
            var answer = ParameterizedTypeName.get(CF, keyedRow());
            var load = ParameterizedTypeName.get(ClassName.get("java.util.function", "Function"), connectionKey, answer);
            return TypeSpec.classBuilder(PENDING_LOADS_CLASS_NAME)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("One {@code _entities} field's admitted loads, registered under the operation's vouched\n"
                    + "key once the field has walked every representation, so the vouched-for set is complete\n"
                    + "before the first representation is keyed by it.\n")
                .addField(FieldSpec.builder(listOf(load), "loads", Modifier.PRIVATE, Modifier.FINAL)
                    .initializer("new $T<>()", ARRAY_LIST).build())
                .addField(FieldSpec.builder(listOf(answer), "answers", Modifier.PRIVATE, Modifier.FINAL)
                    .initializer("new $T<>()", ARRAY_LIST).build())
                .addMethod(MethodSpec.methodBuilder("defer")
                    .returns(answer)
                    .addParameter(load, "load")
                    .addStatement("$T answered = new $T<>()", answer, CF)
                    .addStatement("loads.add(load)")
                    .addStatement("answers.add(answered)")
                    .addStatement("return answered")
                    .addJavadoc("Defers {@code load}, a representation's load under a key, to {@link #register}.\n"
                        + "@param load the representation's load under the key it is given\n"
                        + "@return the representation's answer, completed from the load once registered\n")
                    .build())
                .addMethod(MethodSpec.methodBuilder("register")
                    .addParameter(ENV, "env")
                    .beginControlFlow("if (loads.isEmpty())")
                    .addStatement("return")
                    .endControlFlow()
                    .addStatement("$T key = $T.of(env).closeVouched()", connectionKey, ownedConnections)
                    .beginControlFlow("for (int i = 0; i < loads.size(); i++)")
                    .addStatement("$T answered = answers.get(i)", answer)
                    .beginControlFlow("try")
                    .addCode("loads.get(i).apply(key).whenComplete((value, thrown) -> {\n")
                    .addCode("    if (thrown == null) {\n")
                    .addCode("        answered.complete(value);\n")
                    .addCode("    } else {\n")
                    .addCode("        answered.completeExceptionally(thrown);\n")
                    .addCode("    }\n")
                    .addCode("});\n")
                    .nextControlFlow("catch ($T thrown)", RuntimeException.class)
                    .addStatement("answered.completeExceptionally(thrown)")
                    .endControlFlow()
                    .endControlFlow()
                    .addJavadoc("Closes the operation's vouched-for set, where this field admitted a value, and\n"
                        + "registers every deferred load under the operation's vouched key.\n"
                        + "@param env the {@code _entities} field's environment\n")
                    .build())
                .build();
        }

        /**
         * {@code rejectVoucher(env, scope, vouchId, failed)}: logs the rejection to the voucher audit log
         * and rejects the request through the carrier's one slot, answering the representation's slot
         * with the request's rejection, which the error router declines.
         */
        MethodSpec rejectVoucher() {
            var pkg = outputPackage + ".schema";
            var rejected = ClassName.get(pkg, no.sikt.graphitron.emit.VoucherRuntime.REJECTED);
            return MethodSpec.methodBuilder("rejectVoucher")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(CF, loaded()))
                .addParameter(ENV, "env")
                .addParameter(String.class, "scope")
                .addParameter(UUID_CLASS, "vouchId")
                .addParameter(rejected, "failed")
                .addStatement("VOUCHER_AUDIT.info($S, vouchId == null ? $S : vouchId, scope, failed.reason())",
                    "Voucher rejected: vouch id {}, scope {}, reason {}", "unknown")
                .addStatement("return $T.failedFuture($T.of(env).reject(failed))", CF, ownedConnections)
                .addJavadoc("Logs a failed voucher to the audit log, by vouch id (unknown where the token did not\n"
                    + "decode), scope and the check it failed, and rejects the request with it: the carrier\n"
                    + "remembers the first rejection and acquires nothing after it, and the instrumentation\n"
                    + "throws it in place of the result. The slot answers with the request's rejection, which\n"
                    + "the error router declines, so nothing is logged or redacted per slot.\n")
                .build();
        }

        MethodSpec batch(EntityKeyQuery.Entity entity, int altIndex) {
            var listOfObject = listOf(ClassName.get(Object.class));
            var bindingsType = listOf(ArrayTypeName.of(ClassName.get(Object.class)));
            var b = CodeBlock.builder();
            b.beginControlFlow("try");
            b.addStatement("$T contexts = batchEnv.getKeyContextsList()", listOfObject);
            b.addStatement("$T firstEnv = ($T) contexts.get(0)", ENV, ENV);
            b.addStatement("$T[] result = new $T[keys.size()]", RECORD, RECORD);
            b.add("// A key the request's source refuses, now or earlier in the request, answers every slot\n");
            b.add("// null with no error, exactly as an id that names no row; it cannot be used to probe.\n");
            b.addStatement("$T dsl = $T.dslForIfPermitted(firstEnv, key)", DSL_CONTEXT, ownedConnections);
            b.beginControlFlow("if (dsl != null)");
            b.addStatement("$T bindings = new $T<>(keys.size())", bindingsType, ARRAY_LIST);
            b.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
            b.addStatement("bindings.add(new Object[]{i, keys.get(i).toArray(), contexts.get(i)})");
            b.endControlFlow();
            b.addStatement("select$LAlt$L(bindings, firstEnv, dsl, result)", entity.typeName(), altIndex);
            b.endControlFlow();
            b.addStatement("return $T.completedFuture($T.asList(result))", CF, ARRAYS);
            b.nextControlFlow("catch ($T thrown)", Exception.class);
            b.add("// The loader's function may not throw: a throw becomes the batch's failed future, which\n");
            b.add("// fails this batch's slots and no other.\n");
            b.addStatement("return $T.failedFuture(thrown)", CF);
            b.endControlFlow();
            return MethodSpec.methodBuilder("batch" + entity.typeName() + "Alt" + altIndex)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(CF, listOf(RECORD)))
                .addParameter(listOf(listOfObject), "keys")
                .addParameter(BATCH_LOADER_ENV, "batchEnv")
                .addParameter(connectionKey, "key")
                .addCode(b.build())
                .build();
        }

        MethodSpec loadSlot() {
            var method = MethodSpec.methodBuilder(LOAD_SLOT_METHOD)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(CF, slotResult()))
                .addParameter(repType(), "rep")
                .addParameter(ENV, "env")
                .addParameter(int.class, "index")
                .addJavadoc("One slot of a list field reading entities: {@link #$L}, its failure answered at\n"
                    + "the slot, {@code null} plus the error surfaced or redacted at the slot's path, so a\n"
                    + "failed batch takes down only its own slots.\n"
                    + "@param rep the slot's representation\n"
                    + "@param env the list field's environment\n"
                    + "@param index the slot's index in the list\n", LOAD_METHOD);
            if (keyed) {
                // Outside _entities no vouched-for set closes, so this path defers nothing.
                return method.addStatement("return $L(rep, env, index, null)", LOAD_SLOT_METHOD).build();
            }
            return method.addCode(slotBody(CodeBlock.of("$L(rep, env)", LOAD_METHOD))).build();
        }

        /**
         * {@code loadSlot(rep, env, index, pending)}, in a keyed build: the slot read with the
         * {@code _entities} field's pending loads, {@code null} where no set closes.
         */
        MethodSpec loadSlotPending() {
            return MethodSpec.methodBuilder(LOAD_SLOT_METHOD)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(CF, slotResult()))
                .addParameter(repType(), "rep")
                .addParameter(ENV, "env")
                .addParameter(int.class, "index")
                .addParameter(pendingLoads, "pending")
                // The slot answers the row with its key, so each element of the list carries the key it
                // was read on to the entity's subtree.
                .addCode(slotBody(CodeBlock.of("$L(rep, env, pending)", LOAD_KEYED_METHOD)))
                .build();
        }

        /** A slot's body: {@code load}'s answer, its failure answered at the slot. */
        private CodeBlock slotBody(CodeBlock load) {
            var b = CodeBlock.builder();
            b.addStatement("$T<$T> loaded", CF, loaded());
            b.beginControlFlow("try");
            b.addStatement("loaded = $L", load);
            b.nextControlFlow("catch ($T thrown)", Exception.class);
            b.addStatement("loaded = $T.failedFuture(thrown)", CF);
            b.endControlFlow();
            b.addStatement("return loaded.handle((value, thrown) -> thrown == null\n"
                + "    ? $T.<Object>newResult().data(value).build()\n"
                + "    : $T.<Object>surfaceClientErrorOrRedact(thrown, slotEnv(env, index)))",
                DATA_FETCHER_RESULT, errorRouter);
            return b.build();
        }

        MethodSpec inOrder() {
            var slotsType = listOf(ParameterizedTypeName.get(CF, slotResult()));
            var b = CodeBlock.builder();
            b.add("return $T.allOf(slots.toArray(new $T<?>[0])).thenApply(done -> {\n", CF, CF);
            b.indent();
            b.addStatement("$T<Object> data = new $T<>(slots.size())", LIST, ARRAY_LIST);
            b.addStatement("$T<$T> errors = new $T<>()", LIST, GRAPHQL_ERROR, ARRAY_LIST);
            b.beginControlFlow("for ($T<$T> slot : slots)", CF, slotResult());
            b.addStatement("$T answered = slot.join()", slotResult());
            b.addStatement("data.add(answered.getData())");
            b.addStatement("errors.addAll(answered.getErrors())");
            b.endControlFlow();
            b.addStatement("return $T.<$T>newResult().data(data).errors(errors).build()",
                DATA_FETCHER_RESULT, listOf(ClassName.get(Object.class)));
            b.unindent();
            b.add("});\n");
            return MethodSpec.methodBuilder(IN_ORDER_METHOD)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(CF, listResult()))
                .addParameter(slotsType, "slots")
                .addJavadoc("The list field's result from its slots, in order: each slot's value, and every\n"
                    + "slot's errors appended. Every slot answers its own failure, so none fails here.\n"
                    + "@param slots the slots, in list order\n")
                .addCode(b.build())
                .build();
        }

        MethodSpec slotEnv() {
            return MethodSpec.methodBuilder("slotEnv")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(ENV)
                .addParameter(ENV, "env")
                .addParameter(int.class, "index")
                .addStatement("$T step = env.getExecutionStepInfo()", STEP_INFO)
                .addStatement("return $T.newDataFetchingEnvironment(env)"
                    + ".executionStepInfo(step.transform(b -> b.path(step.getPath().segment(index)))).build()",
                    ENV_IMPL)
                .addJavadoc("The list field's environment placed at slot {@code index}, so an error built from it\n"
                    + "sits at that slot's path.\n")
                .build();
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
