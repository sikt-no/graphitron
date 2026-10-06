package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.TestRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_SPELLED_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SEPARATE_FETCH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rule that reaches one coordinate by two routes answers once: what the separate-fetch relation
 * states is that the fetch is its own, not how many ways it came to be.
 *
 * <p>The route here is a handed parent's child whose spelling two schemas both declare, so the
 * child's binding has two candidate tables and the rule for a handed parent meets it twice. Java rather
 * than a fact document because a document cannot vary its catalog: every document is captured
 * against the main jOOQ package, and one table name in two schemas is the multischema package's.
 * Capture takes the catalog as an argument either way, so the gatherers run here as they do there.
 * The rest of what this relation answers is stated in {@code facts/separate-fetch.graphqls} and
 * {@code facts/separate-fetch-renamed-root.graphqls}.
 */
class SeparateFetchRuleTest {

    @TempDir
    Path tmp;

    private static final String GRAPH = CapturedStore.GRAPH;

    private static final String MULTISCHEMA = "no.sikt.graphitron.rewrite.multischemafixture";

    private static final String SDL = """
        type Event @table(name: "event") { name: String }

        type Payload {
            label: String
            event: Event
        }

        type Query {
            summaries: [Payload!]!
                @service(service: {className: "no.sikt.graphitron.rewrite.test.services.FilmKeySummaryService", method: "summaries"})
        }
        """;

    @Test
    @DisplayName("an ambiguously bound child of a handed parent splits once, not once per table")
    void aRuleThatReachesACoordinateTwiceStillAnswersOnce() {
        var ctx = TestRunContext.of();
        var jooq = new JooqCatalog(MULTISCHEMA, ctx.codegenLoader());
        try (var captured = CapturedStore.ofCatalogWith(tmp, GRAPH, SDL, jooq, List.of(),
                ClasspathCorpus.entries())) {
            var t = GRAPHITRON_SPELLED_TABLE;
            assertThat(captured.dsl().select(t.TABLE_SCHEMA).from(t)
                    .where(t.GRAPH_NAME.eq(GRAPH), t.SPELLING.eq("event")).fetch(t.TABLE_SCHEMA))
                .as("the premise: the child's spelling has two candidate tables")
                .containsExactlyInAnyOrder("multischema_a", "multischema_b");
            var f = INTENT_FIELD_SEPARATE_FETCH;
            assertThat(captured.dsl().selectFrom(f).where(f.GRAPH_NAME.eq(GRAPH))
                    .fetch(r -> r.getTypeName() + "." + r.getFieldName() + "=" + r.getRule()))
                .as("the ambiguity is a fact about the binding, not a second fetch")
                .containsExactlyInAnyOrder(
                    "Query.summaries=ROOT_OPERATION",
                    "Payload.event=RECORD_HANDED_PARENT");
        }
    }
}
