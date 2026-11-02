package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first-visit guard on {@code graphitron_occurrence}: a chain does not enter a type already on
 * it, and the occurrence that would close the cycle keeps its row and grows nothing. A schema that
 * nests a type in itself is one the classifier refuses without the store stating a defect, so its
 * cases are captured here rather than in a fact document.
 */
@PipelineTier
class OccurrenceCycleTest {

    @TempDir
    Path tmp;

    @Test
    void anInputTypeThatNestsItselfIsEnteredOncePerChain() {
        withCaptured("""
            type Film @table(name: "film") { title: String }
            input Tree {
                title: String
                children: [Tree]
            }
            type Query { films(filter: Tree): [Film!]! }
            """, dsl -> assertThat(occurrencesUnder(dsl, "Query.films(filter:)")).containsExactly(
                "Query.films(filter:) FIELD_ARGUMENT 1",
                "Query.films(filter:).children INPUT_FIELD 2",
                "Query.films(filter:).title INPUT_FIELD 2"));
    }

    @Test
    void anOutputTypeThatNestsItselfIsEnteredOncePerChain() {
        withCaptured("""
            type Film @table(name: "film") {
                title: String
                details: Details
            }
            type Details {
                length: Int
                more: Details
            }
            type Query { films: [Film!]! }
            """, dsl -> assertThat(occurrencesUnder(dsl, "Film.details")).containsExactly(
                "Film.details FIELD 0",
                "Film.details.length FIELD 1",
                "Film.details.more FIELD 1"));
    }

    /** The occurrences at or under one coordinate, one string per row, in coordinate order. */
    private static List<String> occurrencesUnder(DSLContext dsl, String coordinate) {
        var o = GRAPHITRON_OCCURRENCE;
        return dsl.select(o.COORDINATE, o.KIND, o.DEPTH)
            .from(o)
            .where(o.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(o.COORDINATE.eq(coordinate).or(o.COORDINATE.startsWith(coordinate + ".")))
            .orderBy(o.COORDINATE)
            .fetch()
            .map(row -> row.value1() + " " + row.value2() + " " + row.value3());
    }

    private void withCaptured(String sdl, Consumer<DSLContext> body) {
        try (var store = CapturedStore.ofCatalog(tmp, sdl, CatalogInput.of(testContext()))) {
            body.accept(store.dsl());
        }
    }
}
