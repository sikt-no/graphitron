package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.ReferencePathElement;
import no.sikt.graphitron.record.field.TableField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableFieldValidationTest {

    enum Case implements ValidatorCase {

        /** No {@code @reference} — FK auto-inference will be attempted at code-generation time. */
        NO_PATH {
            public GraphitronField field() {
                return new TableField("actors", null, List.of());
            }
            public List<String> errors() { return List.of(); }
        },

        /** Explicit FK path — overrides auto-inference. */
        WITH_FK_PATH {
            public GraphitronField field() {
                return new TableField("actors", null, List.of(
                    new ReferencePathElement("actor", "FILM_ACTOR_FK", Optional.empty())));
            }
            public List<String> errors() { return List.of(); }
        },

        /** Condition method present but could not be resolved via reflection. */
        UNRESOLVED_CONDITION {
            public GraphitronField field() {
                return new TableField("actors", null, List.of(
                    new ReferencePathElement("actor", "FILM_ACTOR_FK", Optional.of(
                        new MethodRef("com.example.Conditions.actorCondition", null, null)))));
            }
            public List<String> errors() {
                return List.of("Field 'actors': condition method 'com.example.Conditions.actorCondition' could not be resolved");
            }
        };

        public abstract GraphitronField field();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void tableFieldValidation(Case tc) {
        assertThat(validate(inTableTypeSchema("Film", "actors", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
