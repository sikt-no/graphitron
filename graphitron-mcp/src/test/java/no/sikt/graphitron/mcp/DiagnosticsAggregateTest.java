package no.sikt.graphitron.mcp;

import graphql.language.SourceLocation;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.diagnostics.RejectionFacts;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code diagnostics.aggregate} invariant pins, every one asserted on tool answers rather
 * than on the engine's internals, so a residue family later migrating to its own derivation arm
 * is invisible here. The main fixture is an SDL schema run through the real pipeline into a
 * bootstrapped store ({@link StoreBackedBuild}); the cardinality and canonical-render pins need
 * populations an SDL fixture cannot cheaply produce (hundreds of distinct groups, mirrored
 * directive orders), so those write hand-built errors through the production residue loader
 * into an in-memory store, which is the loader's own contract, and still assert only on what
 * the tools answer.
 */
class DiagnosticsAggregateTest {

    /**
     * Produces, through the real pipeline: three unresolved-column errors (two sharing the
     * attempt {@code badOne} across two types), a stack of lint findings including the
     * whole-build {@code no-session-state} finding that carries no location at all, and the
     * rule-less {@code @table}-on-input advisory.
     */
    private static final String SDL = """
        type Film @table(name: "film") {
          original_language_id: Int
          badOne: Int
          badTwo: Int
        }
        type Actor @table(name: "actor") {
          badOne: Int
        }
        input FilmInput @table(name: "film") {
          film_id: Int
        }
        type Query {
          film(where: FilmInput): Film
          actor: Actor
        }
        """;

    @TempDir
    Path tmp;

    @Test
    void zeroArgumentCallIsTheTriagePresetAndItsCountsSumToTheTotal() {
        try (var build = StoreBackedBuild.run(tmp, "aggregate-preset", SDL)) {
            var result = aggregate(build.handle(), build.workspace.snapshot(), Map.of());
            var structured = structured(result);

            assertThat(structured.get("groupBy")).isEqualTo(List.of("actionable", "kind"));
            var groups = groups(structured);
            assertThat(groups).isNotEmpty();
            assertThat(groups).allSatisfy(group -> {
                var key = key(group);
                assertThat(key).containsKey("actionable").containsKey("kind");
                assertThat((Integer) group.get("count")).isPositive();
            });
            assertThat(shownSum(groups) + longOf(structured.get("elidedCount")))
                .as("group counts plus the elided remainder sum to the row count")
                .isEqualTo(longOf(structured.get("totalDiagnostics")));

            // The headline is the actionable / deferred binary, stated in the first line.
            assertThat(firstLine(result)).contains("actionable");
            assertThat(structured).containsKeys("snapshotAvailability", "elidedGroups");
        }
    }

    @Test
    void aggregateAndDrillDownAgreeOnEveryGroupsCount() {
        try (var build = StoreBackedBuild.run(tmp, "aggregate-parity", SDL)) {
            var structured = structured(aggregate(build.handle(), build.workspace.snapshot(),
                Map.of("groupBy", List.of("attemptKind", "attempt"))));
            var groups = groups(structured);
            assertThat(groups).isNotEmpty();
            // The two-type badOne attempt proves the group key spans coordinates.
            assertThat(groups).anySatisfy(group -> {
                assertThat(key(group)).containsEntry("attempt", "badOne");
                assertThat(group.get("count")).isEqualTo(2);
                assertThat(examples(group)).containsExactly("Actor.badOne", "Film.badOne");
            });

            for (var group : groups) {
                var args = new LinkedHashMap<String, Object>();
                args.put("where", key(group));
                args.put("limit", 10_000);
                var drilled = structured(DiagnosticsTool.diagnosticsResult(
                    build.handle(), build.workspace.snapshot(), args));
                assertThat(entries(drilled))
                    .as("filtering diagnostics to the group key %s returns exactly that group's "
                        + "count; the two tools share one where translation", key(group))
                    .hasSize((Integer) group.get("count"));
            }
        }
    }

    @Test
    void truncationReportsTheElidedGroupsAndTheirCombinedCount() {
        try (var build = StoreBackedBuild.run(tmp, "aggregate-truncation", SDL)) {
            var limited = structured(aggregate(build.handle(), build.workspace.snapshot(),
                Map.of("groupBy", List.of("coordinate"), "limit", 1)));
            assertThat(groups(limited)).hasSize(1);
            int totalGroups = (Integer) limited.get("totalGroups");
            assertThat(totalGroups).isGreaterThan(1);
            assertThat(limited.get("elidedGroups")).isEqualTo(totalGroups - 1);
            assertThat(shownSum(groups(limited)) + longOf(limited.get("elidedCount")))
                .isEqualTo(longOf(limited.get("totalDiagnostics")));

            // A minCount past every group elides everything, and the accounting still balances:
            // an empty aggregate over a broken schema never reads as a clean one.
            var folded = structured(aggregate(build.handle(), build.workspace.snapshot(),
                Map.of("groupBy", List.of("coordinate"), "minCount", 999)));
            assertThat(groups(folded)).isEmpty();
            assertThat(folded.get("elidedGroups")).isEqualTo(folded.get("totalGroups"));
            assertThat(longOf(folded.get("elidedCount"))).isEqualTo(longOf(folded.get("totalDiagnostics")));
        }
    }

    @Test
    void rowsWithoutACoordinateOrFileFormAStatedAbsentBucket() {
        try (var build = StoreBackedBuild.run(tmp, "aggregate-absent", SDL)) {
            // Warnings carry no coordinate: they group into one null-keyed bucket instead of
            // dropping out of the totals.
            var byCoordinate = structured(aggregate(build.handle(), build.workspace.snapshot(),
                Map.of("groupBy", List.of("coordinate"))));
            assertThat(groups(byCoordinate)).anySatisfy(group -> {
                var key = key(group);
                assertThat(key).containsKey("coordinate");
                assertThat(key.get("coordinate")).isNull();
                assertThat((Integer) group.get("count")).isGreaterThan(1);
            });
            assertThat(shownSum(groups(byCoordinate)) + longOf(byCoordinate.get("elidedCount")))
                .isEqualTo(longOf(byCoordinate.get("totalDiagnostics")));

            // The whole-build no-session-state finding carries no location either, so file gets
            // the same stated bucket, and the null-safe where reads it back as a drill-down.
            var byFile = structured(aggregate(build.handle(), build.workspace.snapshot(),
                Map.of("groupBy", List.of("file"))));
            var absent = groups(byFile).stream()
                .filter(group -> key(group).get("file") == null)
                .findFirst().orElseThrow();
            var where = new LinkedHashMap<String, Object>();
            where.put("file", null);
            var args = new LinkedHashMap<String, Object>();
            args.put("where", where);
            var drilled = structured(DiagnosticsTool.diagnosticsResult(
                build.handle(), build.workspace.snapshot(), args));
            assertThat(entries(drilled)).hasSize((Integer) absent.get("count"));
        }
    }

    @Test
    void groupCardinalityStaysUnderTheStatedCapWithHonestElision() {
        try (var store = GraphitronModelStore.open()) {
            var loc = new SourceLocation(1, 1, "/s.graphqls");
            var errors = new ArrayList<ValidationError>();
            for (int i = 0; i < 250; i++) {
                errors.add(ValidationError.forField("T.f" + i,
                    Rejection.unknownColumn("column could not be resolved", "c" + i, List.of()), loc));
            }
            var handle = volumeHandle(store, errors);

            var args = Map.<String, Object>of("groupBy", List.of("attempt"), "limit", 10_000);
            var structured = structured(aggregate(handle, LspSchemaSnapshot.unavailable(), args));
            var groups = groups(structured);
            assertThat(groups)
                .as("however large the requested limit, the group count stays under the stated cap")
                .hasSize(DiagnosticFacets.MAX_GROUP_LIMIT);
            assertThat(structured.get("elidedGroups"))
                .isEqualTo(250 - DiagnosticFacets.MAX_GROUP_LIMIT);
            assertThat(shownSum(groups) + longOf(structured.get("elidedCount"))).isEqualTo(250L);
        }
    }

    @Test
    void directivesGroupOnTheCanonicalSortedRenderSoClaimOrderCannotSplitAGroup() {
        try (var store = GraphitronModelStore.open()) {
            var loc = new SourceLocation(1, 1, "/s.graphqls");
            var handle = volumeHandle(store, List.of(
                ValidationError.forType("A",
                    Rejection.directiveConflict(List.of("splitQuery", "routine"), "conflict"), loc),
                ValidationError.forType("B",
                    Rejection.directiveConflict(List.of("routine", "splitQuery"), "conflict"), loc)));

            var structured = structured(aggregate(handle, LspSchemaSnapshot.unavailable(),
                Map.of("groupBy", List.of("directives"))));
            assertThat(groups(structured)).singleElement().satisfies(group -> {
                assertThat(key(group)).containsEntry("directives", "routine,splitQuery");
                assertThat(group.get("count")).isEqualTo(2);
            });
        }
    }

    @Test
    void anUnknownDimensionIsRefusedWithTheFullVocabulary() {
        try (var store = GraphitronModelStore.open()) {
            var handle = volumeHandle(store, List.of());
            var result = DiagnosticFacets.aggregateResult(handle, LspSchemaSnapshot.unavailable(),
                Map.of("groupBy", List.of("nope")));
            assertThat(result.isError()).isTrue();
            String message = firstLine(result);
            assertThat(message).contains("unknown dimension 'nope'");
            assertThat(message)
                .as("the refusal carries the closed vocabulary, so the retry needs no second guess")
                .contains("attemptKind").contains("directory");
        }
    }

    // ---- helpers ----

    private McpSchema.CallToolResult aggregate(
        GraphitronMcpServer.StoreHandle handle, LspSchemaSnapshot snapshot, Map<String, Object> args
    ) {
        var result = DiagnosticFacets.aggregateResult(handle, snapshot, args);
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        return result;
    }

    /** Writes {@code errors} through the production residue loader and hands back the read handle. */
    private GraphitronMcpServer.StoreHandle volumeHandle(
        GraphitronModelStore store, List<ValidationError> errors
    ) {
        new RejectionFacts(store.dsl(), new FactCapture.GraphIdentity("volume", tmp)).write(errors);
        return new GraphitronMcpServer.StoreHandle(store.dsl(), "volume");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups(Map<String, Object> structured) {
        return (List<Map<String, Object>>) structured.get("groups");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> structured) {
        return (List<Map<String, Object>>) structured.get("diagnostics");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> key(Map<String, Object> group) {
        return (Map<String, Object>) group.get("key");
    }

    @SuppressWarnings("unchecked")
    private static List<String> examples(Map<String, Object> group) {
        return (List<String>) group.get("examples");
    }

    private static long shownSum(List<Map<String, Object>> groups) {
        return groups.stream().mapToLong(g -> ((Number) g.get("count")).longValue()).sum();
    }

    private static long longOf(Object value) {
        return ((Number) value).longValue();
    }

    private static String firstLine(McpSchema.CallToolResult result) {
        assertThat(result.content()).isNotEmpty();
        return ((McpSchema.TextContent) result.content().getFirst()).text().lines().findFirst().orElse("");
    }
}
