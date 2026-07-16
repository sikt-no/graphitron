package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.BatchedLookupTableField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validator cases for {@link BatchedLookupTableField}, merged pairwise from the pre-R432
 * {@code SplitLookupTableFieldValidationTest} (the Table-sourced arm) and
 * {@code RecordLookupTableFieldValidationTest} (the Record-sourced arm). The
 * lookup-Connection rejection is an author-facing validator error on both arms — unlike the
 * non-lookup leaf, whose Record arm makes Connection unrepresentable at the constructor.
 */
@UnitTier
class BatchedLookupTableFieldValidationTest {

    private static final TableRef FILM_TABLE = TestFixtures.tableRef("film", "FILM", "Film", List.of());
    private static final LookupMapping EMPTY_LOOKUP = new LookupMapping.ColumnMapping(List.of(), FILM_TABLE);
    private static final List<ColumnRef> PARENT_KEY_COLS = List.of(new ColumnRef("dummy_id", "DUMMY_ID", "java.lang.Integer"));

    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, wrapper);
    }

    private static final ReturnTypeRef.TableBoundReturnType RT_SINGLE = filmReturn(new FieldWrapper.Single(true));
    private static final ReturnTypeRef.TableBoundReturnType RT_LIST = filmReturn(new FieldWrapper.List(true, true));
    private static final ReturnTypeRef.TableBoundReturnType RT_CONN = filmReturn(new FieldWrapper.Connection(true, 100));
    private static final OrderBySpec.Fixed PK_ORDER = new OrderBySpec.Fixed(
        List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"), null, OrderBySpec.SortDirection.ASC)), true);

    // Table-sourced arm key/registration (the former SplitLookupTableField shapes).
    private static final SourceKey T_SOURCE_KEY_CONN = TestFixtures.splitSourceKey(PARENT_KEY_COLS);
    private static final LoaderRegistration T_LR_CONN = TestFixtures.loaderRegistration(RT_CONN, false, false);

    // Record-sourced arm key/registration (the former RecordLookupTableField shapes).
    private static final SourceKey R_SOURCE_KEY_SINGLE = TestFixtures.recordParentRowSourceKey(PARENT_KEY_COLS);
    private static final SourceKey R_SOURCE_KEY_LIST = TestFixtures.recordParentRowSourceKey(PARENT_KEY_COLS);
    private static final SourceKey R_SOURCE_KEY_CONN = TestFixtures.recordParentRowSourceKey(PARENT_KEY_COLS);
    private static final LoaderRegistration R_LR_SINGLE = TestFixtures.loaderRegistration(RT_SINGLE, false, false);
    private static final LoaderRegistration R_LR_LIST = TestFixtures.loaderRegistration(RT_LIST, false, false);
    private static final LoaderRegistration R_LR_CONN = TestFixtures.loaderRegistration(RT_CONN, false, false);

    // Single-cardinality @splitQuery @lookupKey is rejected at classifier time in
    // FieldBuilder; the emitter-level validator no longer carries a fallback check
    // for it. Classifier-level coverage lives in GraphitronSchemaBuilderTest.

    // Record-sourced + condition-join first hop classifies straight to
    // AuthorError upstream; the validator does not surface a deferred-rejection for it.

    private static final List<JoinStep> FK_PATH = List.of(TestFixtures.fkJoin(
        TestFixtures.foreignKeyRef("language_film_id_fkey"), null, List.of(),
        TestFixtures.joinTarget("film"), List.of(), null, ""));
    private static final List<JoinStep> CONDITION_PATH = List.of(TestFixtures.conditionJoin(
        TestFixtures.staticServiceMethodRef("com.example.Conditions", "filmCondition",
            ClassName.get("org.jooq", "Condition"), List.of()),
        TestFixtures.filmTable(), ""));

    enum Case implements ValidatorCase {

        // ---- Table-sourced arm ----

        TABLE_CONNECTION_BLOCKED("Table arm: connection return — not valid on lookup field",
            new BatchedLookupTableField("Language", "films", null, RT_CONN, List.of(), List.of(), new OrderBySpec.None(), null,
                SourceShape.Table, T_SOURCE_KEY_CONN, TestFixtures.fkColumnsLift(), T_LR_CONN, EMPTY_LOOKUP,
                /* parentCorrelation */ null),
            List.of("Field 'Language.films': lookup fields must not return a connection")),

        // ---- Record-sourced arm ----

        RECORD_SINGLE_NO_PATH("Record arm: single cardinality, empty joinPath — emittable post-R61",
            new BatchedLookupTableField("Language", "film", null, RT_SINGLE, List.of(), List.of(), new OrderBySpec.None(), null,
                SourceShape.Record, R_SOURCE_KEY_SINGLE, TestFixtures.fkColumnsLift(), R_LR_SINGLE, EMPTY_LOOKUP,
                /* parentCorrelation */ null),
            List.of()),

        RECORD_SINGLE_WITH_FK_PATH("Record arm: single cardinality with FK path — emittable post-R61",
            new BatchedLookupTableField("Language", "film", null, RT_SINGLE,
                FK_PATH,
                List.of(), new OrderBySpec.None(), null, SourceShape.Record, R_SOURCE_KEY_SINGLE,
                TestFixtures.fkColumnsLift(), R_LR_SINGLE, EMPTY_LOOKUP,
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of()),

        RECORD_LIST_WITH_CONDITION_ONLY("Record arm: list cardinality with condition-only join step — classifies, no validation error (R232)",
            new BatchedLookupTableField("Language", "films", null, RT_LIST,
                CONDITION_PATH,
                List.of(), PK_ORDER, null, SourceShape.Record, R_SOURCE_KEY_LIST,
                TestFixtures.fkColumnsLift(), R_LR_LIST, EMPTY_LOOKUP,
                TestFixtures.pcFor(CONDITION_PATH, TestFixtures.filmTable())),
            List.of()),

        RECORD_LIST_WITH_FK_PATH("Record arm: list cardinality with FK path — emittable, no validation error",
            new BatchedLookupTableField("Language", "films", null, RT_LIST,
                FK_PATH,
                List.of(), PK_ORDER, null, SourceShape.Record, R_SOURCE_KEY_LIST,
                TestFixtures.fkColumnsLift(), R_LR_LIST, EMPTY_LOOKUP,
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of()),

        RECORD_CONNECTION_BLOCKED("Record arm: connection return — lookup-field rejection",
            new BatchedLookupTableField("Language", "films", null, RT_CONN, List.of(), List.of(), new OrderBySpec.None(), null,
                SourceShape.Record, R_SOURCE_KEY_CONN, TestFixtures.fkColumnsLift(), R_LR_CONN, EMPTY_LOOKUP,
                /* parentCorrelation */ null),
            List.of("Field 'Language.films': lookup fields must not return a connection"));

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
    void batchedLookupTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
