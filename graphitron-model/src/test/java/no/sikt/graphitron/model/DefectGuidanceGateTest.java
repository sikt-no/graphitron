package no.sikt.graphitron.model;

import no.sikt.graphitron.model.boot.DefectGuidance;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the defect guidance fragments to the codes the store declares: every fragment names a
 * declared code. The defects reference renderer checks the same, so a build that skips these tests
 * still fails on an orphan. The other direction, every code having a fragment, is the store's own:
 * it makes the guidance column NOT NULL and refuses to create itself without one.
 */
class DefectGuidanceGateTest {

    @Test
    @DisplayName("every guidance fragment names a declared defect code")
    void everyFragmentNamesADeclaredCode() {
        ThreadConfinedStore.run(dsl -> {
            Set<String> declared = new HashSet<>(dsl.select(GRAPHITRON_DEFECT_TYPE.CODE)
                .from(GRAPHITRON_DEFECT_TYPE).fetch(GRAPHITRON_DEFECT_TYPE.CODE));
            Set<String> fragments = DefectGuidance.fragmentCodes();
            assertThat(fragments)
                .as("the gate lists the fragment directory, so an empty listing means it saw nothing")
                .isNotEmpty();
            var orphans = new TreeSet<>(fragments);
            orphans.removeAll(declared);
            assertThat(orphans)
                .as("fragments under %s naming a code graphitron_defect_type does not declare;"
                    + " rename the fragment or remove it with its code", DefectGuidance.DIRECTORY)
                .isEmpty();
        });
    }

    @Test
    @DisplayName("a fragment's shared prose is expanded into the stored row")
    void includesAreExpanded() {
        ThreadConfinedStore.run(dsl -> {
            var unexpanded = dsl.select(GRAPHITRON_DEFECT_TYPE.CODE)
                .from(GRAPHITRON_DEFECT_TYPE)
                .where(GRAPHITRON_DEFECT_TYPE.GUIDANCE.contains("include::"))
                .fetch(GRAPHITRON_DEFECT_TYPE.CODE);
            assertThat(unexpanded).as("guidance rows still naming an include").isEmpty();
        });
    }
}
