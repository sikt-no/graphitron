package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.rewrite.schema.DeclaredDirectives;

import java.util.Set;

/**
 * Name-level registry for schema directives, used by the Commit B emitters to decide whether a
 * directive application on a schema element should reach the programmatic {@code GraphQLSchema}
 * ("survivor") or be consumed by the generator and dropped ("generator-only").
 *
 * <p>A directive is generator-only iff it is declared in Graphitron's own
 * {@code directives.graphqls}: the set is derived from that resource via
 * {@link DeclaredDirectives}, never hand-maintained, following the
 * {@link no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes} precedent. A survivor is any
 * directive name that is not in the generator-only set: this covers Apollo Federation directives
 * and any user-declared custom directive (including the built-in {@code @deprecated}).
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
     * Graphitron's own directive names, derived via {@link DeclaredDirectives#names()} from the
     * bundled {@code directives.graphqls}. Every name here is read by the rewrite classifier at
     * build time; none of them has runtime meaning in the emitted schema. Adding a new
     * generator-only directive means declaring it in {@code directives.graphqls}, which the
     * classifier requires anyway; membership here follows automatically.
     */
    public static final Set<String> GENERATOR_ONLY_DIRECTIVES = DeclaredDirectives.names();

    /**
     * Returns {@code true} when an application of {@code directiveName} should reach the
     * programmatic schema. A directive survives if its name is not in
     * {@link #GENERATOR_ONLY_DIRECTIVES}.
     */
    public static boolean isSurvivor(String directiveName) {
        return !GENERATOR_ONLY_DIRECTIVES.contains(directiveName);
    }
}
