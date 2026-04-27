package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PojoInputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class InputTypeValidationTest {

    /** Validates an {@link InputType} with an optional extra type in the schema. */
    private static List<ValidationError> validateInputType(InputType type, Map<String, GraphitronType> extraTypes) {
        var types = new java.util.LinkedHashMap<String, GraphitronType>();
        types.put("Query", new RootType("Query", null));
        types.put(type.name(), type);
        types.putAll(extraTypes);
        return validate(new GraphitronSchema(types, Map.of()));
    }

    private static List<ValidationError> validateInputType(InputType type) {
        return validateInputType(type, Map.of());
    }

    enum Case {

        VALID("valid input type — no errors",
            new PojoInputType("FilmInput", null, null, null),
            List.of()),

        KNOWN_INPUT_TYPE_NO_ERROR("input field referencing another InputType in the schema — no error",
            new PojoInputType("CreateFilmInput", null, null, null),
            Map.of("TranslationInput", new PojoInputType("TranslationInput", null, null, null)),
            List.of());

        private final String description;
        private final InputType type;
        private final Map<String, GraphitronType> extraTypes;
        private final List<String> errors;

        Case(String description, InputType type, List<String> errors) {
            this(description, type, Map.of(), errors);
        }

        Case(String description, InputType type, Map<String, GraphitronType> extraTypes, List<String> errors) {
            this.description = description;
            this.type = type;
            this.extraTypes = extraTypes;
            this.errors = errors;
        }

        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void inputTypeValidation(Case tc) {
        assertThat(validateInputType(tc.type, tc.extraTypes))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors);
    }
}
