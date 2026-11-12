package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import no.sikt.graphitron.command.ConnectionClass;
import no.sikt.graphitron.emit.ConnectionsEmitter;

/**
 * How a producer builds the generated connection class its field's type names, one spelling for
 * every producer: the page's rows trimmed to the page (reversed back when paging backward), the
 * two page flags, the cursor function over the page's order columns, and, where the class takes
 * them, the count and facets closures the producer supplies. The rows, the page request and the
 * closures are the producer's locals; nothing here runs a statement.
 *
 * <p>Every expression reads a {@code page} local, the {@code ConnectionHelper.PageRequest} the
 * producer paged with.
 */
public final class ConnectionConstruction {

    /** The lambda parameter a count closure takes, named apart from the producer's own {@code env}. */
    public static final String COUNT_ENV = "countEnv";

    /** The lambda parameter a facets closure takes, named apart from the producer's own {@code env}. */
    public static final String FACETS_ENV = "facetsEnv";

    /** The generated unit holding the facets records, beside the helper in the util package. */
    private static final String FACETS_CLASS_NAME = "Facets";

    private ConnectionConstruction() {}

    /** The class a producer of {@code connection} builds, beside {@code helper} in the util package. */
    public static ClassName classOf(ClassName helper, ConnectionClass connection) {
        return ConnectionsEmitter.className(helper.packageName(), connection.typeName());
    }

    /**
     * {@code new Connections.<Type>(...)} over the over-fetched rows in {@code rowsLocal}.
     * {@code countOf} is the count closure (read only where the class counts), {@code facetsOf}
     * the facets closure, or {@code null} for a producer with no facet plan, whose facets field
     * then resolves to {@code null}, as it does on every connection a batched producer builds.
     */
    public static CodeBlock build(ClassName helper, ConnectionClass connection, String rowsLocal,
            CodeBlock countOf, CodeBlock facetsOf) {
        var code = CodeBlock.builder()
            .add("new $T(\n", classOf(helper, connection))
            .indent()
            .add("$T.trim($L, page.pageSize(), page.backward()),\n", helper, rowsLocal)
            .add("$T.hasNextPage($L, page.pageSize(), page.after(), page.backward()),\n", helper, rowsLocal)
            .add("$T.hasPreviousPage($L, page.pageSize(), page.after(), page.backward()),\n", helper, rowsLocal)
            .add("$T.cursorOf(page.extraFields())", helper);
        tail(code, connection, countOf, facetsOf);
        return code.add(")").unindent().build();
    }

    /**
     * The empty page: no rows, no further page either way, and a count of 0 where the class
     * counts, which is what a parent whose query found nothing counts too.
     */
    public static CodeBlock empty(ClassName helper, ConnectionClass connection) {
        var code = CodeBlock.builder()
            .add("new $T($T.of(), false, false, $T.cursorOf($T.of())", classOf(helper, connection),
                ClassName.get("java.util", "List"), helper, ClassName.get("java.util", "List"));
        tail(code, connection, CodeBlock.of("$L -> 0", COUNT_ENV), null);
        return code.add(")").build();
    }

    /**
     * The count closure: {@code count} runs when the client reads {@code totalCount}, on the
     * context {@link #closureDsl} names for {@link #COUNT_ENV}.
     */
    public static CodeBlock countOf(CodeBlock count) {
        return CodeBlock.of("$L -> $L", COUNT_ENV, count);
    }

    /**
     * The facets closure of a faceted root producer: when the client reads the facets field it
     * buckets the producer's {@code facetBase}, {@code facetConditions} and {@code facetSpecs}
     * locals, then builds the type's facets record from the buckets, one component per facet in
     * {@code specs} order, each bucket a record of the facet's value type. A facet the client did
     * not select has no buckets and its component stays {@code null}, unread.
     */
    public static CodeBlock facetsOf(ClassName helper, ConnectionClass connection,
            java.util.List<no.sikt.graphitron.rewrite.model.FacetSpec> specs, boolean routed) {
        var facets = ClassName.get(helper.packageName(), FACETS_CLASS_NAME);
        var record = facets.nestedClass(
            no.sikt.graphitron.model.grammar.FacetNaming.facetsTypeName(connection.typeName()));
        var components = CodeBlock.builder();
        for (int i = 0; i < specs.size(); i++) {
            var spec = specs.get(i);
            if (i > 0) {
                components.add(",\n");
            }
            components.add("buckets.get($S) == null ? null : buckets.get($S).stream()"
                + ".map(bucket -> new $T(bucket.get(\"value\"), ($T) bucket.get(\"count\"))).toList()",
                spec.inputFieldName(), spec.inputFieldName(),
                facets.nestedClass(spec.facetValueTypeName()), Integer.class);
        }
        var map = ClassName.get("java.util", "Map");
        var buckets = com.palantir.javapoet.ParameterizedTypeName.get(map, ClassName.get(String.class),
            com.palantir.javapoet.ParameterizedTypeName.get(ClassName.get("java.util", "List"),
                com.palantir.javapoet.ParameterizedTypeName.get(map, ClassName.get(String.class),
                    ClassName.get(Object.class))));
        return CodeBlock.builder()
            .add("$L -> {\n", FACETS_ENV)
            .indent()
            .add("$T buckets = $T.facetBuckets($L, $L, facetBase, facetConditions, facetSpecs);\n",
                buckets, helper, closureDsl(helper, routed, FACETS_ENV), FACETS_ENV)
            .add("return new $T(\n$>$L$<);\n", record, components.build())
            .unindent()
            .add("}")
            .build();
    }

    /**
     * The context a closure runs on: the producer's own {@code dsl} under routed tenancy, else the
     * request's, resolved through {@code helper} from the closure's own environment.
     */
    public static CodeBlock closureDsl(ClassName helper, boolean routed, String envName) {
        return routed ? CodeBlock.of("dsl") : CodeBlock.of("$T.dsl($L)", helper, envName);
    }

    private static void tail(CodeBlock.Builder code, ConnectionClass connection, CodeBlock countOf,
            CodeBlock facetsOf) {
        if (connection.counts()) {
            code.add(",\n$L", countOf);
        }
        if (connection.faceted()) {
            code.add(",\n$L", facetsOf != null ? facetsOf : CodeBlock.of("$L -> null", FACETS_ENV));
        }
    }
}
