package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ColumnReferenceField;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.ReferencePathElement;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnReferenceFieldValidationTest {

    /** One FK-only path element: table=language, key=FILM_LANGUAGE_FK, no condition. */
    private static final List<ReferencePathElement> FK_PATH = List.of(
        new ReferencePathElement("language", "FILM_LANGUAGE_FK", Optional.empty())
    );

    /** One method-only path element with a fully resolved condition method. */
    private static final List<ReferencePathElement> CONDITION_PATH = List.of(
        new ReferencePathElement(null, null, Optional.of(
            new MethodRef("com.example.Conditions.languageCondition", "org.jooq.Condition",
                List.of())))
    );

    /** One method-only path element where the condition method could not be resolved. */
    private static final List<ReferencePathElement> UNRESOLVED_CONDITION_PATH = List.of(
        new ReferencePathElement(null, null, Optional.of(
            new MethodRef("com.example.Conditions.languageCondition", null, null)))
    );

    enum Case implements ValidatorCase {

        /** No {@code @field} — column name defaults to the GraphQL field name; column and path resolved. */
        RESOLVED_IMPLICIT {
            public GraphitronField field() {
                return new ColumnReferenceField("languageName", null, "languageName", "NAME", Optional.empty(), FK_PATH);
            }
            public List<String> errors() { return List.of(); }
        },

        /** {@code @field(name: "language_name")} — explicit column name override; column and path resolved. */
        RESOLVED_EXPLICIT {
            public GraphitronField field() {
                return new ColumnReferenceField("languageName", null, "language_name", "NAME", Optional.empty(), FK_PATH);
            }
            public List<String> errors() { return List.of(); }
        },

        /** Path with a resolved condition method instead of a FK. */
        CONDITION_METHOD {
            public GraphitronField field() {
                return new ColumnReferenceField("languageName", null, "languageName", "NAME", Optional.empty(), CONDITION_PATH);
            }
            public List<String> errors() { return List.of(); }
        },

        /** Column name could not be matched to a jOOQ field in the joined table. */
        UNRESOLVED_COLUMN {
            public GraphitronField field() {
                return new ColumnReferenceField("languageName", null, "languageName", null, Optional.empty(), FK_PATH);
            }
            public List<String> errors() {
                return List.of("Field 'languageName': column 'languageName' could not be resolved in the jOOQ table");
            }
        },

        /** No {@code @reference} directive — path is empty. */
        MISSING_PATH {
            public GraphitronField field() {
                return new ColumnReferenceField("languageName", null, "languageName", "NAME", Optional.empty(), List.of());
            }
            public List<String> errors() {
                return List.of("Field 'languageName': @reference path is required");
            }
        },

        /** Condition method present but could not be resolved via reflection. */
        UNRESOLVED_CONDITION {
            public GraphitronField field() {
                return new ColumnReferenceField("languageName", null, "languageName", "NAME", Optional.empty(), UNRESOLVED_CONDITION_PATH);
            }
            public List<String> errors() {
                return List.of("Field 'languageName': condition method 'com.example.Conditions.languageCondition' could not be resolved");
            }
        };

        public abstract GraphitronField field();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void columnReferenceFieldValidation(Case tc) {
        assertThat(validate(inTableTypeSchema("Film", "languageName", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
