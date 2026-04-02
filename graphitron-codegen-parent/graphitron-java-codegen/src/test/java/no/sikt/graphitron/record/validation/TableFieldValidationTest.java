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
import no.sikt.graphitron.record.field.UnresolvedKeyAndConditionStep;
import no.sikt.graphitron.record.field.UnresolvedKeyStep;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableFieldValidationTest {

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — FK auto-inference will be attempted at code-generation time",
            new TableField("actors", null, List.of()),
            List.of()),

        WITH_FK_PATH("explicit FK path — key resolved to a jOOQ ForeignKey",
            new TableField("actors", null, List.of(new FkStep(Keys.FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY))),
            List.of()),

        WITH_FK_AND_CONDITION("FK + resolved condition method",
            new TableField("actors", null, List.of(
                new FkWithConditionStep(Keys.FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY,
                    new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of())))),
            List.of()),

        WITH_CONDITION_ONLY("condition method only — no FK",
            new TableField("actors", null, List.of(
                new ConditionOnlyStep(new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of())))),
            List.of()),

        UNRESOLVED_KEY("key name specified but FK could not be found in the jOOQ catalog",
            new TableField("actors", null, List.of(new UnresolvedKeyStep("FILM_ACTOR_FK"))),
            List.of("Field 'actors': key 'FILM_ACTOR_FK' could not be resolved in the jOOQ catalog")),

        UNRESOLVED_CONDITION("condition method present but could not be resolved via reflection",
            new TableField("actors", null, List.of(
                new UnresolvedConditionStep("com.example.Conditions.actorCondition"))),
            List.of("Field 'actors': condition method 'com.example.Conditions.actorCondition' could not be resolved")),

        UNRESOLVED_KEY_AND_CONDITION("both key and condition specified, neither could be resolved — two errors",
            new TableField("actors", null, List.of(
                new UnresolvedKeyAndConditionStep("FILM_ACTOR_FK", "com.example.Conditions.actorCondition"))),
            List.of(
                "Field 'actors': key 'FILM_ACTOR_FK' could not be resolved in the jOOQ catalog",
                "Field 'actors': condition method 'com.example.Conditions.actorCondition' could not be resolved"));

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        Case(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void tableFieldValidation(Case tc) {
        assertThat(validate(inTableTypeSchema("Film", "actors", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
