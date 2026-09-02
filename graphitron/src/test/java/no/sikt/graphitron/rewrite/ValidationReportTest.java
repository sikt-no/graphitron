package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * Wiring contract of {@link ValidationReport#from}: the report carries the two lists it was handed
 * and answers emptiness off them, and nothing else. It computed a canonical-URI set once, for an
 * LSP filter that has since gone store-based; the file spelling now lives where the columns it
 * meets are declared.
 */
@UnitTier
class ValidationReportTest {

    @Test
    void emptyReportHasNoEntries() {
        assertThat(ValidationReport.empty().isEmpty()).isTrue();
        assertThat(ValidationReport.empty().errors()).isEmpty();
        assertThat(ValidationReport.empty().warnings()).isEmpty();
    }

    @Test
    void fromCarriesBothListsAndIsNotEmpty() {
        var errors = List.of(new ValidationError(
            "Coord", Rejection.structural("error"),
            new SourceLocation(2, 1, "/tmp/a.graphqls")));
        var warnings = List.<BuildWarning>of(new BuildWarning.NoRule(
            "warn", new SourceLocation(5, 1, "/tmp/b.graphqls")));

        var report = ValidationReport.from(errors, warnings);

        assertThat(report.errors()).isEqualTo(errors);
        assertThat(report.warnings()).isEqualTo(warnings);
        assertThat(report.isEmpty()).isFalse();
    }

    @Test
    void aReportWithNeitherListPopulatedIsEmpty() {
        assertThat(ValidationReport.from(List.of(), List.of()).isEmpty()).isTrue();
    }
}
