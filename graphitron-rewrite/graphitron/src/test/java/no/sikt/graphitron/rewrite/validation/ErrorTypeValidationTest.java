package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Validates the intentional no-op in {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator}
 * for {@link ErrorType}. Error types carry handler mappings (Java exception → GraphQL error) but
 * have no structural constraints that the validator can check — correctness is enforced at
 * schema-load time by verifying the referenced exception classes exist. The validator therefore
 * produces zero errors for any {@code ErrorType}, regardless of its handler list.
 */
@UnitTier
class ErrorTypeValidationTest {

    @Test
    void errorType_producesNoValidationErrors() {
        GraphitronType type = new ErrorType("FilmNotFoundException", null, List.of());

        assertThat(validate(type))
            .extracting(ValidationError::message)
            .isEmpty();
    }
}
