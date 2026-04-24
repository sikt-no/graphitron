package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.model.FieldWrapper;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emit-time synthesis for {@code @asConnection} fields. Scans the assembled schema for fields
 * that carry the {@code @asConnection} directive on a bare-list return type (directive-driven
 * detection) and builds a plan describing which Connection, Edge, and PageInfo types need to be
 * synthesised. The plan is then used by:
 *
 * <ul>
 *   <li>{@link ObjectTypeGenerator} to replace the bare-list return type reference with a
 *       Connection type reference and to append {@code first} / {@code after} pagination
 *       arguments.</li>
 *   <li>{@link GraphitronSchemaClassGenerator} to register synthesised types via
 *       {@code .additionalType(...)} so graphql-java's {@code GraphQLSchema.build()} includes
 *       them at runtime.</li>
 *   <li>{@link no.sikt.graphitron.rewrite.GraphQLRewriteGenerator} to write the synthesised
 *       {@code <ConnName>Type} / {@code <ConnName>EdgeType} / {@code PageInfoType} Java files to
 *       the {@code schema} sub-package.</li>
 * </ul>
 *
 * <p>Structural fields (where the Connection type is already declared in the assembled schema)
 * are skipped automatically: {@link #buildPlan} only collects entries where
 * {@code assembled.getType(resolvedConnectionName) == null}.
 */
public final class ConnectionSynthesis {

    static final String DIRECTIVE_AS_CONNECTION = "asConnection";
    static final String ARG_CONNECTION_NAME    = "connectionName";
    static final String ARG_DEFAULT_FIRST_VALUE = "defaultFirstValue";

    private static final ClassName OBJECT_TYPE        = ClassName.get("graphql.schema", "GraphQLObjectType");
    private static final ClassName FIELD_DEF          = ClassName.get("graphql.schema", "GraphQLFieldDefinition");
    private static final ClassName TYPE_REF           = ClassName.get("graphql.schema", "GraphQLTypeReference");
    private static final ClassName NON_NULL           = ClassName.get("graphql.schema", "GraphQLNonNull");
    private static final ClassName LIST               = ClassName.get("graphql.schema", "GraphQLList");
    private static final ClassName APPLIED_DIRECTIVE  = ClassName.get("graphql.schema", "GraphQLAppliedDirective");
    private static final ClassName CODE_REGISTRY_BLDR = ClassName.get("graphql.schema", "GraphQLCodeRegistry", "Builder");
    private static final ClassName FIELD_COORDS       = ClassName.get("graphql.schema", "FieldCoordinates");

    private ConnectionSynthesis() {}

    /**
     * Per-connection synthesis metadata. Keyed by the resolved connection type name.
     *
     * @param elementTypeName the GraphQL type name of the element (e.g. {@code "Film"})
     * @param itemNullable    {@code true} when individual items may be null
     * @param shareable       {@code true} when at least one carrier field is also {@code @shareable}
     * @param defaultPageSize the default value for the synthesised {@code first} argument
     */
    public record ConnectionDef(
        String elementTypeName,
        boolean itemNullable,
        boolean shareable,
        int defaultPageSize
    ) {}

    /**
     * Synthesis plan computed from the assembled schema.
     *
     * @param connections       connection name to {@link ConnectionDef}, insertion-ordered
     * @param needPageInfo      {@code true} when at least one Connection is being synthesised and
     *                          the schema does not already declare a {@code PageInfo} type
     * @param pageInfoShareable {@code true} when any synthesised Connection is shareable
     *                          (propagated to the synthesised {@code PageInfo})
     */
    public record Plan(
        Map<String, ConnectionDef> connections,
        boolean needPageInfo,
        boolean pageInfoShareable
    ) {
        public static final Plan EMPTY = new Plan(Map.of(), false, false);
    }

    /**
     * Resolves the connection type name for a field that carries {@code @asConnection}.
     * Returns the explicit {@code connectionName} argument value when present; otherwise derives
     * {@code <ParentType><FieldName>Connection} using the same convention as
     * {@link no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter}.
     */
    static String resolveConnectionName(String parentTypeName, GraphQLFieldDefinition field) {
        var applied = field.getAppliedDirective(DIRECTIVE_AS_CONNECTION);
        if (applied != null) {
            var arg = applied.getArgument(ARG_CONNECTION_NAME);
            if (arg != null && arg.getValue() instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return parentTypeName + capitalize(field.getName()) + "Connection";
    }

    /**
     * Derives the edge type name from the connection type name using the same convention as
     * {@link FetcherRegistrationsEmitter}: replace {@code "Connection"} with {@code "Edge"}.
     */
    static String resolveEdgeName(String connectionName) {
        return connectionName.replace("Connection", "Edge");
    }

    /**
     * Builds the synthesis plan by scanning all object and interface type fields in
     * {@code assembled} for directive-driven {@code @asConnection} use. Fields whose resolved
     * Connection type name already exists in the schema are skipped (structural path).
     *
     * <p>{@code PageInfo} emission is needed when at least one Connection is being synthesised
     * and the schema does not already declare {@code PageInfo}.
     */
    public static Plan buildPlan(GraphQLSchema assembled) {
        var connections = new LinkedHashMap<String, ConnectionDef>();
        boolean pageInfoShareable = false;

        for (var type : assembled.getAllTypesAsList()) {
            if (type.getName().startsWith("_")) continue;
            List<GraphQLFieldDefinition> fields;
            if (type instanceof GraphQLObjectType obj) {
                fields = obj.getFieldDefinitions();
            } else if (type instanceof GraphQLInterfaceType iface) {
                fields = iface.getFieldDefinitions();
            } else {
                continue;
            }
            String parentTypeName = type.getName();
            for (var field : fields) {
                if (!field.hasAppliedDirective(DIRECTIVE_AS_CONNECTION)) continue;

                GraphQLOutputType fieldType = field.getType();
                boolean outerNonNull = fieldType instanceof GraphQLNonNull;
                var unwrapped = outerNonNull
                    ? ((GraphQLNonNull) fieldType).getWrappedType()
                    : fieldType;
                if (!(unwrapped instanceof GraphQLList listType)) continue;

                String connName = resolveConnectionName(parentTypeName, field);

                // Structural field: Connection type already exists; synthesis not needed.
                if (assembled.getType(connName) != null) continue;

                boolean itemNullable = !(listType.getWrappedType() instanceof GraphQLNonNull);
                var elementLayer = itemNullable
                    ? listType.getWrappedType()
                    : ((GraphQLNonNull) listType.getWrappedType()).getWrappedType();
                String elementTypeName = elementLayer instanceof GraphQLNamedType named
                    ? named.getName() : elementLayer.toString();

                int defaultPageSize = resolveDefaultFirstValue(field);
                boolean shareable = field.hasAppliedDirective("shareable");
                if (shareable) pageInfoShareable = true;

                var existing = connections.get(connName);
                if (existing != null) {
                    // Dedup: shareable is OR'd; element type and nullability are authoritative
                    // from the first occurrence (collision validation is the validator's job).
                    connections.put(connName, new ConnectionDef(
                        existing.elementTypeName(), existing.itemNullable(),
                        existing.shareable() || shareable,
                        existing.defaultPageSize()));
                } else {
                    connections.put(connName, new ConnectionDef(elementTypeName, itemNullable, shareable, defaultPageSize));
                }
            }
        }

        boolean needPageInfo = !connections.isEmpty() && assembled.getType("PageInfo") == null;
        return new Plan(Map.copyOf(connections), needPageInfo, pageInfoShareable);
    }

    /**
     * Produces the TypeSpecs for all synthesised Connection, Edge, and PageInfo types described
     * by {@code plan}. Returns an empty list when {@code plan} is empty. Results are sorted by
     * class name for stable output diffs.
     *
     * <p>Each Connection and Edge TypeSpec includes a {@code registerFetchers} method wired to
     * {@code <outputPackage>.util.ConnectionHelper} — matching the pattern that
     * {@link FetcherRegistrationsEmitter} applies for structural (hand-written) Connection types.
     *
     * @param outputPackage the top-level output package, e.g. {@code "no.sikt.graphitron.generated"};
     *                      used to construct the {@code ConnectionHelper} class reference
     */
    public static List<TypeSpec> emitSupportingTypes(Plan plan, String outputPackage) {
        if (plan.connections().isEmpty()) return List.of();
        String utilPackage = outputPackage.isEmpty() ? "util" : outputPackage + ".util";
        var result = new ArrayList<TypeSpec>();
        for (var entry : plan.connections().entrySet()) {
            String connName = entry.getKey();
            String edgeName = resolveEdgeName(connName);
            ConnectionDef def = entry.getValue();
            result.add(buildConnectionTypeSpec(connName, edgeName, def, utilPackage));
            result.add(buildEdgeTypeSpec(edgeName, def, utilPackage));
        }
        if (plan.needPageInfo()) {
            result.add(buildPageInfoTypeSpec(plan.pageInfoShareable()));
        }
        result.sort(Comparator.comparing(TypeSpec::name));
        return result;
    }

    // ===== TypeSpec builders =====

    private static TypeSpec buildConnectionTypeSpec(String connName, String edgeName, ConnectionDef def,
                                                    String utilPackage) {
        var edgesField = CodeBlock.builder()
            .add("$T.newFieldDefinition()", FIELD_DEF)
            .add(".name($S)", "edges")
            .add(".type($T.nonNull($T.list($T.nonNull($T.typeRef($S)))))", NON_NULL, LIST, NON_NULL, TYPE_REF, edgeName)
            .add(".build()")
            .build();

        CodeBlock nodesInnerRef = def.itemNullable()
            ? CodeBlock.of("$T.typeRef($S)", TYPE_REF, def.elementTypeName())
            : CodeBlock.builder()
                .add("$T.nonNull($T.typeRef($S))", NON_NULL, TYPE_REF, def.elementTypeName())
                .build();
        var nodesField = CodeBlock.builder()
            .add("$T.newFieldDefinition()", FIELD_DEF)
            .add(".name($S)", "nodes")
            .add(".type($T.nonNull($T.list(", NON_NULL, LIST)
            .add(nodesInnerRef)
            .add(")))")
            .add(".build()")
            .build();

        var pageInfoField = CodeBlock.builder()
            .add("$T.newFieldDefinition()", FIELD_DEF)
            .add(".name($S)", "pageInfo")
            .add(".type($T.nonNull($T.typeRef($S)))", NON_NULL, TYPE_REF, "PageInfo")
            .add(".build()")
            .build();

        var body = CodeBlock.builder()
            .add("return $T.newObject()", OBJECT_TYPE)
            .indent()
            .add("\n.name($S)", connName)
            .add("\n.field(").add(edgesField).add(")")
            .add("\n.field(").add(nodesField).add(")")
            .add("\n.field(").add(pageInfoField).add(")");
        if (def.shareable()) {
            body.add("\n.withAppliedDirective($T.newDirective().name($S).build())", APPLIED_DIRECTIVE, "shareable");
        }
        body.add("\n.build();\n").unindent();

        var helper = ClassName.get(utilPackage, ConnectionHelperClassGenerator.CLASS_NAME);
        var fetchersBody = CodeBlock.builder()
            .add("codeRegistry").indent()
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::edges)",    FIELD_COORDS, connName, "edges",    helper)
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::nodes)",    FIELD_COORDS, connName, "nodes",    helper)
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::pageInfo);\n", FIELD_COORDS, connName, "pageInfo", helper)
            .unindent()
            .build();

        return TypeSpec.classBuilder(connName + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.methodBuilder("type")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(OBJECT_TYPE)
                .addCode(body.build())
                .build())
            .addMethod(MethodSpec.methodBuilder("registerFetchers")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(CODE_REGISTRY_BLDR, "codeRegistry")
                .addCode(fetchersBody)
                .build())
            .build();
    }

    private static TypeSpec buildEdgeTypeSpec(String edgeName, ConnectionDef def, String utilPackage) {
        var cursorField = CodeBlock.builder()
            .add("$T.newFieldDefinition()", FIELD_DEF)
            .add(".name($S)", "cursor")
            .add(".type($T.nonNull($T.typeRef($S)))", NON_NULL, TYPE_REF, "String")
            .add(".build()")
            .build();

        CodeBlock nodeTypeRef = def.itemNullable()
            ? CodeBlock.of("$T.typeRef($S)", TYPE_REF, def.elementTypeName())
            : CodeBlock.builder()
                .add("$T.nonNull($T.typeRef($S))", NON_NULL, TYPE_REF, def.elementTypeName())
                .build();
        var nodeField = CodeBlock.builder()
            .add("$T.newFieldDefinition()", FIELD_DEF)
            .add(".name($S)", "node")
            .add(".type(").add(nodeTypeRef).add(")")
            .add(".build()")
            .build();

        var body = CodeBlock.builder()
            .add("return $T.newObject()", OBJECT_TYPE)
            .indent()
            .add("\n.name($S)", edgeName)
            .add("\n.field(").add(cursorField).add(")")
            .add("\n.field(").add(nodeField).add(")");
        if (def.shareable()) {
            body.add("\n.withAppliedDirective($T.newDirective().name($S).build())", APPLIED_DIRECTIVE, "shareable");
        }
        body.add("\n.build();\n").unindent();

        var helper = ClassName.get(utilPackage, ConnectionHelperClassGenerator.CLASS_NAME);
        var fetchersBody = CodeBlock.builder()
            .add("codeRegistry").indent()
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::edgeNode)",    FIELD_COORDS, edgeName, "node",   helper)
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::edgeCursor);\n", FIELD_COORDS, edgeName, "cursor", helper)
            .unindent()
            .build();

        return TypeSpec.classBuilder(edgeName + "Type")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.methodBuilder("type")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(OBJECT_TYPE)
                .addCode(body.build())
                .build())
            .addMethod(MethodSpec.methodBuilder("registerFetchers")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(CODE_REGISTRY_BLDR, "codeRegistry")
                .addCode(fetchersBody)
                .build())
            .build();
    }

    private static TypeSpec buildPageInfoTypeSpec(boolean shareable) {
        var body = CodeBlock.builder()
            .add("return $T.newObject()", OBJECT_TYPE)
            .indent()
            .add("\n.name($S)", "PageInfo")
            .add("\n.field($T.newFieldDefinition().name($S).type($T.nonNull($T.typeRef($S))).build())",
                FIELD_DEF, "hasNextPage", NON_NULL, TYPE_REF, "Boolean")
            .add("\n.field($T.newFieldDefinition().name($S).type($T.nonNull($T.typeRef($S))).build())",
                FIELD_DEF, "hasPreviousPage", NON_NULL, TYPE_REF, "Boolean")
            .add("\n.field($T.newFieldDefinition().name($S).type($T.typeRef($S)).build())",
                FIELD_DEF, "startCursor", TYPE_REF, "String")
            .add("\n.field($T.newFieldDefinition().name($S).type($T.typeRef($S)).build())",
                FIELD_DEF, "endCursor", TYPE_REF, "String");
        if (shareable) {
            body.add("\n.withAppliedDirective($T.newDirective().name($S).build())", APPLIED_DIRECTIVE, "shareable");
        }
        body.add("\n.build();\n").unindent();

        return TypeSpec.classBuilder("PageInfoType")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.methodBuilder("type")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(OBJECT_TYPE)
                .addCode(body.build())
                .build())
            .build();
    }

    // ===== Helpers =====

    private static int resolveDefaultFirstValue(GraphQLFieldDefinition field) {
        var dir = field.getAppliedDirective(DIRECTIVE_AS_CONNECTION);
        if (dir == null) return FieldWrapper.DEFAULT_PAGE_SIZE;
        var arg = dir.getArgument(ARG_DEFAULT_FIRST_VALUE);
        if (arg == null || arg.getValue() == null) return FieldWrapper.DEFAULT_PAGE_SIZE;
        Object val = arg.getValue();
        if (val instanceof graphql.language.IntValue iv) return iv.getValue().intValueExact();
        if (val instanceof Number n) return n.intValue();
        return FieldWrapper.DEFAULT_PAGE_SIZE;
    }

    static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
