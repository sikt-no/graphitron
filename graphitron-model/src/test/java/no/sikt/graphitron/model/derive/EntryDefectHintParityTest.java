package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static no.sikt.graphitron.model.Tables.DIAGNOSTIC;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stored defect's suggestion reads the same in the build and in the editor (Alf, 2026-11-19): the
 * build's message, {@link EntryDefectVerdict#error}, and the diagnostic view's, which the language
 * server and the MCP server replay, both append the hint {@code graphitron_entry_defect_hint} ranks.
 * Asked of every COLUMN_UNRESOLVED a filter document holds.
 */
class EntryDefectHintParityTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    void theBuildAndTheEditorSuggestTheSameColumns() {
        FACTS.read("defects/filter-column-unresolved", graph -> {
            var dsl = graph.handle().dsl();
            var graphName = graph.handle().graphName();
            var built = EntryDefectVerdict.rows(dsl, graphName).stream()
                .filter(row -> row.code().equals("COLUMN_UNRESOLVED"))
                .toList();
            assertThat(built).as("the document's column misses").isNotEmpty();
            assertThat(built).as("every one has a suggestion").allSatisfy(row ->
                assertThat(row.hint()).startsWith("; did you mean: "));
            for (var row : built) {
                var editor = dsl.select(DIAGNOSTIC.MESSAGE).from(DIAGNOSTIC)
                    .where(DIAGNOSTIC.GRAPH_NAME.eq(graphName),
                        DIAGNOSTIC.LSP_CODE.eq("GRAPHITRON_COLUMN_UNRESOLVED"),
                        DIAGNOSTIC.FILE.eq(row.location().getSourceName()),
                        DIAGNOSTIC.SOURCE_LINE.eq(row.location().getLine()),
                        DIAGNOSTIC.SOURCE_COLUMN.eq(row.location().getColumn()))
                    .fetchSingle(DIAGNOSTIC.MESSAGE);
                assertThat(EntryDefectVerdict.error(row).message())
                    .as("the build's message carries the hint")
                    .contains(row.hint());
                assertThat(editor)
                    .as("the editor's message carries the same hint")
                    .contains(row.hint());
            }
            return null;
        });
    }
}
