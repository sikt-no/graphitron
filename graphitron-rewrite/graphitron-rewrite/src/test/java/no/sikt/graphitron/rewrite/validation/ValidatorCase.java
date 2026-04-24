package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.model.GraphitronField;

import java.util.List;

/**
 * Implemented by every validation test enum. Each enum constant is a self-contained
 * test case: it builds its own {@link GraphitronField} fixture and declares the exact
 * error messages expected from {@code GraphitronSchemaValidator}.
 *
 * <p>The test method is always the same shape:
 * <pre>
 * {@literal @}ParameterizedTest(name = "{0}")
 * {@literal @}EnumSource(SomeFieldCase.class)
 * void someFieldValidation(SomeFieldCase tc) {
 *     assertThat(validate(tc.field()))
 *         .extracting(ValidationError::message)
 *         .containsExactlyInAnyOrderElementsOf(tc.errors());
 * }
 * </pre>
 */
public interface ValidatorCase {
    GraphitronField field();
    List<String> errors();

    default boolean isValid() {
        return errors().isEmpty();
    }
}
