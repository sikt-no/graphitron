package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.jooq.generated.testdata.public_.Keys;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ReferencePathElementRef.ConditionOnlyRef;
import no.sikt.graphitron.record.field.DefaultOrderSpec;
import no.sikt.graphitron.record.field.FieldWrapper;
import no.sikt.graphitron.record.field.FieldConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkWithConditionRef;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.OrderSpec;
import no.sikt.graphitron.record.field.ReturnTypeRef;
import no.sikt.graphitron.record.field.SortFieldSpec;
import no.sikt.graphitron.record.field.ChildField.TableField;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyAndConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyRef;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.ACTOR;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableFieldValidationTest {

    private static ReturnTypeRef actorReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Actor", new ResolvedTable("actor", "ACTOR", ACTOR), wrapper);
    }

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — FK auto-inference will be attempted at code-generation time",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), new FieldConditionRef.NoFieldCondition(), false),
            List.of()),

        WITH_FK_PATH("explicit FK path — key resolved to a jOOQ ForeignKey",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(new FkRef(Keys.FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY)), new FieldConditionRef.NoFieldCondition(), false),
            List.of()),

        WITH_FK_AND_CONDITION("FK + resolved condition method in reference path",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(
                new FkWithConditionRef(Keys.FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY,
                    new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of()))), new FieldConditionRef.NoFieldCondition(), false),
            List.of()),

        WITH_CONDITION_ONLY("condition method only — no FK",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(
                new ConditionOnlyRef(new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of()))), new FieldConditionRef.NoFieldCondition(), false),
            List.of()),

        UNRESOLVED_KEY("key name specified but FK could not be found in the jOOQ catalog",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(new UnresolvedKeyRef("FILM_ACTOR_FK")), new FieldConditionRef.NoFieldCondition(), false),
            List.of("Field 'actors': key 'FILM_ACTOR_FK' could not be resolved in the jOOQ catalog")),

        UNRESOLVED_CONDITION("condition method present but could not be resolved via reflection",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(
                new UnresolvedConditionRef("com.example.Conditions.actorCondition")), new FieldConditionRef.NoFieldCondition(), false),
            List.of("Field 'actors': condition method 'com.example.Conditions.actorCondition' could not be resolved")),

        UNRESOLVED_KEY_AND_CONDITION("both key and condition specified, neither could be resolved — two errors",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(
                new UnresolvedKeyAndConditionRef("FILM_ACTOR_FK", "com.example.Conditions.actorCondition")), new FieldConditionRef.NoFieldCondition(), false),
            List.of(
                "Field 'actors': key 'FILM_ACTOR_FK' could not be resolved in the jOOQ catalog",
                "Field 'actors': condition method 'com.example.Conditions.actorCondition' could not be resolved")),

        FIELD_CONDITION_RESOLVED("resolved @condition on field — adds WHERE clause; no errors",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), new FieldConditionRef.ResolvedFieldCondition(
                new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of()), false, List.of()), false),
            List.of()),

        FIELD_CONDITION_RESOLVED_OVERRIDE("resolved @condition with override:true — no errors",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), new FieldConditionRef.ResolvedFieldCondition(
                new MethodRef("com.example.Conditions.actorCondition", "org.jooq.Condition", List.of()), true, List.of()), false),
            List.of()),

        FIELD_CONDITION_UNRESOLVED("unresolved @condition method on field — validation error",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), new FieldConditionRef.UnresolvedFieldCondition(
                "com.example.Conditions.missingCondition", false, List.of()), false),
            List.of("Field 'actors': condition method 'com.example.Conditions.missingCondition' could not be resolved")),

        DEFAULT_ORDER_FIELDS("@defaultOrder with explicit fields — valid",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true,
                    new DefaultOrderSpec(new OrderSpec.FieldsOrder(List.of(new SortFieldSpec("actor_id", null))), "ASC"),
                    List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), false),
            List.of()),

        DEFAULT_ORDER_INDEX("@defaultOrder with named index — valid",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.IndexOrder("IDX_ACTOR_LAST_NAME"), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), false),
            List.of()),

        DEFAULT_ORDER_PRIMARY_KEY("@defaultOrder with primaryKey mode — valid",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.PrimaryKeyOrder(), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), false),
            List.of()),

        DEFAULT_ORDER_UNRESOLVED_INDEX("@defaultOrder references an index that could not be found — validation error",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), false),
            List.of("Field 'actors': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        DEFAULT_ORDER_UNRESOLVED_PRIMARY_KEY("@defaultOrder uses primaryKey but the table has none — validation error",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedPrimaryKeyOrder(), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), false),
            List.of("Field 'actors': primary key could not be resolved — the table may not have one")),

        CONNECTION_DEFAULT_ORDER_UNRESOLVED_INDEX("connection cardinality: @defaultOrder references an index that could not be found — validation error",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.Connection(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), false),
            List.of("Field 'actors': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        CONNECTION_DEFAULT_ORDER_UNRESOLVED_PRIMARY_KEY("connection cardinality: @defaultOrder uses primaryKey but the table has none — validation error",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.Connection(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedPrimaryKeyOrder(), "ASC"), List.of())),
                List.of(), new FieldConditionRef.NoFieldCondition(), false),
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
