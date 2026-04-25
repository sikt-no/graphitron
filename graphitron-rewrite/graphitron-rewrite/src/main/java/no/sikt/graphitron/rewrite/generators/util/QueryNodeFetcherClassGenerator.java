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
 * <p>See plan-nodeid-directives.md "{@code Query.node(id:)} dispatch is core".
 */
public class QueryNodeFetcherClassGenerator {

    public static final String CLASS_NAME                = "QueryNodeFetcher";
    public static final String DISPATCH_METHOD           = "getNode";
    public static final String REGISTER_RESOLVER_METHOD  = "registerTypeResolver";
    public static final String TYPENAME_COLUMN           = "__typename";
    public static final String NODE_INTERFACE_NAME       = "Node";

    private static final ClassName ENV               = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RECORD            = ClassName.get("org.jooq", "Record");
    private static final ClassName DSL_CONTEXT       = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName FIELD             = ClassName.get("org.jooq", "Field");
    private static final ClassName DSL               = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName ARRAY_LIST        = ClassName.get("java.util", "ArrayList");
    private static final ClassName CODE_REGISTRY_BLDR = ClassName.get("graphql.schema", "GraphQLCodeRegistry", "Builder");

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

        var dispatch = MethodSpec.methodBuilder(DISPATCH_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(RECORD)
            .addParameter(ENV, "env")
            .addJavadoc("Dispatches a Relay {@code Query.node(id:)} call to the matching\n"
                + "{@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType NodeType} table.\n"
                + "Returns {@code null} for null/garbage/unknown IDs (Relay spec).\n")
            .addStatement("$T id = env.getArgument($S)", String.class, "id")
            .addStatement("if (id == null) return null")
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
            dispatch.beginControlFlow("if ($S.equals(typeId))", nt.typeId());
            dispatch.addStatement("$T t = $T.$L", jooqTableClass, jooqTableClass, tableSingleton);
            dispatch.addStatement("$T fields = new $T($T.$$fields(env.getSelectionSet(), t, env))",
                arrayListOfField, arrayListOfField, typeClass);
            dispatch.addStatement("fields.add($T.inline($S).as($S))", DSL, nt.name(), TYPENAME_COLUMN);
            var hasIdArgs = CodeBlock.builder().add("$S, id", nt.typeId());
            for (var col : nt.nodeKeyColumns()) {
                hasIdArgs.add(", t.$L", col.javaName());
            }
            dispatch.addStatement("return dsl.select(fields).from(t).where($T.hasId($L)).fetchOne()",
                nodeIdEncoder, hasIdArgs.build());
            dispatch.endControlFlow();
        }
        dispatch.addStatement("return null");

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
            .addJavadoc("Runtime dispatcher for the Relay {@code Query.node(id:)} field. See\n"
                + "{@link QueryNodeFetcherClassGenerator} for emission semantics.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(dispatch.build())
            .addMethod(registerResolver)
            .addMethod(contextHelper)
            .build();
        return List.of(spec);
    }
}
