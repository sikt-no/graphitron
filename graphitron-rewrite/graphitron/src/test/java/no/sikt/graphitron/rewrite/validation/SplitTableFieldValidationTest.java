package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
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

    private static final BatchKey.RowKeyed PARENT_BATCH_KEY = new BatchKey.RowKeyed(List.of());

    // Emitter-level validator messages. Kept inline (rather than read from
    // SplitRowsMethodEmitter.unsupportedReason) so a change to the production string breaks
    // this test loudly — update both sides in the same commit.
    private static final String CONDITION_JOIN_STUB =
        "Field 'Film.actors': @splitQuery 'Film.actors' with a condition-join step cannot be "
        + "emitted until classification-vocabulary item 5 resolves condition-method target tables";

    enum Case implements ValidatorCase {

        // Single-cardinality @splitQuery with a single FK hop — emittable. Positive case:
        // the emitter-level validator produces no errors. Classifier-level rejection of
        // empty / multi-hop single cardinality lives in FieldBuilder and is exercised by
        // GraphitronSchemaBuilderTest, not here.
        SINGLE_CARDINALITY_EMITTABLE("single cardinality with one-hop FK path — emittable, no errors",
            new SplitTableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.FkJoin("film_actor_film_id_fkey", null, null, List.of(), TestFixtures.joinTarget("film_actor"), List.of(), null, "")),
                List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY),
            List.of()),

        WITH_CONDITION_ONLY("single cardinality with condition-only join step — runtime stub, build error",
            new SplitTableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.ConditionJoin(new MethodRef.Basic("com.example.Conditions", "actorCondition", ClassName.get("org.jooq", "Condition"), List.of()), "")),
                List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY),
            List.of(CONDITION_JOIN_STUB)),

        // plan-split-query-connection.md §1: Split + Connection with no ORDER BY is a build error.
        // ROW_NUMBER() needs a total order to slice partitions deterministically; without one,
        // cursor encoding hashes an empty tuple and pages silently non-deterministically.
        CONNECTION_EMPTY_ORDERBY_NONE("Connection + OrderBySpec.None — build error, non-empty ORDER BY required",
            new SplitTableField("Film", "actors", null, actorReturn(new FieldWrapper.Connection(false, 100)),
                List.of(new JoinStep.FkJoin("film_actor_film_id_fkey", null, null, List.of(), TestFixtures.joinTarget("film_actor"), List.of(), null, "")),
                List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY),
            List.of("Field 'Film.actors': @splitQuery connections require a non-empty ORDER BY "
                + "(add @defaultOrder, @orderBy, or a primary key on the target table)")),

        CONNECTION_EMPTY_ORDERBY_FIXED("Connection + empty OrderBySpec.Fixed — same rejection",
            new SplitTableField("Film", "actors", null, actorReturn(new FieldWrapper.Connection(false, 100)),
                List.of(new JoinStep.FkJoin("film_actor_film_id_fkey", null, null, List.of(), TestFixtures.joinTarget("film_actor"), List.of(), null, "")),
                List.of(), new OrderBySpec.Fixed(List.of(), "asc"), null, PARENT_BATCH_KEY),
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
