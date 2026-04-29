package no.sikt.graphitron.rewrite.generators.util;

import java.util.Set;

/**
 * Name-level registry for schema directives, used by the Commit B emitters to decide whether a
 * directive application on a schema element should reach the programmatic {@code GraphQLSchema}
 * ("survivor") or be consumed by the generator and dropped ("generator-only").
 *
 * <p>Generator-only directives are the Graphitron build-time directives enumerated in
 * {@link no.sikt.graphitron.rewrite.BuildContext} constants. A survivor is any directive name
 * that is not in the generator-only set: this covers Apollo Federation directives and any
 * user-declared custom directive (including the built-in {@code @deprecated}).
 *
 * <p>The registry does not reason about <em>definitions</em>; it only classifies names. The
 * {@code GraphitronSchema} assembler is responsible for collecting survivor directive
 * definitions from the input {@code TypeDefinitionRegistry} and calling
 * {@code schemaBuilder.additionalDirective(...)} for each. The per-type emitters
 * ({@code <TypeName>Type}) consult {@link #isSurvivor(String)} to decide whether to translate
 * an application onto the corresponding graphql-java builder.
 */
public final class SchemaDirectiveRegistry {

    private SchemaDirectiveRegistry() {}

    /**
     * Graphitron's own directives. Every name here is defined in
     * {@code graphitron-common/src/main/resources/directives.graphqls} and read by the
     * rewrite classifier; none of them has runtime meaning in the emitted schema.
     *
     * <p>Kept in sync with the {@code DIR_*} constants in
     * {@link no.sikt.graphitron.rewrite.BuildContext} plus the SDL-declared directives that
     * the classifier reads opportunistically ({@code @enum}, {@code @index}, {@code @order},
     * {@code @experimental_constructType}). Adding a new generator-only directive means
     * adding both a {@code DIR_*} constant and an entry here.
     */
    public static final Set<String> GENERATOR_ONLY_DIRECTIVES = Set.of(
        "table",
        "record",
        "discriminate",
        "discriminator",
        "node",
        "notGenerated",
        "multitableReference",
        "nodeId",
        "field",
        "reference",
        "error",
        "tableMethod",
        "defaultOrder",
        "splitQuery",
        "service",
        "externalField",
        "lookupKey",
        "orderBy",
        "condition",
        "mutation",
        "asConnection",
        "enum",
        "index",
        "order",
        "experimental_constructType"
    );

    /**
     * Returns {@code true} when an application of {@code directiveName} should reach the
     * programmatic schema. A directive survives if its name is not in
     * {@link #GENERATOR_ONLY_DIRECTIVES}.
     */
    public static boolean isSurvivor(String directiveName) {
        return !GENERATOR_ONLY_DIRECTIVES.contains(directiveName);
    }
}
