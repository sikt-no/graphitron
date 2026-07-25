package no.sikt.graphitron.rewrite.generators.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class SchemaDirectiveRegistryTest {

    /**
     * Pins the generator-only set derived from {@code directives.graphqls}. The set is derived,
     * not hand-maintained; this test exists so an edit to {@code directives.graphqls} that adds
     * or removes a directive changes the survivor filter consciously rather than silently. It
     * would also catch graphql-java starting to inject built-in directive definitions
     * ({@code @deprecated}, {@code @skip}, ...) into the parsed registry, which must never
     * enter this set.
     */
    @Test
    void generatorOnlySet_pinsTheDeclaredDirectiveNames() {
        assertThat(SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES).containsExactlyInAnyOrder(
            "asConnection", "asFacet", "condition", "defaultOrder", "discriminate",
            "discriminator", "enum", "error", "experimental_constructType", "externalField",
            "field", "index", "lookupKey", "multitableReference", "mutation", "node",
            "nodeId", "notGenerated", "order", "orderBy", "pivot", "record", "reference",
            "referenceFor", "routine", "scalarType", "service", "sourceRow", "splitQuery",
            "table", "tenantFanOut"
        );
    }

    @Test
    void isSurvivor_trueForUnknownCustomDirective() {
        assertThat(SchemaDirectiveRegistry.isSurvivor("myAppDirective")).isTrue();
        assertThat(SchemaDirectiveRegistry.isSurvivor("deprecated")).isTrue();
    }

    @Test
    void isSurvivor_trueForFederationDirectives() {
        // Sanity check that the dropped FEDERATION_DIRECTIVES set's contents would still survive
        // the survivor filter (none of them is a Graphitron generator-only directive). Spot-check
        // the most load-bearing ones; the full federation directive list is owned by federation-jvm.
        assertThat(SchemaDirectiveRegistry.isSurvivor("key")).isTrue();
        assertThat(SchemaDirectiveRegistry.isSurvivor("shareable")).isTrue();
        assertThat(SchemaDirectiveRegistry.isSurvivor("tag")).isTrue();
        assertThat(SchemaDirectiveRegistry.isSurvivor("external")).isTrue();
    }

    @Test
    void isSurvivor_falseForGeneratorOnlyDirectives() {
        SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES.forEach(name ->
            assertThat(SchemaDirectiveRegistry.isSurvivor(name))
                .as("generator-only directive @%s must not survive", name)
                .isFalse()
        );
    }
}
