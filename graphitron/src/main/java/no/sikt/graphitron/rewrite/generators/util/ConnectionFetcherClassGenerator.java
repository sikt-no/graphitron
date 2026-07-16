package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
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

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        var helper = ClassName.get(outputPackage + ".util", ConnectionHelperClassGenerator.CLASS_NAME);
        var out = new ArrayList<TypeSpec>();
        schema.types().values().stream()
            .filter(t -> t instanceof GraphitronType.ConnectionType)
            .map(t -> (GraphitronType.ConnectionType) t)
            .sorted(Comparator.comparing(GraphitronType.ConnectionType::name))
            .forEach(ct -> {
                var conn = TypeSpec.classBuilder(ct.name() + "Fetchers")
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
                    conn.addMethod(facetsDelegate(helper, outputPackage));
                }
                out.add(conn.build());

                out.add(TypeSpec.classBuilder(ct.edgeTypeName() + "Fetchers")
                    .addModifiers(Modifier.PUBLIC)
                    .addMethod(delegate("node", helper, "edgeNode"))
                    .addMethod(delegate("cursor", helper, "edgeCursor"))
                    .build());
            });
        return out;
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
     * honours (R13 review, finding 5): the facet aggregate is real per-request SQL, and letting
     * its exception reach graphql-java's default handler would copy the raw message — for a jOOQ
     * {@code DataAccessException}, the rendered SQL — into the client-visible errors array.
     * Catching here keeps the degrade contract (the nullable facets field resolves to null with a
     * redacted error; the page is unaffected) while the sibling delegates stay plain reads with no
     * SQL of their own. {@code totalCount} shares the gap by precedent and is tracked separately.
     */
    private static MethodSpec facetsDelegate(ClassName helper, String outputPackage) {
        return MethodSpec.methodBuilder("facets")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .beginControlFlow("try")
            .addStatement("return $T.facets(env)", helper)
            .nextControlFlow("catch ($T e)", Exception.class)
            .addStatement("return $L",
                no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator
                    .noChannelRouterCall(outputPackage, "e"))
            .endControlFlow()
            .build();
    }
}
