package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.List;

/**
 * Implemented by every type-level validation test enum. Parallel to {@link ValidatorCase}
 * for field-level tests.
 *
 * <p>The test method is always the same shape:
 * <pre>
 * {@literal @}ParameterizedTest(name = "{0}")
 * {@literal @}EnumSource(SomeTypeCase.class)
 * void someTypeValidation(SomeTypeCase tc) {
 *     assertThat(validate(tc.type()))
 *         .extracting(ValidationError::message)
 *         .containsExactlyInAnyOrderElementsOf(tc.errors());
 * }
 * </pre>
 */
public interface TypeValidatorCase {
    GraphitronType type();
    List<String> errors();

    default boolean isValid() {
        return errors().isEmpty();
    }
}
