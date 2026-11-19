package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.read.SourceUri;
import no.sikt.graphitron.model.test.FactGraphs;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.DIAGNOSTIC;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A replayed stored defect links its code to its entry on the defects reference page, which carries
 * the rule and the fix guidance the store holds for it; a finding of no stored defect links nothing.
 */
class DefectReferenceLinkTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    @DisplayName("a stored defect's diagnostic links its code to its defects reference entry")
    void defectLinksItsReferenceEntry() {
        var diagnostics = replay("defects/mutation-upsert", "GRAPHITRON_MUTATION_UPSERT");
        assertThat(diagnostics)
            .filteredOn(d -> d.getCode() != null && "GRAPHITRON_MUTATION_UPSERT".equals(d.getCode().getLeft()))
            .isNotEmpty()
            .allSatisfy(d -> assertThat(d.getCodeDescription().getHref())
                .isEqualTo("https://graphitron.sikt.no/manual/reference/defects.html#mutation-upsert"));
    }

    /** Every diagnostic the last build recorded in the file holding {@code lspCode}'s finding. */
    private static List<Diagnostic> replay(String document, String lspCode) {
        return FACTS.read(document, graph -> {
            var handle = graph.handle();
            String file = handle.dsl().select(DIAGNOSTIC.FILE).from(DIAGNOSTIC)
                .where(DIAGNOSTIC.GRAPH_NAME.eq(handle.graphName()), DIAGNOSTIC.LSP_CODE.eq(lspCode))
                .limit(1)
                .fetchSingle(DIAGNOSTIC.FILE);
            String source;
            try {
                source = Files.readString(Path.of(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return Diagnostics.compute(BundledVocabulary.get(), SourceUri.of(file),
                WorkspaceFileTestSupport.snapshot(source), Optional.of(handle));
        });
    }
}
