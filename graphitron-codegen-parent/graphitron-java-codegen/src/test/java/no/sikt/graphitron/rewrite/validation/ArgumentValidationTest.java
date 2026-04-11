package no.sikt.graphitron.rewrite.validation;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ArgumentRef;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.FieldConditionRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates argument type resolution on fields that carry {@link ArgumentRef} lists.
 */
class ArgumentValidationTest {

    private static final ReturnTypeRef.TableBoundReturnType FILM_RETURN = new ReturnTypeRef.TableBoundReturnType("Film", new no.sikt.graphitron.rewrite.model.TableRef("film", "FILM", "Film", Optional.of(List.of())), new FieldWrapper.Single(true));

    /**
     * Build a schema with the field on a {@link RootType} parent (avoids unresolved-table errors),
     * plus any extra types needed for argument resolution checks.
     */
    private static List<ValidationError> validateField(GraphitronField field, Map<String, GraphitronType> extraTypes) {
        var types = new java.util.LinkedHashMap<String, GraphitronType>();
        types.put(field.parentTypeName(), new RootType(field.parentTypeName(), null, List.of()));
        types.putAll(extraTypes);
        var fields = Map.of(FieldCoordinates.coordinates(field.parentTypeName(), field.name()), field);
        return validate(new GraphitronSchema(types, fields));
    }

    private static List<ValidationError> validateField(GraphitronField field) {
        return validateField(field, Map.of());
    }

    private static TableField tableField(List<ArgumentRef> args) {
        return new TableField("Film", "actors", null, FILM_RETURN, List.of(), new FieldConditionRef.NoFieldCondition(), args);
    }

    enum Case {

        NO_ARGS("no arguments — no errors",
            tableField(List.of()),
            Map.of(),
            List.of()),

        BUILTIN_SCALAR_ARG("argument with built-in scalar type — no errors",
            tableField(List.of(new ArgumentRef.MethodParamArg.ScalarParamArg("limit", "Int", false, false))),
            Map.of(),
            List.of()),

        KNOWN_INPUT_TYPE_ARG("argument referencing a known InputType — no errors",
            tableField(List.of(new ArgumentRef.MethodParamArg.ObjectParamArg("filter", "FilmFilter", false, false))),
            Map.of("FilmFilter", new InputType("FilmFilter", null)),
            List.of()),

        CUSTOM_SCALAR_ARG("argument with a custom scalar type — no errors (graphql-java validates scalars)",
            tableField(List.of(new ArgumentRef.MethodParamArg.ScalarParamArg("createdAt", "DateTime", false, false))),
            Map.of(),
            List.of());

        private final GraphitronField field;
        private final Map<String, GraphitronType> extraTypes;
        private final List<String> errors;
        private final String description;

        Case(String description, GraphitronField field, Map<String, GraphitronType> extraTypes, List<String> errors) {
            this.description = description;
            this.field = field;
            this.extraTypes = extraTypes;
            this.errors = errors;
        }

        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void argumentValidation(Case tc) {
        assertThat(validateField(tc.field, tc.extraTypes))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors);
    }
}
