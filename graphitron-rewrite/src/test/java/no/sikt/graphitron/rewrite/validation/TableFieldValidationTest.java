package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.PaginationSpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.stubbedError;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableFieldValidationTest {

    private static ReturnTypeRef.TableBoundReturnType actorReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Actor", new TableRef("actor", "ACTOR", "Actor", List.of()), wrapper);
    }

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — FK auto-inference will be attempted at code-generation time (stubbed)",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), List.of(), new OrderBySpec.None(), null),
            List.of(stubbedError("Film.actors", TableField.class))),

        WITH_FK_PATH("explicit FK path — key resolved to a jOOQ ForeignKey (stubbed)",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.FkJoin("film_actor_film_id_fkey", "", new TableRef("film_actor", "", "", List.of()), null, "")),
                List.of(), new OrderBySpec.None(), null),
            List.of(stubbedError("Film.actors", TableField.class))),

        WITH_CONDITION_ONLY("condition-only join step — no FK (stubbed)",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.ConditionJoin(new MethodRef.Basic("com.example.Conditions", "actorCondition", "org.jooq.Condition", List.of()), "")),
                List.of(), new OrderBySpec.None(), null),
            List.of(stubbedError("Film.actors", TableField.class))),

        FIELD_CONDITION_RESOLVED("resolved @condition on field — adds WHERE clause (stubbed)",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(),
                List.of(new ConditionFilter("com.example.Conditions", "actorCondition", List.of())),
                new OrderBySpec.None(), null),
            List.of(stubbedError("Film.actors", TableField.class))),

        FIELD_CONDITION_RESOLVED_OVERRIDE("resolved @condition with override:true — override applied at build time (stubbed)",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(),
                List.of(new ConditionFilter("com.example.Conditions", "actorCondition", List.of())),
                new OrderBySpec.None(), null),
            List.of(stubbedError("Film.actors", TableField.class))),

        DEFAULT_ORDER_FIELDS("@defaultOrder with explicit fields (stubbed)",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true)),
                List.of(), List.of(),
                new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"), null)), "ASC"),
                null),
            List.of(stubbedError("Film.actors", TableField.class))),

        DEFAULT_ORDER_INDEX("@defaultOrder with named index (stubbed)",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true)),
                List.of(), List.of(),
                new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("last_name", "LAST_NAME", "java.lang.String"), null)), "ASC"),
                null),
            List.of(stubbedError("Film.actors", TableField.class))),

        DEFAULT_ORDER_PRIMARY_KEY("@defaultOrder with primaryKey mode (stubbed)",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true)),
                List.of(), List.of(),
                new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"), null)), "ASC"),
                null),
            List.of(stubbedError("Film.actors", TableField.class))),

        PAGINATED_WITH_ORDERING("connection with pagination and ordering (stubbed)",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.Connection(true, true)),
                List.of(), List.of(),
                new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"), null)), "ASC"),
                new PaginationSpec(
                    new PaginationSpec.PaginationArg("first", "Int", false),
                    null,
                    new PaginationSpec.PaginationArg("after", "String", false),
                    null)),
            List.of(stubbedError("Film.actors", TableField.class))),

        PAGINATED_WITHOUT_ORDERING("connection with pagination but no ordering — error (and stubbed)",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.Connection(true, true)),
                List.of(), List.of(),
                new OrderBySpec.None(),
                new PaginationSpec(
                    new PaginationSpec.PaginationArg("first", "Int", false),
                    null,
                    new PaginationSpec.PaginationArg("after", "String", false),
                    null)),
            List.of("Field 'Film.actors': paginated fields must have ordering (add @defaultOrder or @orderBy)",
                stubbedError("Film.actors", TableField.class)));

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
