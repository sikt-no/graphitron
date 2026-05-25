package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ChildField.TableMethodField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class TableMethodFieldValidationTest {

    enum Case implements ValidatorCase {

        NO_PATH("no @reference path — validator passes; classifier ensures auto-FK inference / explicit @reference at SDL time (R43 commit 2)",
            new TableMethodField("Film", "filteredActors", null, TestFixtures.tableBoundFilm(new FieldWrapper.Single(true)),
                List.of(), TestFixtures.staticServiceMethodRef("com.example.TableMethods", "filteredActors", ClassName.get("org.jooq", "Table"), List.of()),
                Optional.empty()),
            List.of()),

        WITH_FK_PATH("explicit single-hop FK path — emit ships in R43 commit 3, validator passes",
            new TableMethodField("Film", "filteredActors", null, TestFixtures.tableBoundFilm(new FieldWrapper.Single(true)), List.of(
                TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_actor_film_id_fkey"), null, List.of(), TestFixtures.joinTarget("film_actor"), List.of(), null, "")),
                TestFixtures.staticServiceMethodRef("com.example.TableMethods", "filteredActors", ClassName.get("org.jooq", "Table"), List.of()),
                Optional.empty()),
            List.of()),

        WITH_CONDITION_ONLY("condition method terminal — emitter throws at runtime, validator passes (multi-hop / condition emit are follow-ups)",
            new TableMethodField("Film", "filteredActors", null, TestFixtures.tableBoundFilm(new FieldWrapper.Single(true)), List.of(
                new JoinStep.ConditionJoin(TestFixtures.staticServiceMethodRef("com.example.Conditions", "actorCondition", ClassName.get("org.jooq", "Condition"), List.of()), TestFixtures.actorTable(), "")),
                TestFixtures.staticServiceMethodRef("com.example.TableMethods", "filteredActors", ClassName.get("org.jooq", "Table"), List.of()),
                Optional.empty()),
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
    void tableMethodFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }

    /**
     * Drives the {@code @tableMethod}-must-be-static rejection end-to-end: builds a real schema
     * bound to {@link no.sikt.graphitron.rewrite.TestTableMethodStub}'s instance method
     * {@code getFilmInstance}, runs through {@code GraphitronSchemaBuilder} +
     * {@code GraphitronSchemaValidator}, and asserts the validator surfaces the new rejection
     * arm. Mirrors the @service-side instance-holder validator test in
     * {@code ServiceFieldValidationTest}; the two together pin "validator mirrors classifier
     * invariants" for the static-vs-instance axis on both producer arms.
     */
    @org.junit.jupiter.api.Test
    void instanceTableMethod_validatorReportsAuthorError() {
        var schema = no.sikt.graphitron.rewrite.TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film!]!
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getFilmInstance")
            }
            """);

        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message)
            .filteredOn(m -> m.contains("Query.films"))
            .as("validator surfaces the @tableMethod must-be-static rejection through its own surface")
            .singleElement()
            .satisfies(message -> assertThat(message).contains("must be declared 'static'"));
    }
}
