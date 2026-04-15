package no.sikt.graphql.helpers.resolvers;

import no.sikt.jooq.example.VacationRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.jooq.example.Vacation.VACATION;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ResolverHelpers - prepareRecordsForStore")
class PrepareTableRecordsForStoreTest {

    @Test
    @DisplayName("Should copy touched fields from input to stored record")
    void shouldCopyTouchedFields() {
        var stored = new VacationRecord(1L, "Beach trip");
        stored.touched(false);

        var input = new VacationRecord();
        input.setVacationId(1L);
        input.set(VACATION.DESCRIPTION, "Mountain trip");

        var result = ResolverHelpers.prepareRecordsForStore(List.of(stored), input);

        assertThat(result.getDescription())
                .withFailMessage("Description from input record should be set in the resulting record.")
                .isEqualTo("Mountain trip");

        assertThat(result.touched(VACATION.DESCRIPTION))
                .withFailMessage("The description field from the input record should be 'touched' in the merged record.")
                .isEqualTo(true);

        assertThat(result.touched(VACATION.VACATION_ID))
                .withFailMessage("Primary key field should not be 'touched'.")
                .isEqualTo(false);
    }

    @Test
    @DisplayName("Should preserve untouched fields from stored record")
    void shouldPreserveUntouchedFields() {
        var stored = new VacationRecord(1L, "Beach trip");
        stored.touched(false);

        var input = new VacationRecord();
        input.setVacationId(1L);

        var result = ResolverHelpers.prepareRecordsForStore(List.of(stored), input);

        assertThat(result.getDescription())
                .withFailMessage("The description from the stored record should be preserved.")
                .isEqualTo("Beach trip");

        assertThat(result.touched(VACATION.DESCRIPTION))
                .withFailMessage("The description field from the input record should not be 'touched'.")
                .isEqualTo(false);
    }


    @Test
    @DisplayName("Should merge lists by matching primary keys")
    void shouldMergeListsByKey() {
        var stored1 = new VacationRecord(1L, "Beach trip");
        stored1.touched(false);
        var stored2 = new VacationRecord(2L, "City tour");
        stored2.touched(false);

        var input1 = new VacationRecord();
        input1.setVacationId(1L);
        input1.set(VACATION.DESCRIPTION, "Updated beach trip");

        var input2 = new VacationRecord();
        input2.setVacationId(2L);
        input2.set(VACATION.DESCRIPTION, "Updated city tour");

        var result = ResolverHelpers.prepareRecordsForStore(List.of(stored1, stored2), List.of(input1, input2));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getVacationId()).isEqualTo(1);
        assertThat(result.get(0).getDescription()).isEqualTo("Updated beach trip");

        assertThat(result.get(1).getVacationId()).isEqualTo(2);
        assertThat(result.get(1).getDescription()).isEqualTo("Updated city tour");
    }

    @Test
    @DisplayName("Should add input record when no matching key in stored records")
    void shouldAddNewRecordWhenKeyNotFound() {
        var stored = new VacationRecord(1L, "Beach trip");
        stored.touched(false);

        var input = new VacationRecord(2L, "Mountain trip");

        var result = ResolverHelpers.prepareRecordsForStore(List.of(stored), List.of(input));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVacationId()).isEqualTo(2);
        assertThat(result.get(0).touched(VACATION.VACATION_ID))
                .withFailMessage("Primary key field should be touched for new records to trigger INSERT.")
                .isEqualTo(true);
        assertThat(result.get(0).getDescription()).isEqualTo("Mountain trip");
    }
}
