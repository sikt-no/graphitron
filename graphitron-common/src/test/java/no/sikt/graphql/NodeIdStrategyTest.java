package no.sikt.graphql;

import no.sikt.jooq.example.*;
import no.sikt.jooq.example.Vacation;
import no.sikt.jooq.example.VacationDestination;
import no.sikt.jooq.example.VacationDestinationRecord;
import no.sikt.jooq.example.VacationRecord;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeIdStrategyTest {
    @Test
    void shouldCreateId() {
        var actualId = new NodeIdStrategy().createId("1337", "keyColumn1", "keyColumn2");

        assertEquals("MTMzNzprZXlDb2x1bW4xLGtleUNvbHVtbjI", actualId);
    }

    @Test
    void shouldReturnTypeId() {
        assertEquals("1337", new NodeIdStrategy().getTypeId("MTMzNzprZXlDb2x1bW4xLGtleUNvbHVtbjI"));
    }

    @Test
    void shouldHandleKeyColumnsWithComma() {
        assertEquals("MTQ6MCwxLDIlMkMsMw", new NodeIdStrategy().createId("14", "0", "1", "2,", "3"));
    }

    @Test
    void shouldThrowErrorWhenWrongId() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> new NodeIdStrategy().getTypeId("MCwxLDIlMkMsMw")
        );

        assertEquals("MCwxLDIlMkMsMw (0,1,2%2C,3) is not a valid id", ex.getMessage());
    }

    @Test
    @DisplayName("Setting primary key fields for record")
    void setUniqueIdForRecord() {
        var vacationDestinationRecord = new VacationDestinationRecord();
        new NodeIdStrategy().setId(
                vacationDestinationRecord,
                "VmFjYXRpb25EZXN0aW5hdGlvbjoxMjM0LE5vcndheQ==", // Decoded: VacationDestination:1234,Norway
                "VacationDestination",
                VacationDestination.VACATION_DESTINATION.getPrimaryKey().getFields()
        );

        assertEquals(1234, vacationDestinationRecord.getDestinationId());
        assertEquals("Norway", vacationDestinationRecord.getCountryName());

        assertFalse(vacationDestinationRecord.changed());
    }

    @Test
    @DisplayName("Setting primary key fields for record, with the key fields provided in another order")
    void setUniqueIdForRecordReverseOrder() {
        var vacationDestinationRecord = new VacationDestinationRecord();
        new NodeIdStrategy().setId(
                vacationDestinationRecord,
                "VmFjYXRpb25EZXN0aW5hdGlvbjpOb3J3YXksMTIzNA==", // Decoded: VacationDestination:Norway,1234
                "VacationDestination",
                List.of(VacationDestination.VACATION_DESTINATION.COUNTRY_NAME, VacationDestination.VACATION_DESTINATION.DESTINATION_ID)
        );

        assertEquals(1234, vacationDestinationRecord.getDestinationId());
        assertEquals("Norway", vacationDestinationRecord.getCountryName());

        assertFalse(vacationDestinationRecord.changed());
    }

    @Test
    @DisplayName("Setting primary key fields for record, with an additional non-PK field")
    void setUniqueIdForRecordWithExtraField() {
        var vacationRecord = new VacationRecord();
        new NodeIdStrategy().setId(
                vacationRecord,
                "VjoxMjM0LEhlbGxv", // Decoded: V:1234,Hello
                "V",
                List.of(Vacation.VACATION.VACATION_ID, Vacation.VACATION.DESCRIPTION)
        );

        assertEquals(1234, vacationRecord.getVacationId());
        assertEquals("Hello", vacationRecord.getDescription());

        assertTrue(vacationRecord.changed());
    }

    @Test
    @DisplayName("Setting foreign key fields for record")
    void setReferenceIdForRecord() {
        var vacationDestinationRecord = new VacationDestinationRecord();
        new NodeIdStrategy().setId(
                vacationDestinationRecord,
                Keys.VACATION_DESTINATION__VACATION_DESTINATION_VACATION_FKEY,
                "VjoxMjM0LDE=", // Decoded: V:1234,1
                "V",
                List.of(Vacation.VACATION.VACATION_ID, Vacation.VACATION.EXTRA_KEY)
        );
        assertEquals(1234, vacationDestinationRecord.getVacationId());
        assertEquals(1, vacationDestinationRecord.getExtraKey());

        assertTrue(vacationDestinationRecord.changed());
    }

    @Test
    @DisplayName("Setting foreign key fields for record without providing all key fields should throw error")
    void setReferenceIdMissingKeyFields() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> new NodeIdStrategy().setId(
                        new VacationDestinationRecord(),
                        Keys.VACATION_DESTINATION__VACATION_DESTINATION_VACATION_FKEY,
                        "VjoxMjM0",
                        "V",
                        List.of(Vacation.VACATION.VACATION_ID))
        );

        assertEquals("ID is missing the following fields to set the required fields for foreign key vacation_destination_vacation_fkey: extra_key", ex.getMessage());
    }

    @Test
    @Disabled("Does not yet work --- should we support ignoring extra ID fields????? does this even make sense??????")
    @DisplayName("Setting foreign key fields for record")
    void setReferenceIdWithExtraIdFieldForRecord() {
        var vacationDestinationRecord = new VacationDestinationRecord();
        new NodeIdStrategy().setId(
                vacationDestinationRecord,
                Keys.VACATION_DESTINATION__VACATION_DESTINATION_VACATION_FKEY,
                "VjoxMjM0LDEsaWdub3JlZA==", // Decoded: V:1234,1,ignored
                "V",
                List.of(Vacation.VACATION.VACATION_ID, Vacation.VACATION.EXTRA_KEY, Vacation.VACATION.DESCRIPTION)
        );
        assertEquals(1234, vacationDestinationRecord.getVacationId());
        assertEquals(1, vacationDestinationRecord.getExtraKey());

        assertTrue(vacationDestinationRecord.changed());
    }
}