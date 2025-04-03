package no.fellesstudentsystem.schema_transformer.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

public class RemoveDirectiveTest extends AbstractTest {
    @Test
    @DisplayName("Remove selected directive from the schema")
    void removeDirective() {
        var path = "removeDirective";
        assertTransformedDirectivesMatch(path, new DirectivesFilter(makeSchema(path), Set.of("D1")).getModifiedGraphQLSchema());
    }
}
