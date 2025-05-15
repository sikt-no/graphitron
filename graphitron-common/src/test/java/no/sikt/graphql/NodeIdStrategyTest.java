package no.sikt.graphql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}