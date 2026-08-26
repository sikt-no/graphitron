package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.RESOLVER_MUTATION_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.ERROR;

@DisplayName("Schema validation - services reporting the outcome of each element of a batch")
public class BatchItemResultTest extends ValidationTest {
    private static final Set<no.sikt.graphitron.common.configuration.SchemaComponent> COMPONENTS =
            Set.of(CUSTOMER_INPUT_TABLE, CUSTOMER_TABLE, ERROR);

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "service/batchItemResult";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(RESOLVER_MUTATION_SERVICE);
    }

    @Test
    @DisplayName("One listed argument and an errors field is accepted")
    void valid() {
        getProcessedSchema("valid", COMPONENTS);
    }

    @Test
    @DisplayName("A payload with no errors field is rejected, since the failures would have nowhere to go")
    void noErrorField() {
        assertErrorsContain("noErrorField", COMPONENTS,
                "returns BatchItemResult, but the return type 'Response' has no errors field");
    }

    @Test
    @DisplayName("A field with no listed argument is rejected, since there is no batch to address")
    void noListedArgument() {
        assertErrorsContain("noListedArgument", COMPONENTS,
                "does not have exactly one listed argument");
    }

    @Test
    @DisplayName("A field with two listed arguments is rejected, since a position could refer to either")
    void twoListedArguments() {
        assertErrorsContain("twoListedArguments", COMPONENTS,
                "does not have exactly one listed argument");
    }
}
