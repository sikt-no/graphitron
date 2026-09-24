package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.EmittedAnchor;
import no.sikt.graphitron.model.test.CapturedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_CARRIER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_ENTRY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an emitted element anchor lives and dies.
 *
 * <p>Its rows used to be removed by the walk's own clear, which emptied the whole partition in front
 * of a pass that refilled it. A relation whose lifetime is a property of the pass that happens to
 * run around it cannot move to another pass, so the anchor states its own: every row carries the
 * reading that wrote it, and a reading drops what it did not rewrite.
 *
 * <p>The population is the union of named sets, one view each, so what a set contains and what
 * admits it are readable without reading the others. These cases are about the lifetime rather than
 * the sets; the sets are where the admission rules are stated and read.
 */
class EmittedAnchorLifecycleTest {

    private static final String SDL = """
        type Query {
          films: [Film!]! @asConnection
          shorts: [Film!]! @asConnection
        }

        type Film @table(name: "film") { title: String }
        """;

    /**
     * Two carriers state the whole of {@code PageInfo}, and the schema emits one of it.
     *
     * <p>Both applications state it, which is what lets a reader ask which contribute; the anchor
     * is one row per coordinate, because that is what a coordinate is. The collapse between the
     * two grains is the set view's, and getting it wrong shows up here rather than as a duplicate
     * somewhere downstream.
     */
    @Test
    @DisplayName("shared machinery is one element however many carriers state it")
    void sharedMachineryCollapses(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SDL)) {
            assertThat(store.dsl().fetchCount(GRAPHITRON_CONNECTION_CARRIER))
                .as("two applications, and each states the whole of the machinery")
                .isEqualTo(2);
            assertThat(store.dsl().fetchCount(GRAPHITRON_ELEMENT,
                    GRAPHITRON_ELEMENT.COORDINATE.eq("PageInfo")))
                .as("and the schema emits one")
                .isEqualTo(1);
        }
    }

    /**
     * An anchor the next reading does not derive is one the corpus stopped emitting, and the stamp
     * is what tells the two readings apart.
     *
     * <p>The application is what goes, and every arm goes with it: a carrier that stops expanding
     * stops minting its type, its fields and its arguments at once, because all three are derived
     * from the one row rather than stored beside it.
     */
    @Test
    @DisplayName("an element a later reading does not derive is swept")
    void anElementNoLongerDerivedIsSwept(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SDL)) {
            // The applications go, which is a corpus that stopped asking for the expansion. There
            // are no minted rows to remove beside them: what a carrier mints is derived from the
            // application, so removing the application is the whole of it.
            store.dsl().deleteFrom(GRAPHITRON_CONNECTION_ENTRY).execute();

            EmittedAnchor.derive(store.dsl(), CapturedStore.GRAPH,
                LocalDateTime.of(2030, 1, 1, 0, 0));

            assertThat(store.dsl().fetchCount(GRAPHITRON_ELEMENT,
                    GRAPHITRON_ELEMENT.COORDINATE.eq("PageInfo")))
                .as("nothing mints it now, so the later reading does not write it and it goes")
                .isZero();
            assertThat(store.dsl().fetchCount(GRAPHITRON_ELEMENT,
                    GRAPHITRON_ELEMENT.COORDINATE.eq("Film")))
                .as("while an element the reading still derives stays")
                .isEqualTo(1);
        }
    }
}
