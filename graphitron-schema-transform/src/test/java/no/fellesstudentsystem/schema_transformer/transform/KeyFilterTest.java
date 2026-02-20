package no.fellesstudentsystem.schema_transformer.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class KeyFilterTest extends AbstractTest {

    private static final String BASE = "keyFilter/";

    @Test
    @DisplayName("Non-resolvable @key directive is removed from type")
    void removeNonResolvableKeyFromType() {
        var path = BASE + "removeNonResolvableType";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertDirectives(new KeyFilter(test).getModifiedGraphQLSchema(), expected);
    }

    @Test
    @DisplayName("Only non-resolvable @key is removed when type has both resolvable and non-resolvable @key directives")
    void mixedResolvability() {
        var path = BASE + "mixedResolvability";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertDirectives(new KeyFilter(test).getModifiedGraphQLSchema(), expected);
    }

    @Test
    @DisplayName("Non-resolvable @key directive is removed from interface")
    void removeNonResolvableKeyFromInterface() {
        var path = BASE + "removeNonResolvableInterface";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertDirectives(new KeyFilter(test).getModifiedGraphQLSchema(), expected);
    }
}
