package no.sikt.graphql;

import no.sikt.jooq.example.VacationDestinationRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.sikt.jooq.example.VacationDestination.VACATION_DESTINATION;
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
                VACATION_DESTINATION.getPrimaryKey().getFieldsArray()
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
                VACATION_DESTINATION.COUNTRY_NAME,
                VACATION_DESTINATION.DESTINATION_ID
        );

        assertEquals(1234, vacationDestinationRecord.getDestinationId());
        assertEquals("Norway", vacationDestinationRecord.getCountryName());

        assertFalse(vacationDestinationRecord.changed());
    }

    @Test
    @DisplayName("Setting foreign key fields for record")
    void setReferenceIdForRecord() {
        var vacationDestinationRecord = new VacationDestinationRecord();

        new NodeIdStrategy().setReferenceId(
                vacationDestinationRecord,
                "VjoxMjM0LDE=", // Decoded: V:1234,1
                "V",
                VACATION_DESTINATION.VACATION_ID,
                VACATION_DESTINATION.EXTRA_KEY
        );
        assertEquals(1234, vacationDestinationRecord.getVacationId());
        assertEquals(1, vacationDestinationRecord.getExtraKey());

        assertTrue(vacationDestinationRecord.changed());
    }
}