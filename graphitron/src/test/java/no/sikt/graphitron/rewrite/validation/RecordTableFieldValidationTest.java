package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ChildField.RecordTableField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class RecordTableFieldValidationTest {

    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.tableRef("film", "FILM", "Film", List.of()), wrapper);
    }

    private static final List<ColumnRef> PARENT_KEY_COLS = List.of(new ColumnRef("dummy_id", "DUMMY_ID", "java.lang.Integer"));
    private static final ReturnTypeRef.TableBoundReturnType RT_SINGLE = filmReturn(new FieldWrapper.Single(true));
    private static final ReturnTypeRef.TableBoundReturnType RT_LIST = filmReturn(new FieldWrapper.List(true, true));
    private static final OrderBySpec.Fixed PK_ORDER = new OrderBySpec.Fixed(
        List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"), null, OrderBySpec.SortDirection.ASC)), true);
    private static final SourceKey SOURCE_KEY_SINGLE = TestFixtures.recordParentRowSourceKey(RT_SINGLE.table(), PARENT_KEY_COLS, false);
    private static final SourceKey SOURCE_KEY_LIST = TestFixtures.recordParentRowSourceKey(RT_LIST.table(), PARENT_KEY_COLS, true);
    private static final LoaderRegistration LR_SINGLE = TestFixtures.loaderRegistration(RT_SINGLE, false, false);
    private static final LoaderRegistration LR_LIST = TestFixtures.loaderRegistration(RT_LIST, false, false);

    // R232: RecordTableField + condition-join first hop classifies straight to AuthorError
    // upstream (the record-backed parent has no @table binding to anchor the condition method's
    // source argument). The cases below construct the model directly, bypassing the parser
    // gate, to confirm the validator does not double-fire on the constructed shape.

    private static final List<JoinStep> FK_PATH = List.of(TestFixtures.fkJoin(
        TestFixtures.foreignKeyRef("language_film_id_fkey"), null, List.of(),
        TestFixtures.joinTarget("film"), List.of(), null, ""));
    private static final List<JoinStep> CONDITION_PATH = List.of(new JoinStep.ConditionJoin(
        TestFixtures.staticServiceMethodRef("com.example.Conditions", "filmCondition",
            ClassName.get("org.jooq", "Condition"), List.of()),
        TestFixtures.filmTable(), ""));

    enum Case implements ValidatorCase {

        SINGLE_NO_PATH("single cardinality, empty joinPath — emittable post-R61 (single-record-per-key arm)",
            new RecordTableField("FilmDetails", "film", null, RT_SINGLE, List.of(), List.of(), new OrderBySpec.None(), null, SOURCE_KEY_SINGLE, LR_SINGLE,
                /* parentCorrelation */ null),
            List.of()),

        SINGLE_WITH_FK_PATH("single cardinality with FK path — emittable post-R61",
            new RecordTableField("FilmDetails", "film", null, RT_SINGLE,
                FK_PATH,
                List.of(), new OrderBySpec.None(), null, SOURCE_KEY_SINGLE, LR_SINGLE,
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of()),

        SINGLE_WITH_CONDITION_ONLY("single cardinality with condition-only join step — classifies, no validation error (R232)",
            new RecordTableField("FilmDetails", "film", null, RT_SINGLE,
                CONDITION_PATH,
                List.of(), new OrderBySpec.None(), null, SOURCE_KEY_SINGLE, LR_SINGLE,
                TestFixtures.pcFor(CONDITION_PATH, TestFixtures.filmTable())),
            List.of()),

        LIST_WITH_CONDITION_ONLY("list cardinality with condition-only join step — classifies, no validation error (R232)",
            new RecordTableField("FilmDetails", "film", null, RT_LIST,
                CONDITION_PATH,
                List.of(), PK_ORDER, null, SOURCE_KEY_LIST, LR_LIST,
                TestFixtures.pcFor(CONDITION_PATH, TestFixtures.filmTable())),
            List.of()),

        LIST_WITH_FK_PATH("list cardinality with FK path — emittable, no validation error",
            new RecordTableField("FilmDetails", "films", null, RT_LIST,
                FK_PATH,
                List.of(), PK_ORDER, null, SOURCE_KEY_LIST, LR_LIST,
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of()),

        // R305 latent-bug guard: a list-valued re-fetch field with OrderBySpec.None (no
        // @orderBy/@defaultOrder and a PK-less target, so OrderByResolver supplies no Fixed(PK)
        // default) must VALIDATE, not be rejected for "list fields must have a deterministic order".
        // A re-fetch field's visible order is locked to the source/target key correspondence (the
        // ORDER BY idx scatter), so it is deterministic regardless of ordering spec. The retired
        // OrderingOwnedByProducer marker never covered RecordTableField, so before R305 switched the
        // exemption to requiresReFetch this PK-less list re-fetch would have been wrongly rejected.
        LIST_NO_ORDER_REFETCH_EXEMPT("PK-less list re-fetch with OrderBySpec.None — exempt via requiresReFetch (R305 latent-bug guard)",
            new RecordTableField("FilmDetails", "films", null, RT_LIST,
                FK_PATH,
                List.of(), new OrderBySpec.None(), null, SOURCE_KEY_LIST, LR_LIST,
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
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
    void recordTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
