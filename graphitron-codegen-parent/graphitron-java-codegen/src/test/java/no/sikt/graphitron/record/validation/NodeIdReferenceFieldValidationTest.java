package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.NodeIdReferenceField;
import no.sikt.graphitron.record.field.ReferencePathElement;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NodeIdReferenceFieldValidationTest {

    private static final List<ReferencePathElement> FK_PATH = List.of(
        new ReferencePathElement("language", "FILM_LANGUAGE_FK", Optional.empty())
    );

    private static final List<ReferencePathElement> UNRESOLVED_CONDITION_PATH = List.of(
        new ReferencePathElement(null, null, Optional.of(
            new MethodRef("com.example.Conditions.languageCondition", null, null)))
    );

    enum Case implements ValidatorCase {

        /** Reference path resolves successfully. */
        WITH_PATH {
            public GraphitronField field() {
                return new NodeIdReferenceField("languageId", null, FK_PATH);
            }
            public List<String> errors() { return List.of(); }
        },

        /** No {@code @reference} directive — path is empty. */
        MISSING_PATH {
            public GraphitronField field() {
                return new NodeIdReferenceField("languageId", null, List.of());
            }
            public List<String> errors() {
                return List.of("Field 'languageId': @reference path is required");
            }
        },

        /** Condition method present but could not be resolved via reflection. */
        UNRESOLVED_CONDITION {
            public GraphitronField field() {
                return new NodeIdReferenceField("languageId", null, UNRESOLVED_CONDITION_PATH);
            }
            public List<String> errors() {
                return List.of("Field 'languageId': condition method 'com.example.Conditions.languageCondition' could not be resolved");
            }
        };

        public abstract GraphitronField field();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void nodeIdReferenceFieldValidation(Case tc) {
        assertThat(validate(inTableTypeSchema("Film", "languageId", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
