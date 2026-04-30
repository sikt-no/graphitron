package no.sikt.graphitron.rewrite.generators.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class SchemaDirectiveRegistryTest {

    @Test
    void generatorOnlySet_containsAllGraphitronDirectiveNames() {
        assertThat(SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES).contains(
            "table", "record", "discriminate", "discriminator", "node",
            "notGenerated", "multitableReference", "nodeId", "field", "reference",
            "error", "tableMethod", "defaultOrder", "splitQuery", "service",
            "externalField", "lookupKey", "orderBy", "condition", "mutation",
            "asConnection", "enum", "index", "order", "experimental_constructType"
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
