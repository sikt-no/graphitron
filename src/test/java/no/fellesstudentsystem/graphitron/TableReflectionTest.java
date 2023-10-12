package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.mappings.TableReflection.searchTableForKeyMethodName;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.searchTableForMethodWithName;
import static org.assertj.core.api.Assertions.assertThat;

public class TableReflectionTest {
    public static final String DEFAULT_JOOQ_PACKAGE = "no.sikt.graphitron.jooq.generated.testdata";

    @BeforeEach
    public void setup() {
        GeneratorConfig.setProperties(
                Set.of(),
                "",
                "",
                DEFAULT_JOOQ_PACKAGE,
                List.of(),
                List.of()
        );
    }

    @AfterEach
    public void destroy() {
        GeneratorConfig.clear();
    }

    @Test
    public void searchTableForKeyMethodNameTest() {
        assertThat(searchTableForKeyMethodName("CUSTOMER", "CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY")).isNotEmpty();
        assertThat(searchTableForKeyMethodName("RENTAL", "INVENTORY")).isEmpty();
        assertThat(searchTableForKeyMethodName("RENTAL", "NONEXISTENT")).isEmpty();
    }

    @Test
    public void searchTableForMethodWithNameTest() {
        assertThat(searchTableForMethodWithName("CUSTOMER", "CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY")).isNotEmpty();
        assertThat(searchTableForMethodWithName("RENTAL", "INVENTORY")).isNotEmpty();
        assertThat(searchTableForMethodWithName("RENTAL", "NONEXISTENT")).isEmpty();
    }
}
