package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
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

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class TableFieldValidationTest {

    private static ReturnTypeRef.TableBoundReturnType actorReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Actor", TestFixtures.tableRef("actor", "ACTOR", "Actor", List.of()), wrapper);
    }

    private static final List<JoinStep> CONDITION_PATH = List.of(new JoinStep.ConditionJoin(
        new JoinConditionRef(TestFixtures.staticServiceMethodRef("com.example.Conditions", "actorCondition",
            ClassName.get("org.jooq", "Condition"), List.of())),
        TestFixtures.actorTable(),
        ""));

    private static final List<JoinStep> FK_PATH = List.of(TestFixtures.fkJoin(
        TestFixtures.foreignKeyRef("film_actor_film_id_fkey"), null, List.of(),
        TestFixtures.joinTarget("film_actor"), List.of(), null, ""));

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — FK auto-inference will be attempted at code-generation time",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), List.of(), new OrderBySpec.None(), null,
                /* parentCorrelation */ null),
            List.of()),

        WITH_FK_PATH("explicit FK path — key resolved to a jOOQ ForeignKey",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)),
                FK_PATH,
                List.of(), new OrderBySpec.None(), null,
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of()),

        SINGLE_WITH_CONDITION_ONLY("single cardinality with condition-only join step — classifies and emits a correlated subquery (R232)",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)),
                CONDITION_PATH,
                List.of(), new OrderBySpec.None(), null,
                TestFixtures.pcFor(CONDITION_PATH, TestFixtures.filmTable())),
            List.of()),

        LIST_WITH_CONDITION_ONLY("list cardinality with condition-only join step — classifies and emits a correlated subquery (R232)",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.List(true, true)),
                CONDITION_PATH,
                List.of(),
                new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"), null, OrderBySpec.SortDirection.ASC)), true),
                null,
                TestFixtures.pcFor(CONDITION_PATH, TestFixtures.filmTable())),
            List.of()),

        FIELD_CONDITION_RESOLVED("resolved @condition on field — adds WHERE clause",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(),
                List.of(new ConditionFilter("com.example.Conditions", "actorCondition", List.of())),
                new OrderBySpec.None(), null,
                /* parentCorrelation */ null),
            List.of()),

        FIELD_CONDITION_RESOLVED_OVERRIDE("resolved @condition with override:true — override applied at build time",
            new TableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(),
                List.of(new ConditionFilter("com.example.Conditions", "actorCondition", List.of())),
                new OrderBySpec.None(), null,
                /* parentCorrelation */ null),
            List.of()),

        DEFAULT_ORDER_FIELDS("@defaultOrder with explicit fields",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true)),
                List.of(), List.of(),
                new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"), null, OrderBySpec.SortDirection.ASC)), true),
                null,
                /* parentCorrelation */ null),
            List.of()),

        DEFAULT_ORDER_INDEX("@defaultOrder with named index",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true)),
                List.of(), List.of(),
                new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("last_name", "LAST_NAME", "java.lang.String"), null, OrderBySpec.SortDirection.ASC)), true),
                null,
                /* parentCorrelation */ null),
            List.of()),

        DEFAULT_ORDER_PRIMARY_KEY("@defaultOrder with primaryKey mode",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.List(true, true)),
                List.of(), List.of(),
                new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"), null, OrderBySpec.SortDirection.ASC)), true),
                null,
                /* parentCorrelation */ null),
            List.of()),

        PAGINATED_WITH_ORDERING("connection with pagination and ordering",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.Connection(true, 100)),
                List.of(), List.of(),
                new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"), null, OrderBySpec.SortDirection.ASC)), true),
                new PaginationSpec(
                    new PaginationSpec.PaginationArg("Int", false),
                    null,
                    new PaginationSpec.PaginationArg("String", false),
                    null),
                /* parentCorrelation */ null),
            List.of()),

        PAGINATED_WITHOUT_ORDERING("connection with pagination but no ordering — error",
            new TableField("Film", "actors", null,
                actorReturn(new FieldWrapper.Connection(true, 100)),
                List.of(), List.of(),
                new OrderBySpec.None(),
                new PaginationSpec(
                    new PaginationSpec.PaginationArg("Int", false),
                    null,
                    new PaginationSpec.PaginationArg("String", false),
                    null),
                /* parentCorrelation */ null),
            List.of("Field 'Film.actors': paginated fields must have ordering (add @defaultOrder or @orderBy)"));

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
