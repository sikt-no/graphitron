package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.jooq.generated.testdata.public_.Keys;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ReferencePathElementRef.ConditionOnlyRef;
import no.sikt.graphitron.record.field.FieldCardinality;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.ChildField.TableMethodField;
import no.sikt.graphitron.record.field.ReturnTypeRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyAndConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableMethodFieldValidationTest {

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — FK auto-inference will be attempted at code-generation time",
            new TableMethodField("Film", "filteredActors", null, new ReturnTypeRef.OtherReturnType("Film"), List.of(), new FieldCardinality.Single()),
            List.of()),

        WITH_FK_PATH("explicit FK path — key resolved to a jOOQ ForeignKey",
            new TableMethodField("Film", "filteredActors", null, new ReturnTypeRef.OtherReturnType("Film"), List.of(
                new FkRef(Keys.FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY)), new FieldCardinality.Single()),
            List.of()),

        WITH_CONDITION_ONLY("condition method only — no FK",
            new TableMethodField("Film", "filteredActors", null, new ReturnTypeRef.OtherReturnType("Film"), List.of(
                new ConditionOnlyRef(new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of()))), new FieldCardinality.Single()),
            List.of()),

        UNRESOLVED_KEY("key name specified but FK could not be found in the jOOQ catalog",
            new TableMethodField("Film", "filteredActors", null, new ReturnTypeRef.OtherReturnType("Film"), List.of(new UnresolvedKeyRef("FILM_ACTOR_FK")), new FieldCardinality.Single()),
            List.of("Field 'filteredActors': key 'FILM_ACTOR_FK' could not be resolved in the jOOQ catalog")),

        UNRESOLVED_CONDITION("condition method present but could not be resolved via reflection",
            new TableMethodField("Film", "filteredActors", null, new ReturnTypeRef.OtherReturnType("Film"), List.of(
                new UnresolvedConditionRef("com.example.Conditions.actorCondition")), new FieldCardinality.Single()),
            List.of("Field 'filteredActors': condition method 'com.example.Conditions.actorCondition' could not be resolved")),

        UNRESOLVED_KEY_AND_CONDITION("both key and condition specified, neither could be resolved — two errors",
            new TableMethodField("Film", "filteredActors", null, new ReturnTypeRef.OtherReturnType("Film"), List.of(
                new UnresolvedKeyAndConditionRef("FILM_ACTOR_FK", "com.example.Conditions.actorCondition")), new FieldCardinality.Single()),
            List.of(
                "Field 'filteredActors': key 'FILM_ACTOR_FK' could not be resolved in the jOOQ catalog",
                "Field 'filteredActors': condition method 'com.example.Conditions.actorCondition' could not be resolved"));

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
