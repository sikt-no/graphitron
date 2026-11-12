package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
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
 * <p>The page info class and the facets records are other units' classes, named by the caller.
 */
public final class ConnectionsEmitter {

    public static final String CLASS_NAME = "Connections";

    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName LIST = ClassName.get(List.class);
    private static final ClassName FUNCTION = ClassName.get("java.util.function", "Function");
    private static final TypeName LIST_OF_RECORD = ParameterizedTypeName.get(LIST, RECORD);
    private static final TypeName CURSOR = ParameterizedTypeName.get(FUNCTION, RECORD, ClassName.get(String.class));

    private ConnectionsEmitter() {}

    /** The classes the connections read that other units write: the page info and the facets records. */
    public record Runtime(ClassName pageInfo, ClassName facets) {}

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
        return List.of(outer.build());
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
