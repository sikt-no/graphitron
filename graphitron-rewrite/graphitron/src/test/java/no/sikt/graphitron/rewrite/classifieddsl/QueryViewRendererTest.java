package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R281 slice 1 prototype check for {@link QueryViewRenderer}: a query selects a closure of the
 * annotated corpus, and the renderer regenerates real SDL for it with the test-only directives
 * stripped (job 2) but real Graphitron directives retained.
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
    void rendersSelectedClosureWithTestDirectivesStripped() {
        String out = QueryViewRenderer.render(FIXTURE, "{ film { title releaseYear } }");

        // Job 1 (import what's relevant): the selected closure (Query entry + Film) is rendered.
        assertThat(out).contains("type Query");
        assertThat(out).contains("type Film");
        assertThat(out).contains("title");
        assertThat(out).contains("releaseYear");

        // Job 2 (strip the test directives): the internal directives never print, but a real
        // Graphitron directive on a rendered field (@field) survives regeneration.
        assertThat(out).doesNotContain("@classified");
        assertThat(out).doesNotContain("classifiedType");
        assertThat(out).contains("@field");

        // Job 3 (bound the snippet): a sibling type the query did not select is not dragged in.
        assertThat(out).doesNotContain("type Actor");
    }
}
