package no.fellesstudentsystem.schema_transformer.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

public class RemoveDirectiveTest extends AbstractTest {
    @Test
    @DisplayName("Remove selected directive from the schema")
    void removeDirective() {
        var path = "removeDirective";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertDirectives(new DirectivesFilter(test, Set.of("D1")).getModifiedGraphQLSchema(), expected);
    }
}
