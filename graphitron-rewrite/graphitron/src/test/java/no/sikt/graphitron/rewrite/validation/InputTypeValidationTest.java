package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PojoInputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.InputRecordShape;
import no.sikt.graphitron.rewrite.model.InputRecordShape.InputComponent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
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

    /**
     * Minimal {@link InputRecordShape} for tests that construct input types directly: the
     * validator under test reads only the validator-relevant slots, but the InputType records
     * require a non-null shape (the {@code recordShape()} component is the structural pin for
     * a successfully classified input type). A single placeholder component satisfies the
     * compact constructor.
     */
    private static InputRecordShape placeholderShape(String typeName) {
        return new InputRecordShape(
            ClassName.get("test.inputs", typeName),
            List.of(new InputComponent("placeholder", "placeholder", TypeName.get(String.class), true))
        );
    }

    enum Case {

        VALID("valid input type — no errors",
            new PojoInputType("FilmInput", null, null, null, placeholderShape("FilmInput")),
            List.of()),

        KNOWN_INPUT_TYPE_NO_ERROR("input field referencing another InputType in the schema — no error",
            new PojoInputType("CreateFilmInput", null, null, null, placeholderShape("CreateFilmInput")),
            Map.of("TranslationInput", new PojoInputType("TranslationInput", null, null, null, placeholderShape("TranslationInput"))),
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
