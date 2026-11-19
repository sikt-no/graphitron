package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stored defect's diagnostics entry carries its code, a link to its entry on the defects
 * reference page and the fix guidance the store holds for it, the text the page shows.
 */
class DiagnosticsToolDefectGuidanceTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> diagnostics(String document, String coordinate) {
        return FACTS.read(document, graph -> {
            var result = DiagnosticsTool.diagnosticsResult(graph.handle(), Map.of("coordinate", coordinate));
            return (List<Map<String, Object>>) ((Map<String, Object>) result.structuredContent()).get("diagnostics");
        });
    }

    @Test
    @DisplayName("a deferral's entry names its code, its reference entry and the guidance saying what to write instead")
    void deferralCarriesGuidance() {
        var upsert = diagnostics("defects/mutation-upsert", "Mutation.upsertFilm").stream()
            .filter(e -> "MUTATION_UPSERT".equals(e.get("defectCode")))
            .findFirst().orElseThrow();
        assertThat(upsert.get("reference"))
            .isEqualTo("https://graphitron.sikt.no/manual/reference/defects.html#mutation-upsert");
        assertThat((String) upsert.get("guidance")).contains("*Instead.*").contains("INSERT");
    }

    @Test
    @DisplayName("a defect whose statement says it all carries its code and link and no guidance")
    void codeWithoutGuidance() {
        var conflict = diagnostics("defects/mutation-upsert", "Mutation.upsertOrService").stream()
            .filter(e -> "CLAIMS_CONFLICT".equals(e.get("defectCode")))
            .findFirst().orElseThrow();
        assertThat(conflict.get("reference"))
            .isEqualTo("https://graphitron.sikt.no/manual/reference/defects.html#claims-conflict");
        assertThat(conflict).doesNotContainKey("guidance");
    }
}
