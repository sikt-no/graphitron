package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.ChildField.SplitTableField;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class SplitTableFieldValidationTest {

    private static ReturnTypeRef.TableBoundReturnType actorReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Actor", TestFixtures.tableRef("actor", "ACTOR", "Actor", List.of()), wrapper);
    }

    private static final List<ColumnRef> PARENT_KEY_COLS = List.of(new ColumnRef("dummy_id", "DUMMY_ID", "java.lang.Integer"));
    private static final ReturnTypeRef.TableBoundReturnType RT_SINGLE = actorReturn(new FieldWrapper.Single(true));
    private static final ReturnTypeRef.TableBoundReturnType RT_CONN = actorReturn(new FieldWrapper.Connection(false, 100));
    private static final SourceKey SOURCE_KEY_SINGLE = TestFixtures.splitSourceKey(RT_SINGLE.table(), PARENT_KEY_COLS, false);
    private static final SourceKey SOURCE_KEY_CONN = TestFixtures.splitSourceKey(RT_CONN.table(), PARENT_KEY_COLS, true);
    private static final LoaderRegistration LR_SINGLE = TestFixtures.loaderRegistration(RT_SINGLE, false, false);
    private static final LoaderRegistration LR_CONN = TestFixtures.loaderRegistration(RT_CONN, false, false);

    // R232: the @splitQuery + condition-join shape now classifies and emits a real
    // correlated SELECT via SplitRowsMethodEmitter; the validator no longer surfaces a
    // deferred-rejection for it.

    private static final List<JoinStep> FK_PATH = List.of(TestFixtures.fkJoin(
        TestFixtures.foreignKeyRef("film_actor_film_id_fkey"), null, List.of(),
        TestFixtures.joinTarget("film_actor"), List.of(), null, ""));
    private static final List<JoinStep> CONDITION_PATH = List.of(new JoinStep.ConditionJoin(
        TestFixtures.staticServiceMethodRef("com.example.Conditions", "actorCondition",
            ClassName.get("org.jooq", "Condition"), List.of()),
        TestFixtures.actorTable(), ""));

    enum Case implements ValidatorCase {

        // Single-cardinality @splitQuery with a single FK hop — emittable. Positive case:
        // the emitter-level validator produces no errors. Classifier-level rejection of
        // empty / multi-hop single cardinality lives in FieldBuilder and is exercised by
        // GraphitronSchemaBuilderTest, not here.
        SINGLE_CARDINALITY_EMITTABLE("single cardinality with one-hop FK path — emittable, no errors",
            new SplitTableField("Film", "actors", null, RT_SINGLE,
                FK_PATH,
                List.of(), new OrderBySpec.None(), null, SOURCE_KEY_SINGLE, LR_SINGLE,
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of()),

        WITH_CONDITION_ONLY("single cardinality with condition-only join step — classifies and emits (R232)",
            new SplitTableField("Film", "actors", null, RT_SINGLE,
                CONDITION_PATH,
                List.of(), new OrderBySpec.None(), null, SOURCE_KEY_SINGLE, LR_SINGLE,
                TestFixtures.pcFor(CONDITION_PATH, TestFixtures.filmTable())),
            List.of()),

        // plan-split-query-connection.md §1: Split + Connection with no ORDER BY is a build error.
        // ROW_NUMBER() needs a total order to slice partitions deterministically; without one,
        // cursor encoding hashes an empty tuple and pages silently non-deterministically.
        CONNECTION_EMPTY_ORDERBY_NONE("Connection + OrderBySpec.None — build error, non-empty ORDER BY required",
            new SplitTableField("Film", "actors", null, RT_CONN,
                FK_PATH,
                List.of(), new OrderBySpec.None(), null, SOURCE_KEY_CONN, LR_CONN,
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of("Field 'Film.actors': @splitQuery connections require a non-empty ORDER BY "
                + "(add @defaultOrder, @orderBy, or a primary key on the target table)")),

        CONNECTION_EMPTY_ORDERBY_FIXED("Connection + empty OrderBySpec.Fixed — same rejection",
            new SplitTableField("Film", "actors", null, RT_CONN,
                FK_PATH,
                List.of(), new OrderBySpec.Fixed(List.of(), "asc"), null, SOURCE_KEY_CONN, LR_CONN,
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of("Field 'Film.actors': @splitQuery connections require a non-empty ORDER BY "
                + "(add @defaultOrder, @orderBy, or a primary key on the target table)"));

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
    void splitTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
