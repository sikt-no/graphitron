package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validator cases for {@link BatchedTableField}, merged pairwise from the pre-R432
 * {@code SplitTableFieldValidationTest} (the Table-sourced arm) and
 * {@code RecordTableFieldValidationTest} (the Record-sourced arm). The validate method is one
 * shared path since the merge; the Connection-requires-ORDER-BY guard is reachable only from the
 * Table-sourced arm (the leaf's ctor rejects Record + Connection).
 */
@UnitTier
class BatchedTableFieldValidationTest {

    private static ReturnTypeRef.TableBoundReturnType actorReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Actor", TestFixtures.tableRef("actor", "ACTOR", "Actor", List.of()), wrapper);
    }

    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.tableRef("film", "FILM", "Film", List.of()), wrapper);
    }

    private static final List<ColumnRef> PARENT_KEY_COLS = List.of(new ColumnRef("dummy_id", "DUMMY_ID", "java.lang.Integer"));

    // ===== Table-sourced arm (the former SplitTableField cases) =====

    private static final ReturnTypeRef.TableBoundReturnType T_RT_SINGLE = actorReturn(new FieldWrapper.Single(true));
    private static final ReturnTypeRef.TableBoundReturnType T_RT_CONN = actorReturn(new FieldWrapper.Connection(false, 100));
    private static final SourceKey T_SOURCE_KEY_SINGLE = TestFixtures.splitSourceKey(PARENT_KEY_COLS);
    private static final SourceKey T_SOURCE_KEY_CONN = TestFixtures.splitSourceKey(PARENT_KEY_COLS);
    private static final LoaderRegistration T_LR_SINGLE = TestFixtures.loaderRegistration(T_RT_SINGLE, false, false);
    private static final LoaderRegistration T_LR_CONN = TestFixtures.loaderRegistration(T_RT_CONN, false, false);

    // R232: the @splitQuery + condition-join shape now classifies and emits a real
    // correlated SELECT via SplitRowsMethodEmitter; the validator no longer surfaces a
    // deferred-rejection for it.

    private static final List<JoinStep> T_FK_PATH = List.of(TestFixtures.fkJoin(
        TestFixtures.foreignKeyRef("film_actor_film_id_fkey"), null, List.of(),
        TestFixtures.joinTarget("film_actor"), List.of(), null, ""));
    private static final List<JoinStep> T_CONDITION_PATH = List.of(TestFixtures.conditionJoin(
        TestFixtures.staticServiceMethodRef("com.example.Conditions", "actorCondition",
            ClassName.get("org.jooq", "Condition"), List.of()),
        TestFixtures.actorTable(), ""));

    // ===== Record-sourced arm (the former RecordTableField cases) =====

    private static final ReturnTypeRef.TableBoundReturnType R_RT_SINGLE = filmReturn(new FieldWrapper.Single(true));
    private static final ReturnTypeRef.TableBoundReturnType R_RT_LIST = filmReturn(new FieldWrapper.List(true, true));
    private static final OrderBySpec.Fixed R_PK_ORDER = new OrderBySpec.Fixed(
        List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"), null, OrderBySpec.SortDirection.ASC)), true);
    private static final SourceKey R_SOURCE_KEY_SINGLE = TestFixtures.recordParentRowSourceKey(PARENT_KEY_COLS);
    private static final SourceKey R_SOURCE_KEY_LIST = TestFixtures.recordParentRowSourceKey(PARENT_KEY_COLS);
    private static final LoaderRegistration R_LR_SINGLE = TestFixtures.loaderRegistration(R_RT_SINGLE, false, false);
    private static final LoaderRegistration R_LR_LIST = TestFixtures.loaderRegistration(R_RT_LIST, false, false);

    // R232: record-sourced + condition-join first hop classifies straight to AuthorError
    // upstream (the record-backed parent has no @table binding to anchor the condition method's
    // source argument). The cases below construct the model directly, bypassing the parser
    // gate, to confirm the validator does not double-fire on the constructed shape.

    private static final List<JoinStep> R_FK_PATH = List.of(TestFixtures.fkJoin(
        TestFixtures.foreignKeyRef("language_film_id_fkey"), null, List.of(),
        TestFixtures.joinTarget("film"), List.of(), null, ""));
    private static final List<JoinStep> R_CONDITION_PATH = List.of(TestFixtures.conditionJoin(
        TestFixtures.staticServiceMethodRef("com.example.Conditions", "filmCondition",
            ClassName.get("org.jooq", "Condition"), List.of()),
        TestFixtures.filmTable(), ""));

    enum Case implements ValidatorCase {

        // ---- Table-sourced arm ----

        // Single-cardinality @splitQuery with a single FK hop — emittable. Positive case:
        // the emitter-level validator produces no errors. Classifier-level rejection of
        // empty / multi-hop single cardinality lives in FieldBuilder and is exercised by
        // GraphitronSchemaBuilderTest, not here.
        TABLE_SINGLE_CARDINALITY_EMITTABLE("Table arm: single cardinality with one-hop FK path — emittable, no errors",
            new BatchedTableField("Film", "actors", null, T_RT_SINGLE,
                T_FK_PATH,
                List.of(), new OrderBySpec.None(), null, SourceShape.Table, T_SOURCE_KEY_SINGLE,
                TestFixtures.fkColumnsLift(), T_LR_SINGLE,
                TestFixtures.pcFor(T_FK_PATH, TestFixtures.filmTable())),
            List.of()),

        TABLE_WITH_CONDITION_ONLY("Table arm: single cardinality with condition-only join step — classifies and emits (R232)",
            new BatchedTableField("Film", "actors", null, T_RT_SINGLE,
                T_CONDITION_PATH,
                List.of(), new OrderBySpec.None(), null, SourceShape.Table, T_SOURCE_KEY_SINGLE,
                TestFixtures.fkColumnsLift(), T_LR_SINGLE,
                TestFixtures.pcFor(T_CONDITION_PATH, TestFixtures.filmTable())),
            List.of()),

        // plan-split-query-connection.md §1: Split + Connection with no ORDER BY is a build error.
        // ROW_NUMBER() needs a total order to slice partitions deterministically; without one,
        // cursor encoding hashes an empty tuple and pages silently non-deterministically.
        TABLE_CONNECTION_EMPTY_ORDERBY_NONE("Table arm: Connection + OrderBySpec.None — build error, non-empty ORDER BY required",
            new BatchedTableField("Film", "actors", null, T_RT_CONN,
                T_FK_PATH,
                List.of(), new OrderBySpec.None(), null, SourceShape.Table, T_SOURCE_KEY_CONN,
                TestFixtures.fkColumnsLift(), T_LR_CONN,
                TestFixtures.pcFor(T_FK_PATH, TestFixtures.filmTable())),
            List.of("Field 'Film.actors': @splitQuery connections require a non-empty ORDER BY "
                + "(add @defaultOrder, @orderBy, or a primary key on the target table)")),

        TABLE_CONNECTION_EMPTY_ORDERBY_FIXED("Table arm: Connection + empty OrderBySpec.Fixed — same rejection",
            new BatchedTableField("Film", "actors", null, T_RT_CONN,
                T_FK_PATH,
                List.of(), new OrderBySpec.Fixed(List.of(), true), null, SourceShape.Table, T_SOURCE_KEY_CONN,
                TestFixtures.fkColumnsLift(), T_LR_CONN,
                TestFixtures.pcFor(T_FK_PATH, TestFixtures.filmTable())),
            List.of("Field 'Film.actors': @splitQuery connections require a non-empty ORDER BY "
                + "(add @defaultOrder, @orderBy, or a primary key on the target table)")),

        // ---- Record-sourced arm ----

        RECORD_SINGLE_NO_PATH("Record arm: single cardinality, empty joinPath — emittable post-R61 (single-record-per-key arm)",
            new BatchedTableField("FilmDetails", "film", null, R_RT_SINGLE, List.of(), List.of(), new OrderBySpec.None(), null,
                SourceShape.Record, R_SOURCE_KEY_SINGLE, TestFixtures.fkColumnsLift(), R_LR_SINGLE,
                /* parentCorrelation */ null),
            List.of()),

        RECORD_SINGLE_WITH_FK_PATH("Record arm: single cardinality with FK path — emittable post-R61",
            new BatchedTableField("FilmDetails", "film", null, R_RT_SINGLE,
                R_FK_PATH,
                List.of(), new OrderBySpec.None(), null, SourceShape.Record, R_SOURCE_KEY_SINGLE,
                TestFixtures.fkColumnsLift(), R_LR_SINGLE,
                TestFixtures.pcFor(R_FK_PATH, TestFixtures.filmTable())),
            List.of()),

        RECORD_SINGLE_WITH_CONDITION_ONLY("Record arm: single cardinality with condition-only join step — classifies, no validation error (R232)",
            new BatchedTableField("FilmDetails", "film", null, R_RT_SINGLE,
                R_CONDITION_PATH,
                List.of(), new OrderBySpec.None(), null, SourceShape.Record, R_SOURCE_KEY_SINGLE,
                TestFixtures.fkColumnsLift(), R_LR_SINGLE,
                TestFixtures.pcFor(R_CONDITION_PATH, TestFixtures.filmTable())),
            List.of()),

        RECORD_LIST_WITH_CONDITION_ONLY("Record arm: list cardinality with condition-only join step — classifies, no validation error (R232)",
            new BatchedTableField("FilmDetails", "film", null, R_RT_LIST,
                R_CONDITION_PATH,
                List.of(), R_PK_ORDER, null, SourceShape.Record, R_SOURCE_KEY_LIST,
                TestFixtures.fkColumnsLift(), R_LR_LIST,
                TestFixtures.pcFor(R_CONDITION_PATH, TestFixtures.filmTable())),
            List.of()),

        RECORD_LIST_WITH_FK_PATH("Record arm: list cardinality with FK path — emittable, no validation error",
            new BatchedTableField("FilmDetails", "films", null, R_RT_LIST,
                R_FK_PATH,
                List.of(), R_PK_ORDER, null, SourceShape.Record, R_SOURCE_KEY_LIST,
                TestFixtures.fkColumnsLift(), R_LR_LIST,
                TestFixtures.pcFor(R_FK_PATH, TestFixtures.filmTable())),
            List.of()),

        // R305 latent-bug guard: a list-valued re-fetch field with OrderBySpec.None (no
        // @orderBy/@defaultOrder and a PK-less target, so OrderByResolver supplies no Fixed(PK)
        // default) must VALIDATE, not be rejected for "list fields must have a deterministic order".
        // A re-fetch field's visible order is locked to the source/target key correspondence (the
        // ORDER BY idx scatter), so it is deterministic regardless of ordering spec. The retired
        // OrderingOwnedByProducer marker never covered the record-sourced arm, so before R305
        // switched the exemption to requiresReFetch this PK-less list re-fetch would have been
        // wrongly rejected.
        RECORD_LIST_NO_ORDER_REFETCH_EXEMPT("Record arm: PK-less list re-fetch with OrderBySpec.None — exempt via requiresReFetch (R305 latent-bug guard)",
            new BatchedTableField("FilmDetails", "films", null, R_RT_LIST,
                R_FK_PATH,
                List.of(), new OrderBySpec.None(), null, SourceShape.Record, R_SOURCE_KEY_LIST,
                TestFixtures.fkColumnsLift(), R_LR_LIST,
                TestFixtures.pcFor(R_FK_PATH, TestFixtures.filmTable())),
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
    void batchedTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
