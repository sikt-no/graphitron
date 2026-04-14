package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ChildField.RecordTableField;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class RecordTableFieldValidationTest {

    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", List.of()), wrapper);
    }

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — FK auto-inference will be attempted at code-generation time",
            new RecordTableField("Language", "film", null, filmReturn(new FieldWrapper.Single(true)), List.of(), List.of(), new OrderBySpec.None(), null),
            List.of()),

        WITH_FK_PATH("explicit FK path — key resolved to a jOOQ ForeignKey",
            new RecordTableField("Language", "film", null, filmReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.FkJoin("language_film_id_fkey", "film", null)),
                List.of(), new OrderBySpec.None(), null),
            List.of()),

        WITH_CONDITION_ONLY("condition-only join step — no FK",
            new RecordTableField("Language", "film", null, filmReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.ConditionJoin(new MethodRef("com.example.Conditions", "filmCondition", "org.jooq.Condition", List.of()))),
                List.of(), new OrderBySpec.None(), null),
            List.of()),

        FIELD_CONDITION_RESOLVED("resolved @condition on field — adds WHERE clause; no errors",
            new RecordTableField("Language", "films", null, filmReturn(new FieldWrapper.List(true, true)), List.of(),
                List.of(new ConditionFilter(new MethodRef("com.example.Conditions", "filmCondition", "org.jooq.Condition", List.of()))),
                new OrderBySpec.None(), null),
            List.of()),

        VALID_LIST("list return — valid",
            new RecordTableField("Language", "films", null, filmReturn(new FieldWrapper.List(true, true)), List.of(), List.of(), new OrderBySpec.None(), null),
            List.of());

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
    void recordTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
