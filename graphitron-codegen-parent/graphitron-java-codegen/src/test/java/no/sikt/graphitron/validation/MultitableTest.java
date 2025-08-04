package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Multitable query validation (interfaces and unions)")
public class MultitableTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "multitable";
    }

    @Test
    @DisplayName("Interface returned in field has an implementing type with table missing primary key")
    void listedMultiTableInterfaceNoPrimarykey() {
        assertThatThrownBy(() -> generateFiles("listedNoPrimaryKey"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Interface 'SomeInterface' is returned in field 'query', but implementing " +
                        "type 'PgUser' has table 'PG_USER' which does not have a primary key.");
    }
}
