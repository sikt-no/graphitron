package no.sikt.graphitron.rewrite.generators.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void federationSet_containsFederationV2Directives() {
        assertThat(SchemaDirectiveRegistry.FEDERATION_DIRECTIVES).contains(
            "key", "external", "provides", "requires",
            "shareable", "override", "tag", "inaccessible",
            "composeDirective", "interfaceObject", "extends"
        );
    }

    @Test
    void survivorSets_andGeneratorOnlySet_areDisjoint() {
        SchemaDirectiveRegistry.FEDERATION_DIRECTIVES.forEach(name ->
            assertThat(SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES)
                .as("federation directive @%s must not also be generator-only", name)
                .doesNotContain(name)
        );
    }

    @Test
    void isSurvivor_trueForFederationDirectives() {
        SchemaDirectiveRegistry.FEDERATION_DIRECTIVES.forEach(name ->
            assertThat(SchemaDirectiveRegistry.isSurvivor(name))
                .as("federation directive @%s should be a survivor", name)
                .isTrue()
        );
    }

    @Test
    void isSurvivor_trueForUnknownCustomDirective() {
        assertThat(SchemaDirectiveRegistry.isSurvivor("myAppDirective")).isTrue();
        assertThat(SchemaDirectiveRegistry.isSurvivor("deprecated")).isTrue();
    }

    @Test
    void isSurvivor_falseForGeneratorOnlyDirectives() {
        SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES.forEach(name ->
            assertThat(SchemaDirectiveRegistry.isSurvivor(name))
                .as("generator-only directive @%s must not survive", name)
                .isFalse()
        );
    }

    @Test
    void isFederation_matchesTheFederationSet() {
        assertThat(SchemaDirectiveRegistry.isFederation("key")).isTrue();
        assertThat(SchemaDirectiveRegistry.isFederation("external")).isTrue();
        assertThat(SchemaDirectiveRegistry.isFederation("deprecated")).isFalse();
        assertThat(SchemaDirectiveRegistry.isFederation("table")).isFalse();
    }
}
