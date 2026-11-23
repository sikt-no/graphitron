package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.emit.NodeIdDecodeEmitter;
import no.sikt.graphitron.emit.NodeIdJudgement;
import no.sikt.graphitron.emit.NodeQuery;
import no.sikt.graphitron.render.LoaderConstruction;
import no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code QueryNodeFetcher} class, the runtime dispatcher for the Relay
 * {@code Query.node(id: ID!): Node} and {@code Query.nodes(ids: [ID!]!)} fields.
 *
 * <p>The emitted class lives next to the per-type {@code *Fetchers} classes for discoverability.
 * Every id is first judged by the emitted {@code typenameOf} ({@link NodeIdJudgement}), against the
 * nodes this subgraph serves by id ({@link NodeQuery}): an id whose type prefix does not decode, or
 * whose key does not decode against its served node's key columns, is the client error a
 * {@code @nodeId} argument gives; a well-formed id of any other type answers {@code null}, another
 * subgraph perhaps owning it. A judged id is read as the representation {@code {__typename, id}} the
 * way {@code _entities} reads one (see {@link EntityFetcherDispatchClassGenerator}): in a build
 * without a tenant column through {@code EntityFetcherDispatch.load}, the loader per (type,
 * alternative, connection key), and {@code getNodes} does so per id, each slot answering its own
 * failure. A multi-tenant build dispatches through {@code EntityFetcherDispatch.resolveByReps}
 * instead, {@code nodes} batching the ids per path into {@code rowsNodes}. Every arm SELECTs the
 * requested fields plus a synthetic {@code __typename} column read by the {@code Node}
 * {@code TypeResolver}. A null id, and an id naming no row, answer {@code null}.
 *
 * <p>In {@code node} a malformed id fails the field. In {@code nodes} it fails its own position: that
 * element is {@code null} with the client error at its path, and the other ids resolve, so how far
 * the {@code null} reaches is the schema's element nullability, not this class's.
 *
 * <p>Whether the class is emitted at all is the plan's to say. What it holds is the build's connection
 * key shape, whose tenant part decides the read path, and the nodes served by id the judgement names.
 */
public class QueryNodeFetcherClassGenerator {

    public static final String CLASS_NAME                = "QueryNodeFetcher";
    public static final String DISPATCH_METHOD           = "getNode";
    public static final String DISPATCH_NODES_METHOD     = "getNodes";
    public static final String REGISTER_RESOLVER_METHOD  = "registerTypeResolver";
    /**
     * Synthetic result-set column carrying the participant typename. The {@code __}-wrapping is a
     * deliberate collision-avoidance device (the alias shares the column namespace with
     * consumer-controlled table columns), not the lazy dunder convention banned for Java locals; it
     * reaches generated code as a string literal, so the dunder-identifier meta-test
     * ({@code DunderFreeEmissionPipelineTest}) leaves it alone. The literal's one home is
     * {@link no.sikt.graphitron.command.ReservedAliases#TYPENAME}; the bare {@code "__typename"}
     * strings elsewhere in this generator are federation representation-map keys (the GraphQL
     * introspection namespace), deliberately not this alias.
     */
    public static final String TYPENAME_COLUMN           = no.sikt.graphitron.command.ReservedAliases.TYPENAME;
    public static final String NODE_INTERFACE_NAME       = "Node";

    private static final ClassName ENV                    = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName ENV_IMPL               = ClassName.get("graphql.schema", "DataFetchingEnvironmentImpl");
    private static final ClassName LIST                   = ClassName.get("java.util", "List");
    private static final ClassName MAP                    = ClassName.get("java.util", "Map");
    private static final ClassName RECORD                 = ClassName.get("org.jooq", "Record");
    private static final ClassName DSL_CONTEXT            = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName FIELD                  = ClassName.get("org.jooq", "Field");
    private static final ClassName DSL                    = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName ARRAY_LIST             = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP        = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName ARRAYS                 = ClassName.get("java.util", "Arrays");
    private static final ClassName COMPLETABLE_FUTURE     = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName DATA_LOADER            = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName BATCH_LOADER_ENV       = ClassName.get("org.dataloader", "BatchLoaderEnvironment");
    private static final ClassName CODE_REGISTRY_BLDR     = ClassName.get("graphql.schema", "GraphQLCodeRegistry", "Builder");
    private static final ClassName DATA_FETCHER_RESULT    = ClassName.get("graphql.execution", "DataFetcherResult");

    /**
     * The statement registering the {@code Node} interface's {@code TypeResolver} on a
     * {@code codeRegistry} in scope: it reads the synthetic {@code __typename} column every dispatch
     * arm projects. One spelling for {@link #REGISTER_RESOLVER_METHOD} and for a schema class whose
     * graph serves no node by id, which registers it without this class.
     */
    public static CodeBlock nodeTypeResolverRegistration() {
        return CodeBlock.of("codeRegistry.typeResolver($S, env -> {\n"
            + "    $T record = ($T) env.getObject();\n"
            + "    if (record == null) return null;\n"
            + "    String typeName = (String) record.get($S);\n"
            + "    return typeName == null ? null : env.getSchema().getObjectType(typeName);\n"
            + "})", NODE_INTERFACE_NAME, RECORD, RECORD, TYPENAME_COLUMN);
    }

    /**
     * The {@code QueryNodeFetcher} class. {@code shape}'s tenant part says whether the build routes per
     * tenant, which keeps the synchronous dispatch and makes each resolved node a
     * {@code DataFetcherResult} carrying its tenant; without one, nodes are read through the entity
     * loaders. {@code nodes} are the nodes this subgraph serves by id, which an id is judged against.
     */
    public static List<TypeSpec> generate(String outputPackage, no.sikt.graphitron.emit.ConnectionKeyShape shape,
                                          List<NodeQuery.Node> nodes) {
        var judgement = judgement(outputPackage, nodes);
        if (!shape.hasTenant()) {
            return List.of(untenanted(outputPackage, judgement));
        }

        var fieldWildcard    = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var arrayListOfField = ParameterizedTypeName.get(ARRAY_LIST, fieldWildcard);
        var graphitronContext = ClassName.get(outputPackage + ".schema", "GraphitronContext");

        var entityDispatch = ClassName.get(outputPackage + ".util",
            EntityFetcherDispatchClassGenerator.CLASS_NAME);

        // A multi-tenant build's resolveByReps slots are DataFetcherResult<Record> envelopes, each
        // carrying its row's tenant when its group was routed per tenant. node hands the envelope
        // back as its field value and nodes keeps each one as a list element, so neither wraps a
        // slot again (graphql-java unwraps one level per value).
        TypeName nodeResult = ParameterizedTypeName.get(DATA_FETCHER_RESULT, RECORD);
        TypeName rowType = ClassName.get(Object.class);
        CodeBlock noNode = CodeBlock.of("$T.<$T>newResult().build()", DATA_FETCHER_RESULT, RECORD);

        // fetchById: judge the id, synthesise a single rep from it, dispatch through the entity
        // dispatcher, return the resolved slot (or no node when the id names no served node, no
        // row, or a tenant outside the request's set). A malformed id throws the client error out
        // of getNode. The dispatcher is the single SELECT path for Query.node, Query.nodes, and
        // federation _entities.
        var fetchById = MethodSpec.methodBuilder("fetchById")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(nodeResult)
            .addParameter(ENV, "env")
            .addParameter(String.class, "id")
            .addStatement("$T typename = $L(id)", String.class, NodeIdJudgement.METHOD)
            .addStatement("if (typename == null) return $L", noNode)
            .addStatement("Object[] result = $T.$L($T.of($T.of($S, typename, $S, id)), env)",
                entityDispatch, EntityFetcherDispatchClassGenerator.RESOLVE_BY_REPS_METHOD,
                LIST, MAP, "__typename", "id");
        fetchById
            .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked").build())
            .addComment("Every non-null slot of a multi-tenant resolveByReps is a DataFetcherResult<Record>.")
            .addStatement("return result[0] == null ? $L : ($T) result[0]", noNode, nodeResult);

        var dispatch = MethodSpec.methodBuilder(DISPATCH_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(nodeResult)
            .addParameter(ENV, "env")
            .addJavadoc("Dispatches a Relay {@code Query.node(id:)} call to the matching\n"
                + "{@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType NodeType} table.\n"
                + "No node for a null id, an id of a type this subgraph does not serve by id, or one naming\n"
                + "no row; throws the client error for a malformed id.\n"
                + "In a multi-tenant build the node arrives as the field's {@code DataFetcherResult},\n"
                + "carrying the tenant its id names down to its subtree; no node is an empty one.\n")
            .addStatement("$T id = env.getArgument($S)", String.class, "id")
            .addStatement("if (id == null) return $L", noNode)
            .addStatement("return fetchById(env, id)")
            .build();

        var STRING_CLASS    = ClassName.get(String.class);
        var INTEGER_CLASS   = ClassName.get(Integer.class);
        var listOfString    = ParameterizedTypeName.get(LIST, STRING_CLASS);
        var listOfRecord    = ParameterizedTypeName.get(LIST, rowType);
        var listOfInteger   = ParameterizedTypeName.get(LIST, INTEGER_CLASS);
        var cfRecord        = ParameterizedTypeName.get(COMPLETABLE_FUTURE, rowType);
        var cfListRecord    = ParameterizedTypeName.get(COMPLETABLE_FUTURE, listOfRecord);
        var listOfCfRecord  = ParameterizedTypeName.get(LIST, cfRecord);
        var repType         = ParameterizedTypeName.get(MAP, STRING_CLASS, ClassName.get(Object.class));
        var listOfRep       = ParameterizedTypeName.get(LIST, repType);
        var loaderType      = ParameterizedTypeName.get(DATA_LOADER, repType, rowType);
        var mapStrListInt   = ParameterizedTypeName.get(MAP, STRING_CLASS, listOfInteger);
        var mapStrListStr   = ParameterizedTypeName.get(MAP, STRING_CLASS, listOfString);

        // Per-id fan-out into per-path DataLoaders. Each id is judged first, by its index: a
        // malformed one completes at once with the client error at its position, and one of a type
        // this subgraph does not serve completes null, so only judged representations
        // {__typename, id} reach the loader and nothing in its batch function refuses one. The
        // loader name is the path expression computed from the per-id DFE's execution-step-info;
        // each id gets a per-id DFE so the dispatched alternative sees arguments rebound to
        // {id: <thisId>}. Ids that share the same path land in one loader and batch into one hasIds
        // query.
        //
        // Assumes the DataLoaderRegistry is request-scoped (the standard graphql-java pattern,
        // one registry per ExecutionInput). A registry shared across requests would let loaders
        // and their first-key contexts survive between calls; that is an app-level misconfig,
        // not a generator concern.
        var dispatchNodes = MethodSpec.methodBuilder(DISPATCH_NODES_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(cfListRecord)
            .addParameter(ENV, "env")
            .addJavadoc("Dispatches a Relay {@code Query.nodes(ids:)} call via per-path DataLoaders\n"
                + "keyed by {@code path} (computed from the per-id DFE's execution-step-info).\n"
                + "Ids batch into a single {@link #rowsNodes} call, which synthesises\n"
                + "representations and dispatches through {@code EntityFetcherDispatch.resolveByReps}\n"
                + "(one SELECT per {@code (type, alternative)} group via the shared dispatcher;\n"
                + "a multi-tenant build additionally partitions each group per decoded tenant, so a\n"
                + "batch of ids spanning tenants issues one SELECT per tenant-homogeneous group).\n"
                + "Each id is judged before it is loaded: a malformed id's element is {@code null} with\n"
                + "the client error at its own path and never reaches the loader. An element is\n"
                + "{@code null} with no error for a null id, an id of a type this subgraph does not\n"
                + "serve by id, or one naming no row.\n")
            .addStatement("$T ids = env.getArgument($S)", listOfString, "ids")
            .addStatement("if (ids == null || ids.isEmpty()) return $T.completedFuture($T.of())", COMPLETABLE_FUTURE, LIST)
            // Multi-tenant builds read the name off the carrier's single naming seam. No tenant
            // segment here: ids are undecoded at the loader boundary, and the dispatcher
            // partitions the batch per decoded tenant downstream, so the batch itself may span
            // tenants safely.
            .addStatement("$T path = $T.loaderName(env, $S)",
                String.class,
                ClassName.get(outputPackage + ".schema",
                    ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME), "Query")
            .addStatement("$T futures = new $T<>(ids.size())", listOfCfRecord, ARRAY_LIST)
            .beginControlFlow("for (int i = 0; i < ids.size(); i++)")
            .addStatement("$T id = ids.get(i)", String.class)
            .beginControlFlow("if (id == null)")
            .addStatement("futures.add($T.<$T>completedFuture(null))", COMPLETABLE_FUTURE, rowType)
            .addStatement("continue")
            .endControlFlow()
            .addStatement("$T typename", String.class)
            .beginControlFlow("try")
            .addStatement("typename = $L(id)", NodeIdJudgement.METHOD)
            .nextControlFlow("catch ($T malformed)", clientException(outputPackage))
            .addStatement("futures.add($T.<$T>completedFuture($T.<$T>newResult()"
                + ".error($T.clientErrorAt(malformed, env, i)).build()))", COMPLETABLE_FUTURE, rowType,
                DATA_FETCHER_RESULT, RECORD, ErrorRouterClassGenerator.routerClass(outputPackage))
            .addStatement("continue")
            .endControlFlow()
            .beginControlFlow("if (typename == null)")
            .addStatement("futures.add($T.<$T>completedFuture(null))", COMPLETABLE_FUTURE, rowType)
            .addStatement("continue")
            .endControlFlow()
            .addStatement("$T idEnv = $T.newDataFetchingEnvironment(env).arguments($T.of($S, id)).build()",
                ENV, ENV_IMPL, MAP, "id")
            .addCode("$T loader = idEnv.getDataLoaderRegistry().computeIfAbsent(path, k ->\n", loaderType)
            .addCode("    $L);\n", LoaderConstruction.positional(
                CodeBlock.builder()
                    .add("($T keys, $T batchEnv) -> {\n", listOfRep, BATCH_LOADER_ENV)
                    .add("                $T dfe = ($T) batchEnv.getKeyContextsList().get(0);\n", ENV, ENV)
                    .add("                return $T.completedFuture(rowsNodes(keys, dfe));\n", COMPLETABLE_FUTURE)
                    .add("            }")
                    .build(),
                "idEnv",
                ErrorRouterClassGenerator.routerClass(outputPackage)))
            .addStatement("futures.add(loader.load($T.of($S, typename, $S, id), idEnv))", MAP, "__typename", "id")
            .endControlFlow()
            .addCode("return $T.allOf(futures.toArray(new $T<?>[0]))\n", COMPLETABLE_FUTURE, COMPLETABLE_FUTURE)
            .addCode("    .thenApply(v -> futures.stream().map($T::join).toList());\n", COMPLETABLE_FUTURE)
            .build();

        // rowsNodes: the batch of judged representations {__typename, id}, dispatched through
        // EntityFetcherDispatch.resolveByReps. The dispatcher groups per (type, alternative),
        // widened per decoded tenant in a multi-tenant build, issues one SELECT per group, and
        // scatters rows back to their original positions in keys via idx. Non-canonical id inputs
        // need no re-encode round-trip: Base64.getUrlDecoder accepts both padded and unpadded forms.
        var rowsNodes = MethodSpec.methodBuilder("rowsNodes")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(listOfRep, "keys")
            .addParameter(ENV, "env")
            .addStatement("Object[] result = $T.$L(keys, env)", entityDispatch,
                EntityFetcherDispatchClassGenerator.RESOLVE_BY_REPS_METHOD)
            .addStatement("$T<$T> out = new $T<>(result.length)", LIST, rowType, ARRAY_LIST)
            .beginControlFlow("for (Object o : result)")
                // Each element keeps its slot's envelope, so it hands its own tenant down.
                .addStatement("out.add(o)")
            .endControlFlow()
            .addStatement("return out");

        var contextHelper = MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(graphitronContext)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($T.class)", graphitronContext)
            .build();

        var registerResolver = registerResolver();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Runtime dispatcher for the Relay {@code Query.node(id:)} and\n"
                + "{@code Query.nodes(ids:)} fields. See\n"
                + "{@link QueryNodeFetcherClassGenerator} for emission semantics.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(dispatch)
            .addMethod(dispatchNodes)
            .addMethod(registerResolver)
            .addMethod(fetchById.build())
            .addMethod(rowsNodes.build())
            .addMethod(judgement)
            .addMethod(contextHelper)
            .build();
        return List.of(spec);
    }
    /**
     * Type resolver for the Relay Node interface. Reads the synthetic {@code __typename} column
     * (projected by every dispatcher select arm) and routes the result to the matching concrete
     * {@code GraphQLObjectType}. Registered on {@code GraphQLCodeRegistry} as
     * {@code .typeResolver("Node", ...)} by {@code GraphitronSchema.build()}.
     */
    private static MethodSpec registerResolver() {
        return MethodSpec.methodBuilder(REGISTER_RESOLVER_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(CODE_REGISTRY_BLDR, "codeRegistry")
            .addJavadoc("Registers the {@code Node} interface's {@code TypeResolver} on the\n"
                + "shared {@code GraphQLCodeRegistry.Builder}. Reads the synthetic\n"
                + "{@code __typename} column projected by {@link #" + DISPATCH_METHOD + "}.\n")
            .addStatement(nodeTypeResolverRegistration())
            .build();
    }

    /**
     * The class in a build without a tenant column: {@code node} and {@code nodes} build the
     * representation {@code {__typename, id}} from each id and read it through
     * {@code EntityFetcherDispatch.load}, the loader per (type, alternative, connection key) that
     * {@code _entities} reads through, so an entity is read one way.
     */
    private static TypeSpec untenanted(String outputPackage, MethodSpec judgement) {
        var entityDispatch = ClassName.get(outputPackage + ".util", EntityFetcherDispatchClassGenerator.CLASS_NAME);
        var stringClass = ClassName.get(String.class);
        var objectClass = ClassName.get(Object.class);
        var repType = ParameterizedTypeName.get(MAP, stringClass, objectClass);
        var listOfObject = ParameterizedTypeName.get(LIST, objectClass);
        var slotResult = ParameterizedTypeName.get(DATA_FETCHER_RESULT, objectClass);
        var listResult = ParameterizedTypeName.get(DATA_FETCHER_RESULT, listOfObject);

        var representation = MethodSpec.methodBuilder("representationOf")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(repType)
            .addParameter(String.class, "id")
            .addStatement("if (id == null) return null")
            .addStatement("$T typename = $L(id)", String.class, NodeIdJudgement.METHOD)
            .addStatement("if (typename == null) return null")
            .addStatement("return $T.<String, Object>of($S, typename, $S, id)", MAP, "__typename", "id")
            .addJavadoc("The representation a Relay id names, {@code {__typename, id}}, or {@code null} for a\n"
                + "null id or one of a type this subgraph does not serve by id. Throws the client error for\n"
                + "a malformed id. Non-canonical id inputs need no re-encode round-trip:\n"
                + "{@code Base64.getUrlDecoder} accepts both padded and unpadded forms.\n")
            .build();

        var dispatch = MethodSpec.methodBuilder(DISPATCH_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(COMPLETABLE_FUTURE, RECORD))
            .addParameter(ENV, "env")
            .addJavadoc("Dispatches a Relay {@code Query.node(id:)} call to the matching\n"
                + "{@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType NodeType} table, through\n"
                + "the entity loader {@code _entities} reads the same type through.\n"
                + "Completes with {@code null} for a null id, an id of a type this subgraph does not serve\n"
                + "by id, or one naming no row, and fails with the client error for a malformed id.\n")
            .beginControlFlow("try")
            .addStatement("$T rep = representationOf(env.getArgument($S))", repType, "id")
            .addStatement("if (rep == null) return $T.completedFuture(null)", COMPLETABLE_FUTURE)
            .addStatement("return $T.$L(rep, env)", entityDispatch, EntityFetcherDispatchClassGenerator.LOAD_METHOD)
            .nextControlFlow("catch ($T thrown)", Exception.class)
            .addStatement("return $T.failedFuture(thrown)", COMPLETABLE_FUTURE)
            .endControlFlow()
            .build();

        var dispatchNodes = MethodSpec.methodBuilder(DISPATCH_NODES_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(COMPLETABLE_FUTURE, listResult))
            .addParameter(ENV, "env")
            .addJavadoc("Dispatches a Relay {@code Query.nodes(ids:)} call: each id is read through the entity\n"
                + "loader for its type, so ids batch per (type, alternative) into one SELECT each, under\n"
                + "the request's batch size limit. Answers in id order; an element is {@code null} for a\n"
                + "null id, an id of a type this subgraph does not serve by id, or one naming no row, and\n"
                + "{@code null} with an error at its own path for a malformed id or when its batch failed.\n")
            .addStatement("$T<$T> ids = env.getArgument($S)", LIST, stringClass, "ids")
            .beginControlFlow("if (ids == null || ids.isEmpty())")
            .addStatement("return $T.completedFuture($T.<$T>newResult().data($T.of()).build())",
                COMPLETABLE_FUTURE, DATA_FETCHER_RESULT, listOfObject, LIST)
            .endControlFlow()
            .addStatement("$T<$T<$T>> slots = new $T<>(ids.size())", LIST, COMPLETABLE_FUTURE, slotResult, ARRAY_LIST)
            .beginControlFlow("for (int i = 0; i < ids.size(); i++)")
            .addStatement("$T rep", repType)
            .beginControlFlow("try")
            .addStatement("rep = representationOf(ids.get(i))")
            .nextControlFlow("catch ($T malformed)", clientException(outputPackage))
            .addStatement("slots.add($T.completedFuture($T.<Object>newResult()"
                + ".error($T.clientErrorAt(malformed, env, i)).build()))", COMPLETABLE_FUTURE, DATA_FETCHER_RESULT,
                ErrorRouterClassGenerator.routerClass(outputPackage))
            .addStatement("continue")
            .endControlFlow()
            .addStatement("slots.add(rep == null\n"
                + "    ? $T.completedFuture($T.<Object>newResult().build())\n"
                + "    : $T.$L(rep, env, i))", COMPLETABLE_FUTURE, DATA_FETCHER_RESULT, entityDispatch,
                EntityFetcherDispatchClassGenerator.LOAD_SLOT_METHOD)
            .endControlFlow()
            .addStatement("return $T.$L(slots)", entityDispatch, EntityFetcherDispatchClassGenerator.IN_ORDER_METHOD)
            .build();

        return TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Runtime dispatcher for the Relay {@code Query.node(id:)} and\n"
                + "{@code Query.nodes(ids:)} fields. See\n"
                + "{@link QueryNodeFetcherClassGenerator} for emission semantics.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(dispatch)
            .addMethod(dispatchNodes)
            .addMethod(registerResolver())
            .addMethod(representation)
            .addMethod(judgement)
            .build();
    }

    /** The emitted judgement of an id, against the nodes this subgraph serves by id. */
    private static MethodSpec judgement(String outputPackage, List<NodeQuery.Node> nodes) {
        return NodeIdJudgement.typenameOf(nodes, new NodeIdDecodeEmitter.Runtime(
            ClassName.get(outputPackage + ".util", NodeIdEncoderClassGenerator.CLASS_NAME), outputPackage));
    }

    /** The generated client error a malformed id is refused with. */
    private static ClassName clientException(String outputPackage) {
        var unit = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage).clientException();
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
