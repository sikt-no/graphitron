package no.sikt.graphql;

import no.sikt.jooq.example.VacationDestinationRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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

        assertTrue(vacationDestinationRecord.changed());
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

        assertTrue(vacationDestinationRecord.changed());
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

    @Test
    @DisplayName("nodeIdToTableRecord creates a record with node ID fields populated")
    void nodeIdIsMappedToTableRecord() {
        var record = new NodeIdStrategy().nodeIdToTableRecord(
                "VmFjYXRpb25EZXN0aW5hdGlvbjoxMjM0LE5vcndheQ==", // Decoded: VacationDestination:1234,Norway
                "VacationDestination",
                List.of(VACATION_DESTINATION.DESTINATION_ID, VACATION_DESTINATION.COUNTRY_NAME)
        );

        assertNotNull(record);
        assertEquals(1234, record.getDestinationId());
        assertEquals("Norway", record.getCountryName());
    }

    @Test
    @DisplayName("Node IDs with different base64 padding are considered equal")
    void nodeIdsWithDifferentPaddingAreEqual() {
        assertTrue(new NodeIdStrategy().areEqualNodeIds("QzoxCg==", "QzoxCg"));
    }

    @Test
    @DisplayName("areEqualNodeIds returns false when decoding fails, even if inputs are equal")
    void returnsFalseWhenDecodingFails() {
        assertFalse(new NodeIdStrategy().areEqualNodeIds("@", "@"));
    }

    @Test
    @DisplayName("Distinct numeric @key values are not treated as equal node IDs")
    void distinctNumericKeysAreNotEqualNodeIds() {
        // Both are valid base64 but decode to malformed UTF-8. A lenient decoder collapses their
        // bytes to identical U+FFFD replacement characters and wrongly reports them as equal,
        // which mismatched federated entities keyed on numeric codes (e.g. organisasjonskode).
        assertFalse(new NodeIdStrategy().areEqualNodeIds("12400006", "52400006"));
    }

    @Test
    @DisplayName("Distinct short numeric @key values are not treated as equal node IDs")
    void distinctShortNumericKeysAreNotEqualNodeIds() {
        var nodeIdStrategy = new NodeIdStrategy();
        assertFalse(nodeIdStrategy.areEqualNodeIds("104", "105"));
        assertFalse(nodeIdStrategy.areEqualNodeIds("104", "204"));
        assertFalse(nodeIdStrategy.areEqualNodeIds("105", "106"));
    }

    @Test
    @DisplayName("A plain @key value is not equal to a real node ID")
    void plainKeyIsNotEqualToNodeId() {
        var realNodeId = new NodeIdStrategy().createId("Organisasjon", "12400006");
        assertFalse(new NodeIdStrategy().areEqualNodeIds("12400006", realNodeId));
    }

    @Test
    @DisplayName("Different real node IDs are not treated as equal")
    void differentRealNodeIdsAreNotEqual() {
        var nodeIdStrategy = new NodeIdStrategy();
        var a = nodeIdStrategy.createId("Organisasjon", "12400006");
        var b = nodeIdStrategy.createId("Organisasjon", "52400006");
        assertFalse(nodeIdStrategy.areEqualNodeIds(a, b));
    }

    @Test
    @DisplayName("A real node ID equals itself and its unpadded form")
    void realNodeIdEqualsUnpaddedForm() {
        var nodeIdStrategy = new NodeIdStrategy();
        var id = nodeIdStrategy.createId("Organisasjon", "12400006");
        assertTrue(nodeIdStrategy.areEqualNodeIds(id, id));
        // createId already emits without padding; a padded copy must still compare equal.
        var padded = id + "=".repeat((4 - id.length() % 4) % 4);
        assertTrue(nodeIdStrategy.areEqualNodeIds(id, padded));
    }
}