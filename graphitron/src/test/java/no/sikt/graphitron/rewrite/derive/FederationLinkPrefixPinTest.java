package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.schema.federation.FederationSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The federation-spec url prefix has three spellings, and this is what holds the third to the other
 * two. Two are Java readers sharing {@link FederationSpec#SPEC_PREFIX}, so the compiler keeps them
 * honest. The third is a SQL literal inside {@code intent_synthesized_federation_key}, which cannot
 * bind a query parameter the way a derivation writer in Java can, so nothing but a test can notice
 * the two drifting apart.
 *
 * <p>The assertion reads the view's own stored definition rather than exercising the rule, so a
 * failure names the drift instead of surfacing as a rule that stopped firing. The behavioural half is
 * covered where the rule's cases live.
 */
@UnitTier
class FederationLinkPrefixPinTest {

    @Test
    void theViewsPrefixLiteralIsTheConstantTheJavaReadersShare() {
        try (var store = FactStores.inMemory()) {
            String definition = store.dsl()
                .resultQuery("""
                    SELECT view_definition FROM information_schema.views
                     WHERE lower(table_name) = 'intent_synthesized_federation_key'
                    """)
                .fetchSingle(0, String.class);
            assertThat(definition)
                .as("the view decides the federation opt-in by a prefix over graphitron_link.url")
                .contains(FederationSpec.SPEC_PREFIX);
        }
    }
}
