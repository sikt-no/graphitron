package no.fellesstudentsystem.graphitron.code;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.graphitron.common.configuration.TestConfiguration.setProperties;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.searchTableForKeyMethodName;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.searchTableForMethodWithName;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reflection - Use reflection on jOOQ code")
public class TableReflectionTest {
    @BeforeEach
    public void setup() {
        setProperties();
    }

    @AfterEach
    public void destroy() {
        GeneratorConfig.clear();
    }

    @Test
    @DisplayName("Can find a method name for a key that exists")
    public void findMethodForExistingKey() {
        assertThat(searchTableForKeyMethodName("CUSTOMER", "CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY")).hasValue("address");
    }


    @Test
    @DisplayName("Finds no method if table is provided when key is expected or key does not exist")
    public void findNothingForNotKeys() {
        assertThat(searchTableForKeyMethodName("RENTAL", "INVENTORY")).isEmpty();
        assertThat(searchTableForKeyMethodName("RENTAL", "NONEXISTENT")).isEmpty();
    }

    @Test
    @DisplayName("Can find a method name for a key or table that exists")
    public void findMethodForExistingKeyOrTable() {
        assertThat(searchTableForMethodWithName("CUSTOMER", "CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY")).hasValue("address");
        assertThat(searchTableForMethodWithName("RENTAL", "INVENTORY")).hasValue("inventory");
    }

    @Test
    @DisplayName("Finds no method if provided value does not exist")
    public void findNothingForInvalidName() {
        assertThat(searchTableForMethodWithName("RENTAL", "NONEXISTENT")).isEmpty();
    }
}
