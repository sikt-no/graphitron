package no.sikt.graphitron.record.validation;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.record.GraphitronSchema;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ArgumentSpec;
import no.sikt.graphitron.record.field.ChildField.TableField;
import no.sikt.graphitron.record.field.FieldConditionRef;
import no.sikt.graphitron.record.field.FieldWrapper;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.ReturnTypeRef;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.GraphitronType.InputType;
import no.sikt.graphitron.record.type.GraphitronType.RootType;
import no.sikt.graphitron.record.type.GraphitronType.TableType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates argument type resolution on fields that carry {@link ArgumentSpec} lists.
 */
class ArgumentValidationTest {

    private static final ReturnTypeRef FILM_RETURN = new ReturnTypeRef.OtherReturnType("String", new FieldWrapper.Single(true));

    /**
     * Build a schema with the field on a {@link RootType} parent (avoids unresolved-table errors),
     * plus any extra types needed for argument resolution checks.
     */
    private static List<ValidationError> validateField(GraphitronField field, Map<String, GraphitronType> extraTypes) {
        var types = new java.util.LinkedHashMap<String, GraphitronType>();
        types.put(field.parentTypeName(), new RootType(field.parentTypeName(), null));
        types.putAll(extraTypes);
        var fields = Map.of(FieldCoordinates.coordinates(field.parentTypeName(), field.name()), field);
        return validate(new GraphitronSchema(types, fields));
    }

    private static List<ValidationError> validateField(GraphitronField field) {
        return validateField(field, Map.of());
    }

    private static TableField tableField(List<ArgumentSpec> args) {
        return new TableField("Film", "actors", null, FILM_RETURN, List.of(), new FieldConditionRef.NoFieldCondition(), false, args);
    }

    enum Case {

        NO_ARGS("no arguments — no errors",
            tableField(List.of()),
            Map.of(),
            List.of()),

        BUILTIN_SCALAR_ARG("argument with built-in scalar type — no errors",
            tableField(List.of(new ArgumentSpec("limit", "Int", false, false, false, false))),
            Map.of(),
            List.of()),

        KNOWN_INPUT_TYPE_ARG("argument referencing a known InputType — no errors",
            tableField(List.of(new ArgumentSpec("filter", "FilmFilter", false, false, false, false))),
            Map.of("FilmFilter", new InputType("FilmFilter", null, List.of())),
            List.of()),

        UNKNOWN_TYPE_ARG("argument referencing an unknown type — validation error",
            tableField(List.of(new ArgumentSpec("status", "FilmStatus", false, false, false, false))),
            Map.of(),
            List.of("Field 'actors': argument 'status' references unknown type 'FilmStatus'")),

        MULTIPLE_ARGS_ONE_UNKNOWN("two arguments, one unknown — one error",
            tableField(List.of(
                new ArgumentSpec("limit", "Int", false, false, false, false),
                new ArgumentSpec("status", "FilmStatus", false, false, false, false))),
            Map.of(),
            List.of("Field 'actors': argument 'status' references unknown type 'FilmStatus'"));

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
