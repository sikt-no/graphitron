package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;

import javax.lang.model.element.Modifier;
import java.util.Comparator;
import java.util.List;

/**
 * Generates the {@code QueryNodeFetcher} class — the runtime dispatcher for the Relay
 * {@code Query.node(id: ID!): Node} field.
 *
 * <p>The emitted class lives next to the per-type {@code *Fetchers} classes for discoverability.
 * Its single static {@code getNode(env)} method:
 * <ol>
 *   <li>Reads the {@code id} argument from the {@link graphql.schema.DataFetchingEnvironment}.
 *       Null arguments and base64 decoding errors return {@code null} per the Relay
 *       "if no such object exists, the field returns null" contract — opacity says we never
 *       leak decoding errors back to the client.</li>
 *   <li>Extracts the {@code typeId} prefix via {@code NodeIdEncoder.peekTypeId(id)} (the
 *       generated decode helper next to {@link NodeIdEncoderClassGenerator}).</li>
 *   <li>Branches on the prefix. Each arm SELECTs the requested fields (via the type's
 *       generated {@code TypeClass.$fields(...)}) plus a synthetic {@code __typename} column
 *       — which the {@code Node} {@code TypeResolver} reads to route the result to the
 *       concrete GraphQL type. Filters via {@code NodeIdEncoder.hasId}.</li>
 *   <li>Unknown {@code typeId} returns {@code null}.</li>
 * </ol>
 *
 * <p>Emitted only when at least one {@link NodeType} is classified for the schema.
 *
 * <p>{@code Query.node(id:)} dispatch shipped under {@code @nodeId} + {@code @node} directive
 * support; see {@code graphitron-rewrite/roadmap/changelog.md}.
 */
public class QueryNodeFetcherClassGenerator {

    public static final String CLASS_NAME                = "QueryNodeFetcher";
    public static final String DISPATCH_METHOD           = "getNode";
    public static final String DISPATCH_NODES_METHOD     = "getNodes";
    public static final String REGISTER_RESOLVER_METHOD  = "registerTypeResolver";
    public static final String TYPENAME_COLUMN           = "__typename";
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
    private static final ClassName DATA_LOADER_FACTORY    = ClassName.get("org.dataloader", "DataLoaderFactory");
    private static final ClassName BATCH_LOADER_ENV       = ClassName.get("org.dataloader", "BatchLoaderEnvironment");
    private static final ClassName CODE_REGISTRY_BLDR     = ClassName.get("graphql.schema", "GraphQLCodeRegistry", "Builder");

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage, String jooqPackage) {
        var nodeTypes = schema.types().values().stream()
            .filter(t -> t instanceof NodeType)
            .map(t -> (NodeType) t)
            .sorted(Comparator.comparing(NodeType::typeId))
            .toList();
        if (nodeTypes.isEmpty()) {
            return List.of();
        }

        var fieldWildcard    = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var arrayListOfField = ParameterizedTypeName.get(ARRAY_LIST, fieldWildcard);
        var graphitronContext = ClassName.get(outputPackage + ".schema", "GraphitronContext");
        var nodeIdEncoder    = ClassName.get(outputPackage + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);

        var entityDispatch = ClassName.get(outputPackage + ".util",
            EntityFetcherDispatchClassGenerator.CLASS_NAME);

        // fetchById: synthesise a single rep from id, dispatch through the entity dispatcher,
        // return the resolved Record (or null when typeId/decode/SELECT yields nothing).
        // The dispatcher is now the single SELECT path for Query.node, Query.nodes, and
        // federation _entities.
        var fetchById = MethodSpec.methodBuilder("fetchById")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(RECORD)
            .addParameter(ENV, "env")
            .addParameter(String.class, "id")
            .addStatement("$T typeId = $T.peekTypeId(id)", String.class, nodeIdEncoder)
            .addStatement("if (typeId == null) return null")
            .addStatement("$T typename = $T.$L(typeId)", String.class, entityDispatch,
                EntityFetcherDispatchClassGenerator.TYPENAME_FOR_TYPE_ID_METHOD)
            .addStatement("if (typename == null) return null")
            .addStatement("Object[] result = $T.$L($T.of($T.of($S, typename, $S, id)), env)",
                entityDispatch, EntityFetcherDispatchClassGenerator.RESOLVE_BY_REPS_METHOD,
                LIST, MAP, "__typename", "id")
            .addStatement("return ($T) result[0]", RECORD);

        var dispatch = MethodSpec.methodBuilder(DISPATCH_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(RECORD)
            .addParameter(ENV, "env")
            .addJavadoc("Dispatches a Relay {@code Query.node(id:)} call to the matching\n"
                + "{@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType NodeType} table.\n"
                + "Returns {@code null} for null/garbage/unknown IDs (Relay spec).\n")
            .addStatement("$T id = env.getArgument($S)", String.class, "id")
            .addStatement("if (id == null) return null")
            .addStatement("return fetchById(env, id)")
            .build();

        var STRING_CLASS    = ClassName.get(String.class);
        var INTEGER_CLASS   = ClassName.get(Integer.class);
        var listOfString    = ParameterizedTypeName.get(LIST, STRING_CLASS);
        var listOfRecord    = ParameterizedTypeName.get(LIST, RECORD);
        var listOfInteger   = ParameterizedTypeName.get(LIST, INTEGER_CLASS);
        var cfRecord        = ParameterizedTypeName.get(COMPLETABLE_FUTURE, RECORD);
        var cfListRecord    = ParameterizedTypeName.get(COMPLETABLE_FUTURE, listOfRecord);
        var listOfCfRecord  = ParameterizedTypeName.get(LIST, cfRecord);
        var loaderType      = ParameterizedTypeName.get(DATA_LOADER, STRING_CLASS, RECORD);
        var mapStrListInt   = ParameterizedTypeName.get(MAP, STRING_CLASS, listOfInteger);
        var mapStrListStr   = ParameterizedTypeName.get(MAP, STRING_CLASS, listOfString);

        // Per-id fan-out into tenant-scoped DataLoaders. The loader name is built per id from
        // a per-id DFE (arguments rebound to {id: <thisId>}) so getTenantId resolves against
        // the individual id rather than the outer ids[]. Ids that resolve to the same tenant
        // share a loader and batch into one hasIds query; ids from different tenants land in
        // separate loaders. This also makes batchEnv.getKeyContextsList().get(0) safe inside
        // the batch lambda: every key in a given loader shares a tenant by construction.
        //
        // Assumes the DataLoaderRegistry is request-scoped (the standard graphql-java pattern,
        // one registry per ExecutionInput). A registry shared across requests would let loaders
        // and their first-key contexts survive between calls — that's an app-level misconfig,
        // not a generator concern, but it would break the tenant-scoping invariant.
        var dispatchNodes = MethodSpec.methodBuilder(DISPATCH_NODES_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(cfListRecord)
            .addParameter(ENV, "env")
            .addJavadoc("Dispatches a Relay {@code Query.nodes(ids:)} call via per-tenant DataLoaders\n"
                + "keyed by {@code getTenantId(idEnv) + path}, where {@code idEnv} is a per-id DFE.\n"
                + "Ids resolving to the same tenant share a loader and batch into a single\n"
                + "{@link #rowsNodes} call, which synthesises representations and dispatches\n"
                + "through {@code EntityFetcherDispatch.resolveByReps} (one SELECT per\n"
                + "{@code (type, alternative, tenantId)} group via the shared dispatcher).\n"
                + "Ids from different tenants get separate loaders. Returns {@code null}\n"
                + "entries for null/garbage/unknown IDs (Relay spec).\n")
            .addStatement("$T ids = env.getArgument($S)", listOfString, "ids")
            .addStatement("if (ids == null || ids.isEmpty()) return $T.completedFuture($T.of())", COMPLETABLE_FUTURE, LIST)
            .addStatement("$T path = $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
                String.class, String.class, "/")
            .addCode("$T futures = ids.stream()\n", listOfCfRecord)
            .addCode("    .map(id -> {\n")
            .addCode("        if (id == null) return $T.completedFuture(($T) null);\n", COMPLETABLE_FUTURE, RECORD)
            .addCode("        $T idEnv = $T.newDataFetchingEnvironment(env).arguments($T.of($S, id)).build();\n",
                ENV, ENV_IMPL, MAP, "id")
            .addCode("        $T name = graphitronContext(idEnv).getTenantId(idEnv) + $S + path;\n",
                String.class, "/")
            .addCode("        $T loader = idEnv.getDataLoaderRegistry().computeIfAbsent(name, k ->\n", loaderType)
            .addCode("            $T.newDataLoader(($T keys, $T batchEnv) -> {\n",
                DATA_LOADER_FACTORY, listOfString, BATCH_LOADER_ENV)
            .addCode("                $T dfe = ($T) batchEnv.getKeyContextsList().get(0);\n", ENV, ENV)
            .addCode("                return $T.completedFuture(rowsNodes(keys, dfe));\n", COMPLETABLE_FUTURE)
            .addCode("            }));\n")
            .addCode("        return loader.load(id, idEnv);\n")
            .addCode("    })\n")
            .addCode("    .toList();\n")
            .addCode("return $T.allOf(futures.toArray(new $T[0]))\n", COMPLETABLE_FUTURE, COMPLETABLE_FUTURE)
            .addCode("    .thenApply(v -> futures.stream().map($T::join).toList());\n", COMPLETABLE_FUTURE)
            .build();

        // rowsNodes: synthesise a representation per non-null id (peek typeId → recover
        // GraphQL typename via the dispatcher's emitted lookup → build a {__typename, id}
        // map), then dispatch through EntityFetcherDispatch.resolveByReps. The dispatcher's
        // own (type, alternative, tenantId) grouping issues one SELECT per group, and idx
        // carried through the SELECT scatters rows back to their original positions in
        // keys. Replaces the previous per-typeId loop and its encode-canonicalize-scatter
        // round-trip; the dispatcher's idx-driven scatter handles non-canonical inputs
        // directly because Base64.getUrlDecoder accepts both padded and unpadded forms.
        var listOfMap = ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(MAP, STRING_CLASS, ClassName.get(Object.class)));
        var arrayListOfMap = ParameterizedTypeName.get(ARRAY_LIST,
            ParameterizedTypeName.get(MAP, STRING_CLASS, ClassName.get(Object.class)));
        var rowsNodes = MethodSpec.methodBuilder("rowsNodes")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(listOfString, "keys")
            .addParameter(ENV, "env")
            .addStatement("$T reps = new $T(keys.size())", listOfMap, arrayListOfMap)
            .beginControlFlow("for ($T id : keys)", STRING_CLASS)
                .addStatement("if (id == null) { reps.add(null); continue; }")
                .addStatement("$T typeId = $T.peekTypeId(id)", STRING_CLASS, nodeIdEncoder)
                .addStatement("if (typeId == null) { reps.add(null); continue; }")
                .addStatement("$T typename = $T.$L(typeId)", STRING_CLASS, entityDispatch,
                    EntityFetcherDispatchClassGenerator.TYPENAME_FOR_TYPE_ID_METHOD)
                .addStatement("if (typename == null) { reps.add(null); continue; }")
                .addStatement("reps.add($T.of($S, typename, $S, id))", MAP, "__typename", "id")
            .endControlFlow()
            .addStatement("Object[] result = $T.$L(reps, env)", entityDispatch,
                EntityFetcherDispatchClassGenerator.RESOLVE_BY_REPS_METHOD)
            .addStatement("$T<$T> out = new $T<>(result.length)", LIST, RECORD, ARRAY_LIST)
            .beginControlFlow("for (Object o : result)")
                .addStatement("out.add(($T) o)", RECORD)
            .endControlFlow()
            .addStatement("return out");

        var contextHelper = MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(graphitronContext)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($T.class)", graphitronContext)
            .build();

        // Type resolver for the Relay Node interface. Reads the synthetic __typename column
        // (projected by every dispatch arm above) and routes the result to the matching
        // concrete GraphQLObjectType. Registered on GraphQLCodeRegistry as
        // .typeResolver("Node", ...) by GraphitronSchema.build().
        var registerResolver = MethodSpec.methodBuilder(REGISTER_RESOLVER_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(CODE_REGISTRY_BLDR, "codeRegistry")
            .addJavadoc("Registers the {@code Node} interface's {@code TypeResolver} on the\n"
                + "shared {@code GraphQLCodeRegistry.Builder}. Reads the synthetic\n"
                + "{@code __typename} column projected by {@link #" + DISPATCH_METHOD + "}.\n")
            .addStatement("codeRegistry.typeResolver($S, env -> {\n"
                + "    $T record = ($T) env.getObject();\n"
                + "    if (record == null) return null;\n"
                + "    String typeName = (String) record.get($S);\n"
                + "    return typeName == null ? null : env.getSchema().getObjectType(typeName);\n"
                + "})", NODE_INTERFACE_NAME, RECORD, RECORD, TYPENAME_COLUMN)
            .build();

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
            .addMethod(contextHelper)
            .build();
        return List.of(spec);
    }
}
