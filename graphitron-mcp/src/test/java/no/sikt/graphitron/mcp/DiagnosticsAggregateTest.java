package no.sikt.graphitron.mcp;

import graphql.language.SourceLocation;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.FactWriters.rejectionFacts;
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
            var result = aggregate(build.handle(), Map.of());
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
            var structured = structured(aggregate(
                build.handle(), Map.of("groupBy", List.of("attemptKind", "attempt"))));
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
                var drilled = structured(
                    DiagnosticsTool.diagnosticsResult(build.handle(), args));
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
            var limited = structured(aggregate(
                build.handle(), Map.of("groupBy", List.of("coordinate"), "limit", 1)));
            assertThat(groups(limited)).hasSize(1);
            int totalGroups = (Integer) limited.get("totalGroups");
            assertThat(totalGroups).isGreaterThan(1);
            assertThat(limited.get("elidedGroups")).isEqualTo(totalGroups - 1);
            assertThat(shownSum(groups(limited)) + longOf(limited.get("elidedCount")))
                .isEqualTo(longOf(limited.get("totalDiagnostics")));

            // A minCount past every group elides everything, and the accounting still balances:
            // an empty aggregate over a broken schema never reads as a clean one.
            var folded = structured(aggregate(
                build.handle(), Map.of("groupBy", List.of("coordinate"), "minCount", 999)));
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
            var byCoordinate = structured(aggregate(
                build.handle(), Map.of("groupBy", List.of("coordinate"))));
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
            var byFile = structured(aggregate(build.handle(), Map.of("groupBy", List.of("file"))));
            var absent = groups(byFile).stream()
                .filter(group -> key(group).get("file") == null)
                .findFirst().orElseThrow();
            var where = new LinkedHashMap<String, Object>();
            where.put("file", null);
            var args = new LinkedHashMap<String, Object>();
            args.put("where", where);
            var drilled = structured(DiagnosticsTool.diagnosticsResult(build.handle(), args));
            assertThat(entries(drilled)).hasSize((Integer) absent.get("count"));
        }
    }

    @Test
    void groupCardinalityStaysUnderTheStatedCapWithHonestElision() {
        try (var store = FactStores.inMemory()) {
            var loc = new SourceLocation(1, 1, "/s.graphqls");
            var errors = new ArrayList<ValidationError>();
            for (int i = 0; i < 250; i++) {
                errors.add(ValidationError.forField("T.f" + i,
                    Rejection.unknownColumn("column could not be resolved", "c" + i, List.of()), loc));
            }
            var handle = volumeHandle(store.dsl(), errors);

            var args = Map.<String, Object>of("groupBy", List.of("attempt"), "limit", 10_000);
            var structured = structured(aggregate(handle, args));
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
        try (var store = FactStores.inMemory()) {
            var loc = new SourceLocation(1, 1, "/s.graphqls");
            var handle = volumeHandle(store.dsl(), List.of(
                ValidationError.forType("A",
                    Rejection.directiveConflict(List.of("splitQuery", "routine"), "conflict"), loc),
                ValidationError.forType("B",
                    Rejection.directiveConflict(List.of("routine", "splitQuery"), "conflict"), loc)));

            var structured = structured(aggregate(handle, Map.of("groupBy", List.of("directives"))));
            assertThat(groups(structured)).singleElement().satisfies(group -> {
                assertThat(key(group)).containsEntry("directives", "routine,splitQuery");
                assertThat(group.get("count")).isEqualTo(2);
            });
        }
    }

    @Test
    void anUnknownDimensionIsRefusedWithTheFullVocabulary() {
        try (var store = FactStores.inMemory()) {
            var handle = volumeHandle(store.dsl(), List.of());
            var result = DiagnosticFacets.aggregateResult(handle, Map.of("groupBy", List.of("nope")));
            assertThat(result.isError()).isTrue();
            String message = firstLine(result);
            assertThat(message).contains("unknown dimension 'nope'");
            assertThat(message)
                .as("the refusal carries the closed vocabulary, so the retry needs no second guess")
                .contains("attemptKind").contains("directory");
        }
    }

    @Test
    void anUnknownOrderByIsRefusedWithBothOrderings() {
        try (var store = FactStores.inMemory()) {
            var handle = volumeHandle(store.dsl(), List.of());
            // A typo, a fuller sort expression, the enum-ish spelling, and a non-string: each one
            // used to mean count, which is an answer to a question the caller did not ask.
            for (Object written : List.<Object>of("cuont", "count desc", "COUNT", 5)) {
                var result = DiagnosticFacets.aggregateResult(
                    handle, Map.<String, Object>of("orderBy", written));
                assertThat(result.isError()).as("orderBy '%s' is refused", written).isTrue();
                assertThat(firstLine(result))
                    .contains("unknown orderBy '" + written + "'")
                    .as("the refusal carries both orderings, so the retry needs no second guess")
                    .contains("count").contains("key");
            }
        }
    }

    @Test
    void orderByCountIsTheDefaultAndOrderByKeySortsOnTheGroupKey() {
        try (var build = StoreBackedBuild.run(tmp, "aggregate-ordering", SDL)) {
            var byDefault = groups(structured(aggregate(
                build.handle(), Map.of("groupBy", List.of("coordinate")))));
            var byCount = groups(structured(aggregate(build.handle(),
                Map.of("groupBy", List.of("coordinate"), "orderBy", "count"))));
            assertThat(byCount)
                .as("spelling the default out loud answers exactly as omitting it")
                .isEqualTo(byDefault);
            assertThat(byDefault.stream().map(group -> (Integer) group.get("count")).toList())
                .isSortedAccordingTo(Comparator.reverseOrder());

            var byKey = groups(structured(aggregate(build.handle(),
                Map.of("groupBy", List.of("coordinate"), "orderBy", "key"))));
            var keys = byKey.stream().map(group -> (String) key(group).get("coordinate")).toList();
            assertThat(keys).containsExactlyInAnyOrderElementsOf(
                byDefault.stream().map(group -> (String) key(group).get("coordinate")).toList());
            assertThat(keys.stream().filter(java.util.Objects::nonNull).toList())
                .as("by key means ascending on the group key")
                .isSorted();
            if (keys.contains(null)) {
                assertThat(keys.getLast())
                    .as("the absent bucket keeps its stated place at the tail")
                    .isNull();
            }
        }
    }

    @Test
    void theSeveritySugarAndTheWhereFilterReadTheSameSpelling() {
        try (var build = StoreBackedBuild.run(tmp, "aggregate-severity-casing", SDL)) {
            var sugar = entries(structured(DiagnosticsTool.diagnosticsResult(
                build.handle(), Map.of("severity", "ERROR", "limit", 10_000))));
            assertThat(sugar).isNotEmpty();
            for (String written : List.of("ERROR", "error", "Error")) {
                assertThat(entries(structured(filtered(build.handle(), "severity", written))))
                    .as("where reads a closed-taxonomy value into the spelling its column is "
                        + "stored in, so '%s' filters the rows the sugar filters", written)
                    .hasSameSizeAs(sugar);
            }

            // The aggregate answers the same filter, and its group key comes back in the store's
            // own spelling rather than the caller's, which is what keeps a key a drill-down.
            var grouped = structured(aggregate(build.handle(), Map.of(
                "groupBy", List.of("severity"), "where", Map.of("severity", "ERROR"))));
            assertThat(groups(grouped)).singleElement().satisfies(group -> {
                assertThat(key(group)).containsEntry("severity", "error");
                assertThat(group.get("count")).isEqualTo(sugar.size());
            });
        }
    }

    @Test
    void theKindSpellingTheEntriesRenderReadsBackThroughWhere() {
        try (var build = StoreBackedBuild.run(tmp, "aggregate-kind-spelling", SDL)) {
            var kinds = groups(structured(aggregate(
                build.handle(), Map.of("groupBy", List.of("kind"))))).stream()
                .filter(group -> key(group).get("kind") != null)
                .toList();
            assertThat(kinds).isNotEmpty();
            for (var group : kinds) {
                String stored = (String) key(group).get("kind");
                int count = (Integer) group.get("count");
                var byStored = entries(structured(filtered(build.handle(), "kind", stored)));
                assertThat(byStored).hasSize(count);

                // The entries render the stored kind kebab-cased. That spelling is what an agent
                // has in hand to paste back, so it has to filter the same rows.
                String rendered = (String) byStored.getFirst().get("rejectionKind");
                assertThat(rendered).isNotNull().isNotEqualTo(stored);
                assertThat(entries(structured(filtered(build.handle(), "kind", rendered))))
                    .as("the kind spelling the entries render ('%s') reads back as a filter", rendered)
                    .hasSize(count);
            }
        }
    }

    @Test
    void everyStoredDimensionValueIsAlreadyInItsDeclaredSpelling() {
        try (var build = StoreBackedBuild.run(tmp, "aggregate-spelling-pin", SDL)) {
            for (var dimension : DiagnosticFacets.Dimension.values()) {
                var groups = groups(structured(aggregate(build.handle(), Map.of(
                    "groupBy", List.of(dimension.wireName()),
                    "limit", DiagnosticFacets.MAX_GROUP_LIMIT))));
                for (var group : groups) {
                    Object value = key(group).get(dimension.wireName());
                    if (value == null) {
                        continue;
                    }
                    String stored = String.valueOf(value);
                    assertThat(dimension.normalise(stored))
                        .as("a declared spelling has to be the identity on the values the store "
                            + "holds, or the where boundary would drop rows a raw comparison "
                            + "matches; dimension '%s'", dimension.wireName())
                        .isEqualTo(stored);
                }
            }
        }
    }

    // ---- helpers ----

    /** The {@code diagnostics} answer for one {@code where} dimension, unpaged. */
    private static McpSchema.CallToolResult filtered(
        StoreHandle handle, String dimension, Object value
    ) {
        var where = new LinkedHashMap<String, Object>();
        where.put(dimension, value);
        var args = new LinkedHashMap<String, Object>();
        args.put("where", where);
        args.put("limit", 10_000);
        return DiagnosticsTool.diagnosticsResult(handle, args);
    }


    private McpSchema.CallToolResult aggregate(StoreHandle handle, Map<String, Object> args) {
        var result = DiagnosticFacets.aggregateResult(handle, args);
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        return result;
    }

    /** Writes {@code errors} through the production residue loader and hands back the read handle. */
    private StoreHandle volumeHandle(
        DSLContext dsl, List<ValidationError> errors
    ) {
        rejectionFacts(dsl, "volume", tmp).write(errors);
        return new StoreHandle(dsl, "volume");
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
