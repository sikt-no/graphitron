package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.EmittedAnchor;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.derive.ArgMappingCandidates;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.test.CapturedStore.GRAPH;
import static no.sikt.graphitron.model.test.CapturedStore.withCapturedStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds four capture-cadence statements to the access path that keeps them off a nested loop. Each
 * joined a relation on columns no index served, so H2 compared every pair of rows or re-ran a derived
 * table once per outer row; on a large schema the four cost about a minute per capture between them.
 *
 * <p>The instrument is H2's plan, read off {@code EXPLAIN} on the statement production actually ran:
 * the writers are run again through a {@link DSLContext} that records what it executes, so the test
 * asserts on the SQL the writer builds rather than on a copy of it. What is asserted is one named
 * access path per statement, not a whole plan, so a change elsewhere in the plan does not fail it.
 * Plain {@code EXPLAIN} rather than {@code EXPLAIN ANALYZE}: the access path is the property, and
 * analysing an insert would execute it a second time.
 *
 * <p>None of this shows the time saved. A seeded store is far too small for that, and the sis run
 * recorded on the change is the evidence for the goal; this holds the shape that bought it.
 */
class UnservedJoinPlanTest {

    /**
     * Directive applications with arguments for the position join, an argument and an input field
     * of input-object type for the argMapping carrier to descend, and arguments for the argument
     * grain's upsert.
     */
    private static final String SDL = """
        type Query {
          films(filter: FilmFilter, title: String @deprecated(reason: "old")): [Film]
        }
        type Film {
          title: String @deprecated(reason: "renamed")
          rating: String
        }
        input FilmFilter {
          title: String
          nested: Inner
        }
        input Inner {
          year: Int
        }
        """;

    private static final List<String> CARRIER_ALIASES = List.of(
        "\"carrier_argument_element\"", "\"carrier_argument\"",
        "\"carrier_field_element\"", "\"carrier_field\"");

    @Test
    @DisplayName("an argument entry seeks its application by the at sign's position")
    void argumentEntriesSeekTheirApplication(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var plans = plansOf(dsl, "merge into \"PUBLIC\".\"GRAPHQL_DIRECTIVE_APPLICATION_ARG\"");
            assertThat(plans).hasSize(1);
            assertThat(plans.getFirst())
                .as("the position is no prefix of the application's key, so without its own index "
                    + "every argument entry scans every application")
                .contains("GRAPHQL_DIRECTIVE_APPLICATION_POSITION_IX");
        });
    }

    @Test
    @DisplayName("the argMapping carrier is reached by key seeks, never through a derived table")
    void theCarrierIsReachedByKeySeeks(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var plans = plansOf(dsl, "GRAPHITRON_ARGMAPPING_CANDIDATE").stream()
                .filter(plan -> plan.contains("\"carrier_argument_element\""))
                .toList();
            assertThat(plans)
                .as("both seeds, the contested mark and every expansion pass read the carrier")
                .hasSizeGreaterThanOrEqualTo(4);
            for (var plan : plans) {
                assertThat(plan)
                    .as("H2 seeks into neither a union nor a derived table, so a carrier spelled as "
                        + "one is re-run whole once per candidate row")
                    .doesNotContain("UNION")
                    .doesNotContain("\"carrier\"");
                for (var alias : CARRIER_ALIASES) {
                    assertThat(accessPathOf(plan, alias))
                        .as("%s is joined on a key", alias)
                        .doesNotContain("tableScan")
                        .containsAnyOf("COORDINATE =", "FIELD_NAME =");
                }
            }
            var readers = plans.stream()
                .filter(plan -> plan.contains("JOIN \"PUBLIC\".\"GRAPHQL_ELEMENT\""))
                .toList();
            assertThat(readers)
                .as("the expansion passes and the contested mark arrive with a candidate's coordinate")
                .isNotEmpty();
            for (var plan : readers) {
                assertThat(accessPathOf(plan, "\"PUBLIC\".\"GRAPHQL_ELEMENT\""))
                    .as("a reader holding a coordinate seeks the element by it")
                    .contains("COORDINATE =");
            }
        });
    }

    @Test
    @DisplayName("the field and argument upserts match their target on the primary key alone")
    void upsertsKeyOnThePrimaryKey(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            for (var target : List.of("GRAPHQL_FIELD_ELEMENT", "GRAPHITRON_FIELD",
                    "GRAPHITRON_ARGUMENT")) {
                var plans = plansOf(dsl, "merge into \"PUBLIC\".\"" + target + "\"");
                assertThat(plans).as(target).hasSize(1);
                var plan = plans.getFirst();
                assertThat(plan.substring(0, plan.indexOf("USING")))
                    .as("%s: with the coordinate beside the key the match is key-or-coordinate, "
                        + "which no index serves and which scans the target per source row", target)
                    .contains("PRIMARY_KEY")
                    .doesNotContain("tableScan");
                var on = plan.substring(plan.lastIndexOf("\nON "), plan.indexOf("WHEN MATCHED"));
                assertThat(on).as("%s: the match condition", target).doesNotContain("COORDINATE");
            }
        });
    }

    /**
     * The plans of the statements, among those the capture-cadence writers run, whose inlined SQL
     * contains {@code marker}.
     */
    private static List<String> plansOf(DSLContext dsl, String marker) {
        var statements = new ArrayList<String>();
        var recording = DSL.using(dsl.configuration().derive(new DefaultExecuteListenerProvider(
            new ExecuteListener() {
                @Override
                public void executeStart(ExecuteContext ctx) {
                    if (ctx.query() != null) {
                        statements.add(dsl.renderInlined(ctx.query()));
                    }
                }
            })));
        var at = LocalDateTime.now();
        GraphQLAstCapture.anchor(recording, GRAPH, at);
        EmittedAnchor.derive(recording, GRAPH, at);
        ArgMappingCandidates.derive(recording, GRAPH);
        return statements.stream()
            .filter(sql -> sql.contains(marker))
            .map(sql -> dsl.fetch("EXPLAIN " + sql).getFirst().get(0, String.class))
            .toList();
    }

    /** The index comment H2 prints under the join of {@code joined}: its access path. */
    private static String accessPathOf(String plan, String joined) {
        var lines = plan.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            // A table joined under its own name, or under an alias the line ends with.
            if (line.contains("JOIN " + joined)
                    || line.contains("JOIN \"PUBLIC\".") && line.endsWith(" " + joined)) {
                var comment = new StringBuilder();
                for (int j = i + 1; j < lines.size(); j++) {
                    comment.append(lines.get(j)).append('\n');
                    if (lines.get(j).contains("*/")) {
                        break;
                    }
                }
                return comment.toString();
            }
        }
        throw new AssertionError("no join of " + joined + " in\n" + plan);
    }
}
