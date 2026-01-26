package no.sikt.graphql.helpers.resolvers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ResolverHelpers - assertSameColumnValues")
class ResolverHelpersTest {

    @Test
    @DisplayName("Should pass when all values are equal")
    void shouldPassWhenAllValuesEqual() {
        assertThatCode(() -> ResolverHelpers.assertSameColumnValues(
                "id", 1, "actor", 1, "other", 1
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should pass when all values are null")
    void shouldPassWhenAllValuesNull() {
        assertThatCode(() -> ResolverHelpers.assertSameColumnValues(
                "id", null, "actor", null
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should pass when only one non-null value")
    void shouldPassWhenOnlyOneNonNullValue() {
        assertThatCode(() -> ResolverHelpers.assertSameColumnValues(
                "id", 1, "actor", null
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should throw when two values differ")
    void shouldThrowWhenValuesDiffer() {
        assertThatThrownBy(() -> ResolverHelpers.assertSameColumnValues(
                "id", 1, "actor", 2
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Input fields id and actor have conflicting values. This usually indicates that the ids correspond to different entities.");
    }

    @Test
    @DisplayName("Should throw with first conflicting pair when multiple differ")
    void shouldThrowWithFirstConflictingPair() {
        assertThatThrownBy(() -> ResolverHelpers.assertSameColumnValues(
                "id", 1, "secondId", 2, "actor", 3
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Input fields id and secondId have conflicting values. This usually indicates that the ids correspond to different entities.");
    }

    @Test
    @DisplayName("Should pass with empty arguments")
    void shouldPassWithEmptyArguments() {
        assertThatCode(() -> ResolverHelpers.assertSameColumnValues())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should preserve insertion order in error message")
    void shouldPreserveOrderInErrorMessage() {
        // Fields are specified in order: actor, id - error should mention actor first
        assertThatThrownBy(() -> ResolverHelpers.assertSameColumnValues(
                "actor", 1, "id", 2
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Input fields actor and id have conflicting values. This usually indicates that the ids correspond to different entities.");
    }

    @Test
    @DisplayName("Should throw on odd number of arguments")
    void shouldThrowOnOddArguments() {
        assertThatThrownBy(() -> ResolverHelpers.assertSameColumnValues(
                "id", 1, "actor"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected even number of arguments (key-value pairs)");
    }
}