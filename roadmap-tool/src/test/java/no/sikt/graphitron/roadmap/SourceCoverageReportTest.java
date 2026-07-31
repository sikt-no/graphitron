package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Renders from fixture CSVs written to a temp directory tree and pins the page's contract:
 * module aggregation arithmetic, AsciiDoc table structure, the stated top-N cap, tier columns
 * present exactly when tier CSVs exist, the leaf join carrying both facts per row (including
 * the two actionable quadrants), and the empty-glob diagnostic.
 */
class SourceCoverageReportTest {

    private static final String HEADER = "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,"
        + "INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,"
        + "COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n";

    /** One CSV line in jacoco.csv's column order; instruction/complexity filled with zeros. */
    private static String row(String group, String pkg, String cls,
            long lineMissed, long lineCovered, long branchMissed, long branchCovered,
            long methodMissed, long methodCovered) {
        return group + "," + pkg + "," + cls + ",0,0," + branchMissed + "," + branchCovered + ","
            + lineMissed + "," + lineCovered + ",0,0," + methodMissed + "," + methodCovered + "\n";
    }

    private static Path writeCsv(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Path csv = dir.resolve("jacoco.csv");
        Files.writeString(csv, content);
        return csv;
    }

    /**
     * Two modules, four graphitron classes across two packages. Hand-computed aggregates:
     * graphitron lines 90/(90+80) = 52.9%, branches 4/8 = 50.0%, methods 4/4 = 100.0%.
     */
    private static Path combinedFixture(Path dir) throws IOException {
        return writeCsv(dir, HEADER
            + row("no.sikt:graphitron", SourceCoverageReport.MODEL_PACKAGE, "ChildField.TableField", 10, 30, 1, 1, 0, 1)
            // Nested-class separator deliberately written the VM way to pin the $-to-. normalisation.
            + row("no.sikt:graphitron", SourceCoverageReport.MODEL_PACKAGE, "ChildField$DeadLeaf", 20, 0, 1, 1, 0, 1)
            + row("no.sikt:graphitron", SourceCoverageReport.MODEL_PACKAGE, "RootField.QueryField", 0, 10, 1, 1, 0, 1)
            + row("no.sikt:graphitron", "no.sikt.graphitron.generators", "FetcherEmitter", 50, 50, 1, 1, 0, 1)
            + row("no.sikt:graphitron-roadmap-tool", "no.sikt.graphitron.roadmap", "Main", 1, 3, 0, 0, 1, 1));
    }

    private static List<LeafCoverageReport.Leaf> leaves() {
        return List.of(
            new LeafCoverageReport.Leaf("ChildField", "TableField", "ChildField.TableField", "a table"),
            new LeafCoverageReport.Leaf("ChildField", "DeadLeaf", "ChildField.DeadLeaf", ""));
    }

    @Test
    void moduleTable_aggregatesToHandComputedPercentages(@TempDir Path dir) throws Exception {
        String page = SourceCoverageReport.render(
            List.of(combinedFixture(dir)), Map.of(), leaves(), List.of());

        assertThat(page).contains("| `graphitron`\n| 52.9%\n| 50.0%\n| 100.0%\n");
        assertThat(page).contains("| `graphitron-roadmap-tool`\n| 75.0%\n| -\n| 50.0%\n");
    }

    @Test
    void tables_useAdocStructuralSyntax(@TempDir Path dir) throws Exception {
        String page = SourceCoverageReport.render(
            List.of(combinedFixture(dir)), Map.of(), leaves(), List.of());

        // Four tables, each an AsciiDoc block; the markdown pipe-and-dash form renders as
        // paragraph text and check-adoc-tables would reject it.
        assertThat(page).startsWith("= Source coverage report\n");
        assertThat(page.lines().filter(l -> l.equals("|===")).count()).isEqualTo(8);
        assertThat(page.lines().filter(l -> l.startsWith("[cols=")).count()).isEqualTo(4);
    }

    @Test
    void topMissedTable_isCappedAndStatesTheCap(@TempDir Path dir) throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= SourceCoverageReport.TOP_MISSED_CLASSES + 5; i++) {
            csv.append(row("no.sikt:graphitron", "no.sikt.graphitron.generators",
                "Class" + String.format("%02d", i), i, 0, 0, 0, 0, 0));
        }
        String page = SourceCoverageReport.render(
            List.of(writeCsv(dir, csv.toString())), Map.of(), leaves(), List.of());

        assertThat(page).contains("The " + SourceCoverageReport.TOP_MISSED_CLASSES
            + " classes with the most missed lines");
        // The five least-missed classes fall off the bounded list.
        assertThat(page).contains("`generators.Class30`").doesNotContain("`generators.Class05`");
    }

    @Test
    void tierColumns_presentExactlyWhenTierCsvsExist(@TempDir Path dir) throws Exception {
        Path combined = combinedFixture(dir.resolve("target/site/jacoco"));
        Path unit = writeCsv(dir.resolve("target/site/jacoco-unit"), HEADER
            + row("no.sikt:graphitron", "no.sikt.graphitron.generators", "FetcherEmitter", 75, 25, 0, 0, 0, 0));
        Path pipeline = writeCsv(dir.resolve("target/site/jacoco-pipeline"), HEADER
            + row("no.sikt:graphitron", "no.sikt.graphitron.generators", "FetcherEmitter", 60, 40, 0, 0, 0, 0));

        // Insertion order reversed on purpose: the columns must come out in TIER_ORDER.
        Map<String, List<Path>> tiered = new LinkedHashMap<>();
        tiered.put("pipeline", List.of(pipeline));
        tiered.put("unit", List.of(unit));
        String withTiers = SourceCoverageReport.render(List.of(combined),
            sortedByTierOrder(tiered), leaves(), List.of());

        assertThat(withTiers).contains("| Package | Line | Branch | Method | unit line | pipeline line\n");
        assertThat(withTiers).contains("| `generators`\n| 50.0%\n| 50.0%\n| 100.0%\n| 25.0%\n| 40.0%\n");
        // A package one tier never touched renders "-" in that tier's column.
        assertThat(withTiers).contains("| `rewrite.model`\n| 57.1%\n| 50.0%\n| 100.0%\n| -\n| -\n");
        // The slices do not silently pose as a decomposition.
        assertThat(withTiers).contains("slices, not as a decomposition");

        String withoutTiers = SourceCoverageReport.render(List.of(combined), Map.of(), leaves(), List.of());
        assertThat(withoutTiers).doesNotContain("unit line").doesNotContain("slices");
    }

    @Test
    void leafJoin_carriesBothFactsIncludingTheActionableQuadrants(@TempDir Path dir) throws Exception {
        Path traces = dir.resolve("leaf-coverage.jsonl");
        // TableField is classified twice but three quarters of its class never runs: the
        // demonstrated-but-unexecuted quadrant. DeadLeaf has no traces and no coverage: dead weight.
        Files.writeString(traces, """
            {"op":"classify","leaf":"ChildField.TableField","tier":"pipeline","source":"a.graphqls","test":"T"}
            {"op":"classify","leaf":"ChildField.TableField","tier":"unit","source":"b.graphqls","test":"U"}
            """);
        String page = SourceCoverageReport.render(
            List.of(combinedFixture(dir)), Map.of(), leaves(), List.of(traces));

        assertThat(page).contains("| ChildField\n| `ChildField.TableField`\n| 2\n| 75.0%\n");
        assertThat(page).contains("| ChildField\n| `ChildField.DeadLeaf`\n| 0\n| 0.0%\n");
    }

    @Test
    void leafJoin_withoutTraceFiles_readsDashNotZero(@TempDir Path dir) throws Exception {
        // A -Dleaf-coverage.skip build has no traces; a hard 0 would misread as dead weight.
        String page = SourceCoverageReport.render(
            List.of(combinedFixture(dir)), Map.of(), leaves(), List.of());

        assertThat(page).contains("| ChildField\n| `ChildField.TableField`\n| -\n| 75.0%\n");
        assertThat(page).contains("No classifier traces were found");
    }

    @Test
    void run_withNoCoverageCsv_shortCircuitsWithDiagnostic(@TempDir Path dir) {
        assertThatThrownBy(() -> SourceCoverageReport.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_usageError_returnsExitCodeWithoutThrowing() throws IOException {
        assertThat(SourceCoverageReport.run(List.of())).isEqualTo(64);
    }

    @Test
    void findCsvFiles_separatesCombinedFromTierDirectories(@TempDir Path dir) throws IOException {
        Path combined = writeCsv(dir.resolve("m/target/site/jacoco"), HEADER);
        Path unit = writeCsv(dir.resolve("m/target/site/jacoco-unit"), HEADER);
        // Wrong depth: not under target/site, so not a report directory.
        writeCsv(dir.resolve("m/other/jacoco"), HEADER);

        List<Path> foundCombined = new ArrayList<>();
        Map<String, List<Path>> foundTiered = new LinkedHashMap<>();
        SourceCoverageReport.findCsvFiles(dir, foundCombined, foundTiered);

        assertThat(foundCombined).containsExactly(combined);
        assertThat(foundTiered).containsOnlyKeys("unit");
        assertThat(foundTiered.get("unit")).containsExactly(unit);
    }

    /** Re-keys a map by {@link TierVocabulary#tierOrder()}, as {@code run} does. */
    private static Map<String, List<Path>> sortedByTierOrder(Map<String, List<Path>> tiered) {
        var out = new java.util.TreeMap<String, List<Path>>(TierVocabulary.tierOrder());
        out.putAll(tiered);
        return out;
    }
}
