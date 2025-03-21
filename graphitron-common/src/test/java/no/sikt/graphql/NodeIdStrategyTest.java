package no.sikt.graphql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeIdStrategyTest {
    // Below is string "1337:keyColumn1,keyColumn2" base64 encoded
    static final String BASE64_ENCODED_ID = "MTMzNzprZXlDb2x1bW4xLGtleUNvbHVtbjI";
    static final String TYPE_ID = "1337";
    static final String[] KEY_COLUMNS = {"keyColumn1", "keyColumn2"};

    @Test
    void shouldCreateId() {
        var actualId = NodeIdStrategy.createId(TYPE_ID, KEY_COLUMNS);

        assertEquals(BASE64_ENCODED_ID, actualId);
    }

    @Test
    void shouldReturnTypeId() {
        var actualTypeId = NodeIdStrategy.getTypeId(BASE64_ENCODED_ID);

        assertEquals(TYPE_ID, actualTypeId);
    }
}