package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R281 check for {@link QueryViewRenderer}: a query selects a closure of the annotated corpus, and the
 * renderer regenerates real SDL for it, pruned to the selected fields (job 3) with the test-only
 * directives stripped (job 2) but real Graphitron directives retained.
 */
@PipelineTier
class QueryViewRendererTest {

    private static final String FIXTURE = """
        type Query {
          film: Film @classified(producer: [Query], mapping: Table)
          actor: Actor @classified(producer: [Query], mapping: Table)
        }

        type Film @table(name: "film") @classifiedType(as: TableType) {
          title: String @classified(producer: [], mapping: Column)
          releaseYear: Int @field(name: "release_year") @classified(producer: [], mapping: Column)
        }

        type Actor @table(name: "actor") {
          firstName: String @field(name: "first_name") @classified(producer: [], mapping: Column)
        }
        """;

    @Test
    void rendersSelectedFieldsWithTestDirectivesStripped() {
        String out = QueryViewRenderer.render(FIXTURE, "{ film { releaseYear } }");

        // Job 1 (import what's relevant): the selected closure (Query entry + Film) is rendered, and a
        // real Graphitron type directive (@table) survives.
        assertThat(out).contains("type Query");
        assertThat(out).contains("type Film");
        assertThat(out).contains("releaseYear");
        assertThat(out).contains("@table");

        // Job 2 (strip the test directives): the internal directives never print, but a real
        // Graphitron directive on a rendered field (@field) survives regeneration.
        assertThat(out).doesNotContain("@classified");
        assertThat(out).doesNotContain("classifiedType");
        assertThat(out).contains("@field");

        // Job 3 (bound the snippet): a sibling field of a rendered type (Film.title) is pruned to the
        // selection, the unselected root field (Query.actor) is dropped, and a sibling type the query
        // never reached (Actor) is not dragged in.
        assertThat(out).doesNotContain("title");
        assertThat(out).doesNotContain("actor");
        assertThat(out).doesNotContain("type Actor");
    }
}
