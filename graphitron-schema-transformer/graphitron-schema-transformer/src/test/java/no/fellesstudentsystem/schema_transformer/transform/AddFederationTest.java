package no.fellesstudentsystem.schema_transformer.transform;

import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AddFederationTest extends AbstractTest {
    @Test
    @DisplayName("Adds federation types to schema")
    void addFederation() {
        var transformedSchema = SchemaTransformer.addFederation(makeSchema("federation"));

        var query = transformedSchema.getObjectType("Query");
        if (query == null) {
            throw new IllegalArgumentException("Type \"Query\" is missing.");
        }

        // Testing only these two here, but there are several other fields and types that are also added.
        assertThat(query.getFieldDefinition("_entities")).isNotNull();
        assertThat(transformedSchema.getType("_Entity")).isNotNull();
    }
}
