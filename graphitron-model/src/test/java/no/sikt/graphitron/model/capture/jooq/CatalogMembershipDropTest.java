package no.sikt.graphitron.model.capture.jooq;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A graph's claim on a catalog source lasts as long as its configuration names the catalog. A
 * scenario keeps its catalog between steps, so the recapture under another configuration is driven
 * here, over a store of the test's own: the same document read again under the same graph name,
 * once with no catalog and once with another one.
 *
 * <p>What the store holds for a source is a function of that source alone, so dropping the claim
 * leaves the source and its rows standing; the graph just stops seeing them.
 */
class CatalogMembershipDropTest {

    private static final String DOCUMENT = "scenarios/node-key-published";

    private static final String GRAPH = "g";

    private static final String OTHER_PACKAGE = "no.sikt.graphitron.rewrite.singleschemafixture";

    /** Nothing here is read off a classpath, and the capture is cheaper without one. */
    private static final List<ClasspathEntry> NO_CLASSPATH = List.of();

    @Test
    @DisplayName("a graph recaptured without a catalog stops claiming the catalog it read")
    void droppingTheCatalogDropsItsClaims(@TempDir Path tmp) {
        var fixture = CatalogInput.of(TestRunContext.of());
        try (var store = CapturedStore.ownStoreOfDocument(tmp, DOCUMENT, GRAPH, fixture, NO_CLASSPATH)) {
            var claimed = catalogClaims(store.dsl());
            assertThat(claimed).as("the first reading claims the fixture catalog").isNotEmpty();
            assertThat(nodes(store.dsl())).containsExactly("Keyed");

            store.andDocument(DOCUMENT, GRAPH, null, NO_CLASSPATH);

            assertThat(catalogClaims(store.dsl()))
                .as("a run that declares no catalog claims none")
                .isEmpty();
            assertThat(nodes(store.dsl()))
                .as("and the node its table made is gone with the claim")
                .isEmpty();
            assertThat(rowsUnder(store.dsl(), claimed))
                .as("the sources and their tables stay, being what the store holds for the package"
                    + " whoever reads it")
                .isPositive();
        }
    }

    @Test
    @DisplayName("a graph recaptured against another package claims that package alone")
    void changingThePackageReplacesItsClaims(@TempDir Path tmp) {
        var fixture = CatalogInput.of(TestRunContext.of());
        try (var store = CapturedStore.ownStoreOfDocument(tmp, DOCUMENT, GRAPH, fixture, NO_CLASSPATH)) {
            var first = catalogClaims(store.dsl());

            store.andDocument(DOCUMENT, GRAPH, new CatalogInput(OTHER_PACKAGE, fixture.loader()),
                NO_CLASSPATH);

            var second = catalogClaims(store.dsl());
            assertThat(second).isNotEmpty().allMatch(source -> source.startsWith(OTHER_PACKAGE));
            assertThat(second).doesNotContainAnyElementsOf(first);
            assertThat(nodes(store.dsl()))
                .as("the other catalog has no published_elsewhere, so the type binds no table here")
                .isEmpty();
        }
    }

    /** The catalog sources the graph claims. */
    private static Set<String> catalogClaims(DSLContext dsl) {
        var m = STORE_GRAPH_SOURCE;
        var s = STORE_SOURCE;
        return new TreeSet<>(dsl.select(m.SOURCE_NAME).from(m)
            .join(s).on(s.SOURCE_NAME.eq(m.SOURCE_NAME))
            .where(m.GRAPH_NAME.eq(GRAPH), s.SOURCE_KIND.eq("JOOQ_SCHEMA"))
            .fetch(m.SOURCE_NAME));
    }

    private static List<String> nodes(DSLContext dsl) {
        return dsl.select(GRAPHITRON_NODE.TYPE_NAME).from(GRAPHITRON_NODE)
            .where(GRAPHITRON_NODE.GRAPH_NAME.eq(GRAPH))
            .orderBy(GRAPHITRON_NODE.TYPE_NAME)
            .fetch(GRAPHITRON_NODE.TYPE_NAME);
    }

    private static int rowsUnder(DSLContext dsl, Set<String> sources) {
        return dsl.fetchCount(SQL_TABLE, SQL_TABLE.SOURCE_NAME.in(sources));
    }
}
