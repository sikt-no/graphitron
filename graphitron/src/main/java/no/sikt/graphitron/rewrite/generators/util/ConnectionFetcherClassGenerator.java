package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates the per-connection {@code <Conn>Fetchers} and per-edge {@code <Edge>Fetchers} classes
 *. Each carries one {@code public static} delegate per field
 * ({@code edges} / {@code nodes} / {@code pageInfo} / {@code totalCount}; {@code node} /
 * {@code cursor}) that forwards to the shared {@link ConnectionHelperClassGenerator ConnectionHelper}.
 *
 * <p>The generic pagination logic stays in {@code ConnectionHelper} (one home, hand-auditable);
 * these classes exist only to give each field its own lookup symbol, so a consumer debugging
 * {@code FilmConnection.edges} finds {@code FilmConnectionFetchers.edges} rather than a method
 * reference into a shared helper.
 */
public final class ConnectionFetcherClassGenerator {

    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");

    private ConnectionFetcherClassGenerator() {}

    /**
     * Renders one connection carrier's fetchers pair (the connection class, then the edge
     * class); membership is the type-unit relation's connection row, this method builds the
     * two bodies for the type the row names.
     */
    public static List<TypeSpec> generateFor(GraphitronType.ConnectionType ct, String outputPackage) {
        var helper = ClassName.get(outputPackage + ".util", ConnectionHelperClassGenerator.CLASS_NAME);
        var conn = TypeSpec.classBuilder(ct.name() + no.sikt.graphitron.plan.GeneratedUnits.FETCHERS_SUFFIX)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(delegate("edges", helper, "edges"))
            .addMethod(delegate("nodes", helper, "nodes"))
            .addMethod(delegate("pageInfo", helper, "pageInfo"));
        // totalCount keeps its SDL-presence gate, matching FetcherRegistrationsEmitter.
        if (ct.schemaType().getFieldDefinition("totalCount") != null) {
            conn.addMethod(delegate("totalCount", helper, "totalCount"));
        }
        // Facets delegate under the same has-facets gate as the registration emitter,
        // so the code-registry reference and the method can never drift.
        if (!ct.facets().isEmpty()) {
            conn.addMethod(facetsDelegate(ct, helper, outputPackage));
        }
        var edge = TypeSpec.classBuilder(ct.edgeTypeName() + no.sikt.graphitron.plan.GeneratedUnits.FETCHERS_SUFFIX)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(delegate("node", helper, "edgeNode"))
            .addMethod(delegate("cursor", helper, "edgeCursor"))
            .build();
        return List.of(conn.build(), edge);
    }

    private static MethodSpec delegate(String name, ClassName helper, String helperMethod) {
        return MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("return $T.$L(env)", helper, helperMethod)
            .build();
    }

    /**
     * The facets delegate routes failures through the redaction contract every emitted fetcher
     * honours: the facet aggregate is real per-request SQL, and letting
     * its exception reach graphql-java's default handler would copy the raw message — for a jOOQ
     * {@code DataAccessException}, the rendered SQL — into the client-visible errors array.
     * Catching here keeps the degrade contract (the nullable facets field resolves to null with a
     * redacted error; the page is unaffected) while the sibling delegates stay plain reads with no
     * SQL of their own. {@code totalCount} shares the gap by precedent and is tracked separately.
     */
    private static MethodSpec facetsDelegate(GraphitronType.ConnectionType ct, ClassName helper,
            String outputPackage) {
        var facetsClass = ClassName.get(outputPackage + ".util", FacetsClassGenerator.CLASS_NAME);
        var record = facetsClass.nestedClass(
            no.sikt.graphitron.model.grammar.FacetNaming.facetsTypeName(ct.name()));
        // The helper's buckets, by facet, become the connection's facets record: one component per
        // facet in the facets type's field order, each bucket a record of its facet value type.
        // A facet the client did not select has no buckets and its component stays null, unread.
        var components = com.palantir.javapoet.CodeBlock.builder();
        for (int i = 0; i < ct.facets().size(); i++) {
            var spec = ct.facets().get(i);
            if (i > 0) {
                components.add(",\n");
            }
            components.add("buckets.get($S) == null ? null : buckets.get($S).stream()"
                + ".map(e -> new $T(e.get(\"value\"), (Integer) e.get(\"count\"))).toList()",
                spec.inputFieldName(), spec.inputFieldName(),
                facetsClass.nestedClass(spec.facetValueTypeName()));
        }
        var buckets = com.palantir.javapoet.ParameterizedTypeName.get(ClassName.get(java.util.Map.class),
            ClassName.get(String.class), com.palantir.javapoet.ParameterizedTypeName.get(
                ClassName.get(java.util.List.class), com.palantir.javapoet.ParameterizedTypeName.get(
                    ClassName.get(java.util.Map.class), ClassName.get(String.class), ClassName.get(Object.class))));
        return MethodSpec.methodBuilder("facets")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .beginControlFlow("try")
            .addStatement("$T buckets = $T.facets(env)", buckets, helper)
            .addStatement("if (buckets == null) return null")
            .addStatement("return new $T(\n$>$L$<)", record, components.build())
            .nextControlFlow("catch ($T e)", Exception.class)
            .addStatement("return $L",
                no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator
                    .noChannelRouterCall(outputPackage, "e"))
            .endControlFlow()
            .build();
    }
}
