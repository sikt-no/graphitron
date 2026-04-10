package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.ConditionOnlyRef;
import no.sikt.graphitron.rewrite.field.DefaultOrderSpec;
import no.sikt.graphitron.rewrite.field.FieldWrapper;
import no.sikt.graphitron.rewrite.field.FieldConditionRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkWithConditionRef;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.MethodRef;
import no.sikt.graphitron.rewrite.field.OrderSpec;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.field.SortFieldSpec;
import no.sikt.graphitron.rewrite.field.ChildField.TableField;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable.Plain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableFieldValidationTest {

    private static ReturnTypeRef.TableBoundReturnType actorReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Actor", new Plain("actor", "ACTOR", "Actor", true, List.of(), List.of()), wrapper);
    }

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — FK auto-inference will be attempted at code-generation time",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of()),

        WITH_FK_PATH("explicit FK path — key resolved to a jOOQ ForeignKey",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(new FkRef("film_actor_film_id_fkey", "film", "film_actor", List.of(), List.of())), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of()),

        WITH_FK_AND_CONDITION("FK + resolved condition method in reference path",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(
                new FkWithConditionRef("film_actor_film_id_fkey", "film", "film_actor",
                    new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of()), List.of(), List.of())), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of()),

        WITH_CONDITION_ONLY("condition method only — no FK",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(
                new ConditionOnlyRef(new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of()))), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of()),

        FIELD_CONDITION_RESOLVED("resolved @condition on field — adds WHERE clause; no errors",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), new FieldConditionRef.ResolvedFieldCondition(
                new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of()), false, List.of()), List.of()),
            List.of()),

        FIELD_CONDITION_RESOLVED_OVERRIDE("resolved @condition with override:true — no errors",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), new FieldConditionRef.ResolvedFieldCondition(
                new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of()), true, List.of()), List.of()),
            List.of()),

        FIELD_CONDITION_UNRESOLVED("unresolved @condition method on field — validation error",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), new FieldConditionRef.UnresolvedFieldCondition(
                "com.example.Conditions.missingCondition", false, List.of()), List.of()),
            List.of("Field 'actors': condition method 'com.example.Conditions.missingCondition' could not be resolved")),

        DEFAULT_ORDER_FIELDS("@defaultOrder with explicit fields — valid",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true,
                    new DefaultOrderSpec(new OrderSpec.FieldsOrder(List.of(new SortFieldSpec("actor_id", null))), "ASC"),
                    List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of()),

        DEFAULT_ORDER_INDEX("@defaultOrder with named index — valid",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.IndexOrder("IDX_ACTOR_LAST_NAME"), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of()),

        DEFAULT_ORDER_PRIMARY_KEY("@defaultOrder with primaryKey mode — valid",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.PrimaryKeyOrder(), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of()),

        DEFAULT_ORDER_UNRESOLVED_INDEX("@defaultOrder references an index that could not be found — validation error",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of("Field 'actors': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        DEFAULT_ORDER_UNRESOLVED_PRIMARY_KEY("@defaultOrder uses primaryKey but the table has none — validation error",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedPrimaryKeyOrder(), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of("Field 'actors': primary key could not be resolved — the table may not have one")),

        CONNECTION_DEFAULT_ORDER_UNRESOLVED_INDEX("connection cardinality: @defaultOrder references an index that could not be found — validation error",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.Connection(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of("Field 'actors': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        CONNECTION_DEFAULT_ORDER_UNRESOLVED_PRIMARY_KEY("connection cardinality: @defaultOrder uses primaryKey but the table has none — validation error",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.Connection(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedPrimaryKeyOrder(), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), List.of()),
            List.of("Field 'actors': primary key could not be resolved — the table may not have one"));

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
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
