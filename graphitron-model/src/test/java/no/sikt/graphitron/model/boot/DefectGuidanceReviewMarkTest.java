package no.sikt.graphitron.model.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefectGuidanceReviewMarkTest {

    @Test
    @DisplayName("a first-line review mark is stripped from the text and held as the reviewer")
    void markIsStrippedAndHeld() {
        var fragment = DefectGuidance.parse("A_CODE", "// reviewed: Alf 2026-11-19\nReported at the field.\n");
        assertThat(fragment.reviewed()).isEqualTo("Alf 2026-11-19");
        assertThat(fragment.guidance()).isEqualTo("Reported at the field.");
    }

    @Test
    @DisplayName("a fragment without a mark is best effort")
    void noMarkIsBestEffort() {
        var fragment = DefectGuidance.parse("A_CODE", "Reported at the field.\n");
        assertThat(fragment.reviewed()).isNull();
        assertThat(fragment.guidance()).isEqualTo("Reported at the field.");
    }

    @Test
    @DisplayName("a mark naming no reviewer, or below the first line, is refused")
    void malformedMarksAreRefused() {
        assertThatThrownBy(() -> DefectGuidance.parse("A_CODE", "// reviewed:\nText."))
            .hasMessageContaining("names no reviewer");
        assertThatThrownBy(() -> DefectGuidance.parse("A_CODE", "Text.\n// reviewed: Alf 2026-11-19"))
            .hasMessageContaining("below its first line");
    }
}
