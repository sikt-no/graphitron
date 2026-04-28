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

        // fetchById: the type-dispatch logic shared by getNode and getNodes.
        // Takes the per-ID DataFetchingEnvironment so getDslContext can determine the correct
        // tenant from the individual ID. Callers guarantee id is non-null.
        var fetchById = MethodSpec.methodBuilder("fetchById")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(RECORD)
            .addParameter(ENV, "env")
            .addParameter(String.class, "id")
            .addStatement("$T typeId = $T.peekTypeId(id)", String.class, nodeIdEncoder)
            .addStatement("if (typeId == null) return null")
            .addStatement("$T dsl = graphitronContext(env).getDslContext(env)", DSL_CONTEXT);

        // Emitted as an if-else chain rather than a switch statement: JavaPoet doesn't reliably
        // handle arrow-case blocks containing return-with-block, and the readability cost of an
        // if chain over N NodeTypes is small compared to the structural-coverage cost.
        for (var nt : nodeTypes) {
            var jooqTableClass = ClassName.get(jooqPackage + ".tables", nt.table().javaClassName());
            var typeClass      = ClassName.get(outputPackage + ".types", nt.name());
            String tableSingleton = nt.table().javaFieldName();
            fetchById.beginControlFlow("if ($S.equals(typeId))", nt.typeId());
            fetchById.addStatement("$T t = $T.$L", jooqTableClass, jooqTableClass, tableSingleton);
            fetchById.addStatement("$T fields = new $T($T.$$fields(env.getSelectionSet(), t, env))",
                arrayListOfField, arrayListOfField, typeClass);
            fetchById.addStatement("fields.add($T.inline($S).as($S))", DSL, nt.name(), TYPENAME_COLUMN);
            var hasIdArgs = CodeBlock.builder().add("$S, id", nt.typeId());
            for (var col : nt.nodeKeyColumns()) {
                hasIdArgs.add(", t.$L", col.javaName());
            }
            fetchById.addStatement("return dsl.select(fields).from(t).where($T.hasId($L)).fetchOne()",
                nodeIdEncoder, hasIdArgs.build());
            fetchById.endControlFlow();
        }
        fetchById.addStatement("return null");

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

        var dispatchNodes = MethodSpec.methodBuilder(DISPATCH_NODES_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(cfListRecord)
            .addParameter(ENV, "env")
            .addJavadoc("Dispatches a Relay {@code Query.nodes(ids:)} call via a DataLoader keyed\n"
                + "by {@code getTenantId + path}. The batch function calls {@link #rowsNodes},\n"
                + "which groups IDs by typeId and executes one query per type.\n"
                + "Returns {@code null} entries for null/garbage/unknown IDs (Relay spec).\n")
            .addStatement("$T ids = env.getArgument($S)", listOfString, "ids")
            .addStatement("if (ids == null || ids.isEmpty()) return $T.completedFuture($T.of())", COMPLETABLE_FUTURE, LIST)
            .addStatement("$T name = graphitronContext(env).getTenantId(env) + $S + $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
                String.class, "/", String.class, "/")
            .addCode("$T loader = env.getDataLoaderRegistry().computeIfAbsent(name, k ->\n", loaderType)
            .addCode("    $T.newDataLoader(($T keys, $T batchEnv) -> {\n", DATA_LOADER_FACTORY, listOfString, BATCH_LOADER_ENV)
            .addCode("        $T dfe = ($T) batchEnv.getKeyContextsList().get(0);\n", ENV, ENV)
            .addCode("        return $T.completedFuture(rowsNodes(keys, dfe));\n", COMPLETABLE_FUTURE)
            .addCode("    }));\n")
            .addCode("$T futures = ids.stream()\n", listOfCfRecord)
            .addCode("    .map(id -> id == null ? $T.completedFuture(($T) null) : loader.load(id, env))\n", COMPLETABLE_FUTURE, RECORD)
            .addCode("    .toList();\n")
            .addCode("return $T.allOf(futures.toArray(new $T[0]))\n", COMPLETABLE_FUTURE, COMPLETABLE_FUTURE)
            .addCode("    .thenApply(v -> futures.stream().map($T::join).toList());\n", COMPLETABLE_FUTURE)
            .build();

        // rowsNodes: batch fetch for the DataLoader. Groups keys by typeId and executes one
        // query per type via hasIds, then maps results back to original positions using encode.
        var rowsNodes = MethodSpec.methodBuilder("rowsNodes")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(listOfString, "keys")
            .addParameter(ENV, "env")
            .addStatement("$T[] result = new $T[keys.size()]", RECORD, RECORD)
            .addStatement("$T positions = new $T<>()", mapStrListInt, LINKED_HASH_MAP)
            .beginControlFlow("for (int i = 0; i < keys.size(); i++)")
            .addStatement("$T id = keys.get(i)", STRING_CLASS)
            .addStatement("if (id != null) positions.computeIfAbsent(id, k -> new $T<>()).add(i)", ARRAY_LIST)
            .endControlFlow()
            .addStatement("$T byType = new $T<>()", mapStrListStr, LINKED_HASH_MAP)
            .beginControlFlow("for ($T id : keys)", STRING_CLASS)
            .addStatement("if (id == null) continue")
            .addStatement("$T typeId = $T.peekTypeId(id)", STRING_CLASS, nodeIdEncoder)
            .addStatement("if (typeId != null) byType.computeIfAbsent(typeId, k -> new $T<>()).add(id)", ARRAY_LIST)
            .endControlFlow()
            .addStatement("$T dsl = graphitronContext(env).getDslContext(env)", DSL_CONTEXT);

        for (var nt : nodeTypes) {
            var jooqTableClass = ClassName.get(jooqPackage + ".tables", nt.table().javaClassName());
            var typeClass      = ClassName.get(outputPackage + ".types", nt.name());
            String tableSingleton = nt.table().javaFieldName();
            rowsNodes.beginControlFlow("if (!byType.getOrDefault($S, $T.of()).isEmpty())", nt.typeId(), LIST);
            rowsNodes.addStatement("$T typeIds = byType.get($S)", listOfString, nt.typeId());
            rowsNodes.addStatement("$T t = $T.$L", jooqTableClass, jooqTableClass, tableSingleton);
            rowsNodes.addStatement("$T fields = new $T($T.$$fields(env.getSelectionSet(), t, env))",
                arrayListOfField, arrayListOfField, typeClass);
            rowsNodes.addStatement("fields.add($T.inline($S).as($S))", DSL, nt.name(), TYPENAME_COLUMN);
            var hasIdsArgs = CodeBlock.builder().add("$S, typeIds", nt.typeId());
            for (var col : nt.nodeKeyColumns()) {
                hasIdsArgs.add(", t.$L", col.javaName());
            }
            rowsNodes.beginControlFlow("for ($T r : dsl.select(fields).from(t).where($T.hasIds($L)).fetch())",
                RECORD, nodeIdEncoder, hasIdsArgs.build());
            var encodeArgs = CodeBlock.builder().add("$S", nt.typeId());
            for (var col : nt.nodeKeyColumns()) {
                encodeArgs.add(", r.get(t.$L)", col.javaName());
            }
            rowsNodes.addStatement("$T encodedId = $T.encode($L)", STRING_CLASS, nodeIdEncoder, encodeArgs.build());
            rowsNodes.addStatement("$T idxs = positions.get(encodedId)", listOfInteger);
            rowsNodes.beginControlFlow("if (idxs != null)");
            rowsNodes.addStatement("for (int idx : idxs) result[idx] = r");
            rowsNodes.endControlFlow();
            rowsNodes.endControlFlow(); // for r
            rowsNodes.endControlFlow(); // if !isEmpty
        }

        rowsNodes.addStatement("return $T.asList(result)", ARRAYS);

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
