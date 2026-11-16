package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.CapturedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import no.sikt.graphitron.model.test.TestRunContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_CANDIDATE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The argMapping candidates at a circular input type, checked against the capture that writes them.
 * The rest of the relation is stated by the fact documents {@code scenarios/argmapping-candidate-*};
 * a circular input type has no document, the generator refusing it and the store stating no defect
 * for it, so these two cases stay inline.
 */
class ArgMappingCandidateTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    private static final String CYCLE = """
        input A { b: B }
        input B { a: A }
        type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
        type Query { films(filter: A): [Film!]! }
        """;

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the element that closes a cycle keeps its row and says so")
    void theElementThatClosesACycleSaysSo() {
        try (var store = CapturedStore.ofCatalog(tmp, CYCLE, jooq())) {
            assertThat(at(store.dsl(), "Query.films"))
                .as("the closing element is nameable and so has a row, marked as what it is, and"
                    + " nothing below it is written; a reader tells it from a leaf by the column"
                    + " rather than by the absence of children")
                .containsExactlyInAnyOrder(
                    "filter|<none>|filter|A|0|false|false",
                    "filter.b|filter|b|B|1|false|false",
                    "filter.b.a|filter.b|a|A|2|true|false");
        }
    }

    /**
     * The carrier's own type counts as an ancestor of the clean spelling even though no row stands
     * for it, which is what keeps the two spellings from stopping at different depths. Written out
     * because it is the one place the clean tree could have under-detected: it has no parent row to
     * walk, so the type it hangs under is joined rather than followed.
     */
    @Test
    @DisplayName("the clean spelling closes the same cycle the repeating one does")
    void bothSpellingsCloseTheSameCycle() {
        try (var store = CapturedStore.ofCatalog(tmp, CYCLE, jooq())) {
            assertThat(at(store.dsl(), "Query.films(filter:)"))
                .containsExactlyInAnyOrder(
                    "filter|<none>|filter|A|0|false|false",
                    "b|<none>|b|B|0|false|false",
                    "b.a|b|a|A|1|true|false",
                    "filter.b|filter|b|B|1|false|true",
                    "filter.b.a|filter.b|a|A|2|true|true");
        }
    }

    /** Every candidate at one coordinate, rendered so a whole population is one assertion. */
    private static List<String> at(DSLContext dsl, String coordinate) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        return dsl.selectFrom(c)
            .where(c.GRAPH_NAME.eq(GRAPH)).and(c.COORDINATE.eq(coordinate))
            .fetch(r -> r.getPath() + "|"
                + (r.getParentPath() == null ? "<none>" : r.getParentPath()) + "|"
                + r.getName() + "|" + r.getNamedType() + "|" + r.getDepth() + "|"
                + r.getClosesCycle() + "|" + r.getDeprecated());
    }

    private static CatalogInput jooq() {
        var ctx = TestRunContext.of();
        return CatalogInput.of(ctx);
    }
}
