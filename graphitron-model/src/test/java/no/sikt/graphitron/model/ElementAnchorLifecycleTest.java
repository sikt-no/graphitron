package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.ElementAnchors;
import no.sikt.graphitron.model.test.CapturedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_TYPE;
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
class ElementAnchorLifecycleTest {

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
     * <p>The minted relation keeps both statements, which is what lets a reader ask which
     * applications contribute; the anchor is one row per coordinate, because that is what a
     * coordinate is. The collapse between the two grains is the set view's, and getting it wrong
     * shows up here rather than as a duplicate somewhere downstream.
     */
    @Test
    @DisplayName("shared machinery is one element however many carriers state it")
    void sharedMachineryCollapses(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SDL)) {
            assertThat(store.dsl().fetchCount(GRAPHITRON_MINTED_TYPE,
                    GRAPHITRON_MINTED_TYPE.TYPE_NAME.eq("PageInfo")))
                .as("both carriers state it, and the minted relation keeps both")
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
     * <p>Every minted arm goes, because a carrier that stops expanding stops minting all three.
     * Removing only the types would leave fields under a type nothing derives, which is a state no
     * reading produces and so not a state worth asserting about.
     */
    @Test
    @DisplayName("an element a later reading does not derive is swept")
    void anElementNoLongerDerivedIsSwept(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SDL)) {
            store.dsl().deleteFrom(GRAPHITRON_MINTED_ARGUMENT).execute();
            store.dsl().deleteFrom(GRAPHITRON_MINTED_FIELD).execute();
            store.dsl().deleteFrom(GRAPHITRON_MINTED_TYPE).execute();

            ElementAnchors.derive(store.dsl(), CapturedStore.GRAPH,
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
