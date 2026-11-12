package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Writes the {@code Connections} class from {@link ConnectionsQuery}: a class per connection type and
 * per edge type, each the closure its producer builds over one page, with a member for each position
 * of the connection shape its type fills and none for anything else.
 *
 * <p>A connection holds its page's elements, whether a next or a previous page exists, and the
 * function giving an element its cursor, a keyset position under the producer's ordering and never
 * the element's index; where its type fills them, the count and the facets are functions of the
 * request the producer supplies, run only when read. Its edges are built from the elements and the
 * cursor function, and its page info from the first and last element. An edge holds its element and
 * the cursor function it was handed, since one edge type serves every connection that pages through
 * it. Elements are jOOQ {@code Record}s, as every producer holds them.
 *
 * <p>The class also registers every connection and edge field to its member, keyed by the field's
 * own name and served by the member of the position it fills, through one {@code register} method
 * the schema class calls. A facets field's failure is routed as every fetcher routes one: its
 * aggregate is per-request SQL, so a raw message would otherwise reach the client.
 *
 * <p>The page info class, the facets records and the error router are other units' classes, named
 * by the caller.
 */
public final class ConnectionsEmitter {

    public static final String CLASS_NAME = "Connections";

    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName LIST = ClassName.get(List.class);
    private static final ClassName FUNCTION = ClassName.get("java.util.function", "Function");
    private static final TypeName LIST_OF_RECORD = ParameterizedTypeName.get(LIST, RECORD);
    private static final TypeName CURSOR = ParameterizedTypeName.get(FUNCTION, RECORD, ClassName.get(String.class));
    private static final ClassName CODE_REGISTRY = ClassName.get("graphql.schema", "GraphQLCodeRegistry", "Builder");
    private static final ClassName FIELD_COORDINATES = ClassName.get("graphql.schema", "FieldCoordinates");

    private ConnectionsEmitter() {}

    /**
     * The classes the connections read that other units write: the page info, the facets records
     * and the error router a failed facets field is routed through.
     */
    public record Runtime(ClassName pageInfo, ClassName facets, ClassName errorRouter) {}

    /**
     * The class a producer of the connection or edge type {@code typeName} builds, nested in the
     * {@code Connections} unit of the {@code util} package {@code utilPackage}.
     */
    public static ClassName className(String utilPackage, String typeName) {
        return ClassName.get(utilPackage, CLASS_NAME, typeName);
    }

    public static List<TypeSpec> emit(ConnectionsQuery.Shapes shapes, String outputPackage, Runtime runtime) {
        var util = outputPackage + ".util";
        var outer = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        for (var edge : shapes.edges()) {
            outer.addType(edge(edge));
        }
        for (var connection : shapes.connections()) {
            outer.addType(connection(connection, util, runtime));
        }
        outer.addMethod(register(shapes, util, runtime));
        return List.of(outer.build());
    }

    /**
     * Registers every connection and edge field to the member its position names on the field's
     * type's class. The facets member runs the facets aggregate, so its failure is caught and
     * routed; every other member reads the page or runs the count.
     */
    private static MethodSpec register(ConnectionsQuery.Shapes shapes, String util, Runtime runtime) {
        var method = MethodSpec.methodBuilder("register")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(CODE_REGISTRY, "codeRegistry");
        for (var connection : shapes.connections()) {
            var type = className(util, connection.typeName());
            connection.fieldPositions().forEach((field, position) -> {
                var read = CodeBlock.of("(($T) env.getSource())", type);
                CodeBlock fetcher = switch (position) {
                    case "EDGES" -> CodeBlock.of("$L.edges()", read);
                    case "NODES" -> CodeBlock.of("$L.nodes()", read);
                    case "PAGE_INFO" -> CodeBlock.of("$L.pageInfo()", read);
                    case "TOTAL_COUNT" -> CodeBlock.of("$L.totalCount(env)", read);
                    case "FACETS" -> CodeBlock.builder()
                        .add("{\n").indent()
                        .add("try {\n").indent()
                        .add("return $L.facets(env);\n", read)
                        .unindent().add("} catch ($T e) {\n", Exception.class).indent()
                        .add("return $L;\n", no.sikt.graphitron.render.ErrorDispatchFragments
                            .redact(runtime.errorRouter(), "e"))
                        .unindent().add("}\n")
                        .unindent().add("}")
                        .build();
                    default -> throw new IllegalStateException("Graphitron generator bug: connection '"
                        + connection.typeName() + "' field '" + field + "' fills " + position
                        + ", which is no connection position");
                };
                method.addStatement("codeRegistry.dataFetcher($T.coordinates($S, $S), ($T env) -> $L)",
                    FIELD_COORDINATES, connection.typeName(), field, ENV, fetcher);
            });
        }
        for (var edge : shapes.edges()) {
            var type = className(util, edge.typeName());
            edge.fieldPositions().forEach((field, position) -> {
                String member = switch (position) {
                    case "NODE" -> "node";
                    case "CURSOR" -> "cursor";
                    default -> throw new IllegalStateException("Graphitron generator bug: edge '"
                        + edge.typeName() + "' field '" + field + "' fills " + position
                        + ", which is no edge position");
                };
                method.addStatement("codeRegistry.dataFetcher($T.coordinates($S, $S), ($T env) -> (($T) env.getSource()).$L())",
                    FIELD_COORDINATES, edge.typeName(), field, ENV, type, member);
            });
        }
        return method.build();
    }

    private static TypeSpec edge(ConnectionsQuery.Edge edge) {
        var type = TypeSpec.classBuilder(edge.typeName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addField(FieldSpec.builder(RECORD, "element", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(CURSOR, "cursorOf", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(RECORD, "element")
                .addParameter(CURSOR, "cursorOf")
                .addStatement("this.element = element")
                .addStatement("this.cursorOf = cursorOf")
                .build());
        if (edge.positions().contains("NODE")) {
            type.addMethod(MethodSpec.methodBuilder("node")
                .addModifiers(Modifier.PUBLIC)
                .returns(RECORD)
                .addStatement("return element")
                .build());
        }
        if (edge.positions().contains("CURSOR")) {
            type.addMethod(MethodSpec.methodBuilder("cursor")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return cursorOf.apply(element)")
                .build());
        }
        return type.build();
    }

    private static TypeSpec connection(ConnectionsQuery.Connection connection, String util, Runtime runtime) {
        var positions = connection.positions();
        var edgeClass = ClassName.get(util, CLASS_NAME, connection.edgeTypeName());
        var pageInfo = runtime.pageInfo();
        TypeName count = ParameterizedTypeName.get(FUNCTION, ENV, ClassName.get(Integer.class));
        TypeName facetsRecord = connection.facetsTypeName() == null ? null
            : runtime.facets().nestedClass(connection.facetsTypeName());
        TypeName facets = facetsRecord == null ? null : ParameterizedTypeName.get(FUNCTION, ENV, facetsRecord);
        boolean counts = positions.contains("TOTAL_COUNT");
        boolean faceted = positions.contains("FACETS");
        if (faceted && facets == null) {
            throw new IllegalStateException("Graphitron generator bug: connection '" + connection.typeName()
                + "' fills FACETS but names no facets type; its facets field names one in every valid store");
        }

        var type = TypeSpec.classBuilder(connection.typeName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addField(FieldSpec.builder(LIST_OF_RECORD, "elements", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(TypeName.BOOLEAN, "hasNextPage", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(TypeName.BOOLEAN, "hasPreviousPage", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(CURSOR, "cursorOf", Modifier.PRIVATE, Modifier.FINAL).build());
        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(LIST_OF_RECORD, "elements")
            .addParameter(TypeName.BOOLEAN, "hasNextPage")
            .addParameter(TypeName.BOOLEAN, "hasPreviousPage")
            .addParameter(CURSOR, "cursorOf")
            .addStatement("this.elements = elements")
            .addStatement("this.hasNextPage = hasNextPage")
            .addStatement("this.hasPreviousPage = hasPreviousPage")
            .addStatement("this.cursorOf = cursorOf");
        if (counts) {
            type.addField(FieldSpec.builder(count, "countOf", Modifier.PRIVATE, Modifier.FINAL).build());
            constructor.addParameter(count, "countOf").addStatement("this.countOf = countOf");
        }
        if (faceted) {
            type.addField(FieldSpec.builder(facets, "facetsOf", Modifier.PRIVATE, Modifier.FINAL).build());
            constructor.addParameter(facets, "facetsOf").addStatement("this.facetsOf = facetsOf");
        }
        type.addMethod(constructor.build());

        if (positions.contains("EDGES")) {
            type.addMethod(MethodSpec.methodBuilder("edges")
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(LIST, edgeClass))
                .addStatement("return elements.stream().map(element -> new $T(element, cursorOf)).toList()",
                    edgeClass)
                .build());
        }
        if (positions.contains("NODES")) {
            type.addMethod(MethodSpec.methodBuilder("nodes")
                .addModifiers(Modifier.PUBLIC)
                .returns(LIST_OF_RECORD)
                .addStatement("return elements")
                .build());
        }
        if (positions.contains("PAGE_INFO")) {
            type.addMethod(MethodSpec.methodBuilder("pageInfo")
                .addModifiers(Modifier.PUBLIC)
                .returns(pageInfo)
                .addStatement("return new $T(hasNextPage, hasPreviousPage,\n"
                    + "elements.isEmpty() ? null : cursorOf.apply(elements.get(0)),\n"
                    + "elements.isEmpty() ? null : cursorOf.apply(elements.get(elements.size() - 1)))", pageInfo)
                .build());
        }
        if (counts) {
            type.addMethod(MethodSpec.methodBuilder("totalCount")
                .addModifiers(Modifier.PUBLIC)
                .returns(Integer.class)
                .addParameter(ENV, "env")
                .addStatement("return countOf.apply(env)")
                .build());
        }
        if (faceted) {
            type.addMethod(MethodSpec.methodBuilder("facets")
                .addModifiers(Modifier.PUBLIC)
                .returns(facetsRecord)
                .addParameter(ENV, "env")
                .addStatement("return facetsOf.apply(env)")
                .build());
        }
        return type.build();
    }
}
