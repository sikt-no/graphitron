package no.sikt.graphitron.rewrite.schema;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.schema.DeclaredDirectives;

@UnitTier
class DeclaredDirectivesTest {

    /**
     * Pins the directive names derived from {@code directives.graphqls}. The set is derived, not
     * hand-maintained; this test exists so an edit to {@code directives.graphqls} that adds or
     * removes a directive is made consciously rather than silently. It would also catch
     * graphql-java starting to inject built-in directive definitions ({@code @deprecated},
     * {@code @skip}, ...) into the parsed registry, which must never enter this set.
     */
    @Test
    void pinsTheDeclaredDirectiveNames() {
        assertThat(DeclaredDirectives.names()).containsExactlyInAnyOrder(
            "asConnection", "asFacet", "condition", "defaultOrder", "defaultTenant", "discriminate",
            "discriminator", "enum", "error", "experimental_constructType", "externalField",
            "field", "globalData", "index", "lookupKey", "multitableReference", "mutation", "node",
            "nodeId", "notGenerated", "order", "orderBy", "pivot", "record", "reference",
            "referenceFor", "routine", "scalarType", "service", "sourceRow", "splitQuery",
            "table", "tenant", "tenantFanOut"
        );
    }
}
