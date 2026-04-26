package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ChildField.TableMethodField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.stubbedError;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableMethodFieldValidationTest {

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — FK auto-inference will be attempted at code-generation time (stubbed)",
            new TableMethodField("Film", "filteredActors", null, new ReturnTypeRef.ScalarReturnType("Film", new FieldWrapper.Single(true)),
                List.of(), new MethodRef.Basic("com.example.TableMethods", "filteredActors", ClassName.get("org.jooq", "Table"), List.of())),
            List.of(stubbedError("Film.filteredActors", TableMethodField.class))),

        WITH_FK_PATH("explicit FK path — key resolved to a jOOQ ForeignKey (stubbed)",
            new TableMethodField("Film", "filteredActors", null, new ReturnTypeRef.ScalarReturnType("Film", new FieldWrapper.Single(true)), List.of(
                new JoinStep.FkJoin("film_actor_film_id_fkey", "", null, List.of(), new TableRef("film_actor", "", "", List.of()), List.of(), null, "")),
                new MethodRef.Basic("com.example.TableMethods", "filteredActors", ClassName.get("org.jooq", "Table"), List.of())),
            List.of(stubbedError("Film.filteredActors", TableMethodField.class))),

        WITH_CONDITION_ONLY("condition method only — no FK (stubbed)",
            new TableMethodField("Film", "filteredActors", null, new ReturnTypeRef.ScalarReturnType("Film", new FieldWrapper.Single(true)), List.of(
                new JoinStep.ConditionJoin(new MethodRef.Basic("com.example.Conditions", "actorCondition", ClassName.get("org.jooq", "Condition"), List.of()), "")),
                new MethodRef.Basic("com.example.TableMethods", "filteredActors", ClassName.get("org.jooq", "Table"), List.of())),
            List.of(stubbedError("Film.filteredActors", TableMethodField.class)));

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
    void tableMethodFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
