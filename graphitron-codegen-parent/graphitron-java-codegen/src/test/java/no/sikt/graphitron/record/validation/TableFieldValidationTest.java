package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.jooq.generated.testdata.public_.Keys;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ConditionOnlyStep;
import no.sikt.graphitron.record.field.FkStep;
import no.sikt.graphitron.record.field.FkWithConditionStep;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.TableField;
import no.sikt.graphitron.record.field.UnresolvedConditionStep;
import no.sikt.graphitron.record.field.UnresolvedKeyStep;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

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

        /** Explicit FK path — key resolved to a jOOQ ForeignKey. */
        WITH_FK_PATH {
            public GraphitronField field() {
                return new TableField("actors", null, List.of(
                    new FkStep(Keys.FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY)));
            }
            public List<String> errors() { return List.of(); }
        },

        /** FK + resolved condition method. */
        WITH_FK_AND_CONDITION {
            public GraphitronField field() {
                var condition = new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of());
                return new TableField("actors", null, List.of(
                    new FkWithConditionStep(Keys.FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY, condition)));
            }
            public List<String> errors() { return List.of(); }
        },

        /** Condition only — no FK. */
        WITH_CONDITION_ONLY {
            public GraphitronField field() {
                var condition = new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of());
                return new TableField("actors", null, List.of(new ConditionOnlyStep(condition)));
            }
            public List<String> errors() { return List.of(); }
        },

        /** Key name specified but FK could not be found in the jOOQ catalog. */
        UNRESOLVED_KEY {
            public GraphitronField field() {
                return new TableField("actors", null, List.of(new UnresolvedKeyStep("FILM_ACTOR_FK")));
            }
            public List<String> errors() {
                return List.of("Field 'actors': key 'FILM_ACTOR_FK' could not be resolved in the jOOQ catalog");
            }
        },

        /** Condition method present but could not be resolved via reflection. */
        UNRESOLVED_CONDITION {
            public GraphitronField field() {
                return new TableField("actors", null, List.of(
                    new UnresolvedConditionStep("com.example.Conditions.actorCondition")));
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
