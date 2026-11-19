package no.sikt.graphitron.model.reference;

import no.sikt.graphitron.model.boot.DefectGuidance;
import no.sikt.graphitron.model.reference.DefectReferencePage.Entry;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefectReferencePageTest {

    @Test
    @DisplayName("the page holds one anchored entry per declared code, guidance included")
    void oneEntryPerCode() {
        ThreadConfinedStore.run(dsl -> {
            var entries = DefectReferencePage.read(dsl);
            String page = DefectReferencePage.render(entries, DefectGuidance.fragmentCodes());
            assertThat(entries).isNotEmpty();
            for (var entry : entries) {
                assertThat(page).contains("[#" + DefectReferencePage.anchor(entry.code()) + "]\n=== `"
                    + entry.code() + "`");
            }
            var upsert = entries.stream().filter(e -> e.code().equals("MUTATION_UPSERT")).findFirst().orElseThrow();
            assertThat(upsert.guidance()).isNotBlank();
            assertThat(page).contains(upsert.guidance().strip());
        });
    }

    @Test
    @DisplayName("an entry's anchor is its code in lower kebab case")
    void anchorIsKebab() {
        assertThat(DefectReferencePage.anchor("MUTATION_UPSERT")).isEqualTo("mutation-upsert");
        assertThat(DefectReferencePage.link("MUTATION_UPSERT"))
            .isEqualTo("https://graphitron.sikt.no/manual/reference/defects.html#mutation-upsert");
    }

    @Test
    @DisplayName("the floors refuse an empty set, a blank statement, a deferral with no guidance and an orphaned fragment")
    void floors() {
        var ok = new Entry("A_CODE", "error", "GRAPHITRON", "GRAPHITRON_A_CODE", "A rule.", null);
        assertThatThrownBy(() -> DefectReferencePage.render(List.of(), Set.of()))
            .hasMessageContaining("no declared codes");
        assertThatThrownBy(() -> DefectReferencePage.render(
            List.of(new Entry("B", "error", "GRAPHITRON", null, " ", null)), Set.of()))
            .hasMessageContaining("blank statement");
        assertThatThrownBy(() -> DefectReferencePage.render(
            List.of(new Entry("C", "error", "UNSUPPORTED", null, "Not yet.", null)), Set.of()))
            .hasMessageContaining("C is UNSUPPORTED and has no guidance");
        assertThatThrownBy(() -> DefectReferencePage.render(List.of(ok), Set.of("GONE")))
            .hasMessageContaining("GONE.adoc names a code the store does not declare");
        assertThat(DefectReferencePage.render(List.of(ok), Set.of())).contains("[#a-code]");
    }
}
