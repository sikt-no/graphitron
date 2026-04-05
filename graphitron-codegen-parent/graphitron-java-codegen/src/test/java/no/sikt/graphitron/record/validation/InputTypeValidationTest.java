package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.GraphitronSchema;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.GraphitronType.InputType;
import no.sikt.graphitron.record.type.GraphitronType.RootType;
import no.sikt.graphitron.record.type.InputFieldSpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
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

        SCALAR_FIELDS_NO_ERRORS("input type with only built-in scalars — no errors",
            new InputType("FilmInput", null, List.of(
                new InputFieldSpec("title", "String", true, false, false, false, "title", false),
                new InputFieldSpec("year", "Int", false, false, false, false, "year", false))),
            List.of()),

        UNKNOWN_TYPE_ERROR("input field referencing a type not in the schema — error",
            new InputType("FilmInput", null, List.of(
                new InputFieldSpec("status", "FilmStatus", false, false, false, false, "status", false))),
            List.of("Input type 'FilmInput': field 'status' references unknown type 'FilmStatus'")),

        KNOWN_INPUT_TYPE_NO_ERROR("input field referencing another InputType in the schema — no error",
            new InputType("CreateFilmInput", null, List.of(
                new InputFieldSpec("translation", "TranslationInput", false, false, false, false, "translation", false))),
            Map.of("TranslationInput", new InputType("TranslationInput", null, List.of())),
            List.of()),

        EMPTY_FIELDS_NO_ERRORS("input type with no fields — no errors",
            new InputType("EmptyInput", null, List.of()),
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
