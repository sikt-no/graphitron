package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class RecordLookupTableFieldValidationTest {

    private static final TableRef FILM_TABLE = new TableRef("film", "FILM", "Film", List.of());
    private static final LookupMapping EMPTY_LOOKUP = new LookupMapping(List.of(), FILM_TABLE);
    private static final BatchKey BATCH_KEY = new BatchKey.RowKeyed(List.of());

    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, wrapper);
    }

    // Validator messages for the intra-variant runtime-stub branches of
    // SplitRowsMethodEmitter.unsupportedReason(RecordLookupTableField). Kept inline — a change to
    // the production string breaks this test loudly and must be updated in the same commit.
    private static final String SINGLE_CARDINALITY_STUB =
        "Field 'Language.film': Single-cardinality RecordLookupTableField on 'Language.film' "
        + "not yet supported; list cardinality only.";
    private static final String CONDITION_JOIN_STUB =
        "Field 'Language.films': RecordLookupTableField 'Language.films' with a condition-join step "
        + "cannot be emitted until classification-vocabulary item 5 resolves condition-method target tables";

    enum Case implements ValidatorCase {

        SINGLE_NO_PATH("single cardinality, empty joinPath — single-cardinality stub fires first",
            new RecordLookupTableField("Language", "film", null, filmReturn(new FieldWrapper.Single(true)), List.of(), List.of(), new OrderBySpec.None(), null, BATCH_KEY, EMPTY_LOOKUP),
            List.of(SINGLE_CARDINALITY_STUB)),

        SINGLE_WITH_FK_PATH("single cardinality with FK path — single-cardinality stub surfaces as build error",
            new RecordLookupTableField("Language", "film", null, filmReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.FkJoin("language_film_id_fkey", "", null, List.of(), new TableRef("film", "", "", List.of()), List.of(), null, "")),
                List.of(), new OrderBySpec.None(), null, BATCH_KEY, EMPTY_LOOKUP),
            List.of(SINGLE_CARDINALITY_STUB)),

        LIST_WITH_CONDITION_ONLY("list cardinality with condition-only join step — condition-join stub surfaces as build error",
            new RecordLookupTableField("Language", "films", null, filmReturn(new FieldWrapper.List(true, true)),
                List.of(new JoinStep.ConditionJoin(new MethodRef.Basic("com.example.Conditions", "filmCondition", "org.jooq.Condition", List.of()), "")),
                List.of(), new OrderBySpec.None(), null, BATCH_KEY, EMPTY_LOOKUP),
            List.of(CONDITION_JOIN_STUB)),

        LIST_WITH_FK_PATH("list cardinality with FK path — emittable, no validation error",
            new RecordLookupTableField("Language", "films", null, filmReturn(new FieldWrapper.List(true, true)),
                List.of(new JoinStep.FkJoin("language_film_id_fkey", "", null, List.of(), new TableRef("film", "", "", List.of()), List.of(), null, "")),
                List.of(), new OrderBySpec.None(), null, BATCH_KEY, EMPTY_LOOKUP),
            List.of()),

        CONNECTION_BLOCKED("connection return — lookup-field rejection",
            new RecordLookupTableField("Language", "films", null, filmReturn(new FieldWrapper.Connection(true, true)), List.of(), List.of(), new OrderBySpec.None(), null, BATCH_KEY, EMPTY_LOOKUP),
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
    void recordLookupTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
