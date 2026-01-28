package no.sikt.graphitron.code;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.setProperties;
import static no.sikt.graphitron.mappings.TableReflection.searchTableFieldNameForPathMethodNameGivenFkJavaFieldName;
import static no.sikt.graphitron.mappings.TableReflection.searchTableJavaFieldNameForMethodName;
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
        assertThat(searchTableFieldNameForPathMethodNameGivenFkJavaFieldName("CUSTOMER", "CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY")).hasValue("address");
    }


    @Test
    @DisplayName("Finds no method if table is provided when key is expected or key does not exist")
    public void findNothingForNotKeys() {
        assertThat(searchTableFieldNameForPathMethodNameGivenFkJavaFieldName("RENTAL", "INVENTORY")).isEmpty();
        assertThat(searchTableFieldNameForPathMethodNameGivenFkJavaFieldName("RENTAL", "NONEXISTENT")).isEmpty();
    }

    @Test
    @DisplayName("Can find a method name for a key or table that exists")
    public void findMethodForExistingKeyOrTable() {
        assertThat(searchTableJavaFieldNameForMethodName("CUSTOMER", "CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY")).hasValue("address");
        assertThat(searchTableJavaFieldNameForMethodName("RENTAL", "inventory")).hasValue("inventory");
    }

    @Test
    @DisplayName("Finds no method if provided value does not exist")
    public void findNothingForInvalidName() {
        assertThat(searchTableJavaFieldNameForMethodName("RENTAL", "NONEXISTENT")).isEmpty();
    }
}
