package no.sikt.graphitron.model;

import no.sikt.graphitron.model.schema.federation.FederationSpec;
import no.sikt.graphitron.model.test.CapturedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_LINK;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_LINK_IMPORT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configured opt-in, which no fact document can state, a document configuring no tag: a graph
 * whose input carries a tag and whose documents apply no federation {@code @link} is federated by its
 * configuration and imports {@code @tag} alone, and an authored link stands the configured one down.
 * The authored opt-in and its sweep are the {@code federation-link} fact documents'.
 */
class FederationLinkTest {

    private static final String SDL = """
        type Query { film: String }
        """;

    private static final String LINKED = SDL + """
        extend schema @link(url: "%s", import: ["@tag"])
        """.formatted(FederationSpec.URL);

    @Test
    @DisplayName("a tag configured with no federation link opts the graph in, importing @tag")
    void aConfiguredTagOptsIn(@TempDir Path tmp) {
        try (var store = CapturedStore.tagged(tmp, SDL, "catalog")) {
            var link = GRAPHITRON_FEDERATION_LINK;
            assertThat(store.dsl().select(link.ORIGIN, link.VERSION, link.SOURCE_NAME).from(link)
                    .fetch(r -> r.value1() + " " + r.value2() + " " + r.value3()))
                .containsExactly("CONFIGURED null null");
            var imports = GRAPHITRON_FEDERATION_LINK_IMPORT;
            assertThat(store.dsl().select(imports.NAME).from(imports).fetch(imports.NAME))
                .containsExactly("@tag");
        }
    }

    @Test
    @DisplayName("an authored federation link stands the configured one down")
    void anAuthoredLinkStandsItDown(@TempDir Path tmp) {
        try (var store = CapturedStore.tagged(tmp, LINKED, "catalog")) {
            var link = GRAPHITRON_FEDERATION_LINK;
            assertThat(store.dsl().select(link.ORIGIN, link.VERSION).from(link)
                    .fetch(r -> r.value1() + " " + r.value2()))
                .containsExactly("AUTHORED " + FederationSpec.URL.substring(FederationSpec.SPEC_PREFIX.length()));
        }
    }

    @Test
    @DisplayName("a graph with neither is not federated")
    void neither(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SDL)) {
            assertThat(store.dsl().fetchCount(GRAPHITRON_FEDERATION_LINK)).isZero();
        }
    }
}
