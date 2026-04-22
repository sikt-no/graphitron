package no.sikt.graphitron.rewrite.generators;


import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.TableRef;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates {@code GraphitronWiring.java} in {@code <outputPackage>.rewrite}.
 *
 * <p>The generated class contains a single {@code build()} method that returns a
 * {@code RuntimeWiring.Builder} with all generated {@code *Fetchers.wiring()} calls registered.
 * The caller can add further type resolvers (e.g. for custom scalars, unions) before calling
 * {@code .build()} on the returned builder.
 *
 * <p>This is the glue that connects the per-type wiring methods into a single
 * {@code RuntimeWiring} usable by graphql-java's {@code GraphQL.newGraphQL(schema, wiring)}.
 */
public class GraphitronWiringClassGenerator {

    private static final ClassName RUNTIME_WIRING = ClassName.get("graphql.schema.idl", "RuntimeWiring");

    /**
     * Connection wiring info: the generated Connection and Edge type names for one connection field.
     *
     * @param connectionTypeName e.g. "QueryFilmsConnection"
     * @param edgeTypeName       e.g. "QueryFilmsEdge"
     */
    public record ConnectionWiring(String connectionTypeName, String edgeTypeName) {}

    /**
     * Nested-type wiring info: one {@code TypeRuntimeWiring} per plain GraphQL object type
     * reached through a {@link ChildField.NestingField}. The nested type's own data fetchers read
     * columns from the parent Record via {@link TypeFetcherGenerator#buildWiringEntry}. The
     * {@code representativeParentTable} is the first-seen parent's table (SDL order); multi-parent
     * compatibility is handled at runtime by jOOQ's name-based fallback in {@code Record.get(Field)}.
     *
     * @param nestedTypeName             the GraphQL type name (e.g. "Money")
     * @param fields                     the classified nested fields to wire, in SDL order
     * @param representativeParentTable  the first-seen parent's table; column Field references
     *                                   resolve against this table at generation time
     */
    public record NestedTypeWiring(String nestedTypeName, List<ChildField> fields,
                                    TableRef representativeParentTable) {}

    /**
     * Generates the {@code GraphitronWiring} class.
     *
     * @param fetcherClassNames  the simple class names of all generated {@code *Fetchers} classes
     * @param connectionWirings  connection type wiring entries for Connection/Edge types
     * @param nestedTypeWirings  nested-type wiring entries for plain object types reached through
     *                           {@link ChildField.NestingField}
     */
    public static TypeSpec generate(List<String> fetcherClassNames,
                                     List<ConnectionWiring> connectionWirings,
                                     List<NestedTypeWiring> nestedTypeWirings) {
        var fetchersPackage = RewriteConfig.outputPackage() + ".rewrite.fetchers";
        var rewritePackage = RewriteConfig.outputPackage() + ".rewrite";
        var builderType = ClassName.get("graphql.schema.idl", "RuntimeWiring", "Builder");

        var body = CodeBlock.builder()
            .add("return $T.newRuntimeWiring()", RUNTIME_WIRING);

        boolean hasEntries = !fetcherClassNames.isEmpty()
            || !connectionWirings.isEmpty()
            || !nestedTypeWirings.isEmpty();

        if (!hasEntries) {
            body.add(";\n");
        } else {
            body.indent();
            for (var className : fetcherClassNames) {
                var fetcherClass = ClassName.get(fetchersPackage, className);
                body.add("\n.type($T.wiring())", fetcherClass);
            }

            // Connection type wiring: edges, nodes, pageInfo
            var connectionHelperClass = ClassName.get(rewritePackage,
                ConnectionHelperClassGenerator.CLASS_NAME);
            var TYPE_WIRING = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");

            for (var cw : connectionWirings) {
                body.add("\n.type($T.newTypeWiring($S)\n", TYPE_WIRING, cw.connectionTypeName());
                body.indent();
                body.add(".dataFetcher(\"edges\", $T::edges)\n", connectionHelperClass);
                body.add(".dataFetcher(\"nodes\", $T::nodes)\n", connectionHelperClass);
                body.add(".dataFetcher(\"pageInfo\", $T::pageInfo))", connectionHelperClass);
                body.unindent();

                body.add("\n.type($T.newTypeWiring($S)\n", TYPE_WIRING, cw.edgeTypeName());
                body.indent();
                body.add(".dataFetcher(\"node\", $T::edgeNode)\n", connectionHelperClass);
                body.add(".dataFetcher(\"cursor\", $T::edgeCursor))", connectionHelperClass);
                body.unindent();
            }

            // Nested-type wiring: one TypeRuntimeWiring per plain object type reached through a
            // NestingField. The nested type has no *Fetchers class — its data fetchers come from
            // TypeFetcherGenerator.buildWiringEntry applied to each classified nested field.
            for (var ntw : nestedTypeWirings) {
                body.add("\n.type($T.newTypeWiring($S)", TYPE_WIRING, ntw.nestedTypeName());
                body.indent();
                for (var nf : ntw.fields()) {
                    body.add(TypeFetcherGenerator.buildWiringEntry(
                        nf, null, ntw.representativeParentTable(), null));
                }
                body.add(")");
                body.unindent();
            }

            body.add(";\n");
            body.unindent();
        }

        var buildMethod = MethodSpec.methodBuilder("build")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(builderType)
            .addCode(body.build())
            .build();

        return TypeSpec.classBuilder("GraphitronWiring")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(buildMethod)
            .build();
    }

    /** Backward-compatible overload for callers that don't have connection wiring info. */
    public static TypeSpec generate(List<String> fetcherClassNames) {
        return generate(fetcherClassNames, List.of(), List.of());
    }

    /** Backward-compatible overload for callers without nested-type wiring info. */
    public static TypeSpec generate(List<String> fetcherClassNames, List<ConnectionWiring> connectionWirings) {
        return generate(fetcherClassNames, connectionWirings, List.of());
    }
}
