package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ChildField.RecordTableField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
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

    private static final BatchKey.RecordParentBatchKey BATCH_KEY = new BatchKey.RowKeyed(List.of());

    // Validator messages for RecordTableField. Kept inline — a change to the production string
    // breaks this test loudly and must be updated in the same commit.
    //
    // CONDITION_JOIN_STUB comes from the existing SplitRowsMethodEmitter.unsupportedReason
    // runtime-stub delegation. The single-cardinality gate (Invariant #10) was lifted in R61
    // alongside emitsSingleRecordPerKey extending to single-cardinality fields; cases below
    // exercise the new acceptance path on both cardinalities.
    private static final String CONDITION_JOIN_STUB =
        "Field 'FilmDetails.film': RecordTableField 'FilmDetails.film' with a condition-join step "
        + "cannot be emitted until classification-vocabulary item 5 resolves condition-method target tables";

    enum Case implements ValidatorCase {

        SINGLE_NO_PATH("single cardinality, empty joinPath — emittable post-R61 (single-record-per-key arm)",
            new RecordTableField("FilmDetails", "film", null, filmReturn(new FieldWrapper.Single(true)), List.of(), List.of(), new OrderBySpec.None(), null, BATCH_KEY),
            List.of()),

        SINGLE_WITH_FK_PATH("single cardinality with FK path — emittable post-R61",
            new RecordTableField("FilmDetails", "film", null, filmReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.FkJoin("language_film_id_fkey", null, null, List.of(), TestFixtures.joinTarget("film"), List.of(), null, "")),
                List.of(), new OrderBySpec.None(), null, BATCH_KEY),
            List.of()),

        SINGLE_WITH_CONDITION_ONLY("single cardinality with condition-only join step — condition-join stub surfaces as build error",
            new RecordTableField("FilmDetails", "film", null, filmReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.ConditionJoin(new MethodRef.Basic("com.example.Conditions", "filmCondition", ClassName.get("org.jooq", "Condition"), List.of()), "")),
                List.of(), new OrderBySpec.None(), null, BATCH_KEY),
            List.of(CONDITION_JOIN_STUB)),

        LIST_WITH_CONDITION_ONLY("list cardinality with condition-only join step — condition-join stub surfaces as build error",
            new RecordTableField("FilmDetails", "film", null, filmReturn(new FieldWrapper.List(true, true)),
                List.of(new JoinStep.ConditionJoin(new MethodRef.Basic("com.example.Conditions", "filmCondition", ClassName.get("org.jooq", "Condition"), List.of()), "")),
                List.of(), new OrderBySpec.None(), null, BATCH_KEY),
            List.of(CONDITION_JOIN_STUB)),

        LIST_WITH_FK_PATH("list cardinality with FK path — emittable, no validation error",
            new RecordTableField("FilmDetails", "films", null, filmReturn(new FieldWrapper.List(true, true)),
                List.of(new JoinStep.FkJoin("language_film_id_fkey", null, null, List.of(), TestFixtures.joinTarget("film"), List.of(), null, "")),
                List.of(), new OrderBySpec.None(), null, BATCH_KEY),
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
