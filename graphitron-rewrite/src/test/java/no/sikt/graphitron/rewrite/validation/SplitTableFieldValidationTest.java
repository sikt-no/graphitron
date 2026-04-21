package no.sikt.graphitron.rewrite.validation;

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

class SplitTableFieldValidationTest {

    private static ReturnTypeRef.TableBoundReturnType actorReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Actor", new TableRef("actor", "ACTOR", "Actor", List.of()), wrapper);
    }

    private static final BatchKey PARENT_BATCH_KEY = new BatchKey.RowKeyed(List.of());

    // Validator messages for the intra-variant runtime-stub branches of SplitRowsMethodEmitter.
    // Kept inline (rather than read from SplitRowsMethodEmitter.unsupportedReason) so a change
    // to the production string breaks this test loudly — update both sides in the same commit.
    private static final String SINGLE_CARDINALITY_STUB =
        "Field 'Film.actors': Single-cardinality @splitQuery on 'Film.actors' not yet supported; "
        + "list cardinality is the Phase 2b C1 scope. "
        + "Single-cardinality requires joining the parent table to bridge parent PK to parent FK.";

    enum Case implements ValidatorCase {

        NO_PATH("single cardinality, no @reference — runtime stub at emit time, surfaced as build error",
            new SplitTableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(), List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY),
            List.of(SINGLE_CARDINALITY_STUB)),

        WITH_FK_PATH("single cardinality with FK path — runtime stub at emit time, surfaced as build error",
            new SplitTableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.FkJoin("film_actor_film_id_fkey", "", null, List.of(), new TableRef("film_actor", "", "", List.of()), List.of(), null, "")),
                List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY),
            List.of(SINGLE_CARDINALITY_STUB)),

        WITH_CONDITION_ONLY("single cardinality with condition-only join step — runtime stub, build error",
            new SplitTableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)),
                List.of(new JoinStep.ConditionJoin(new MethodRef.Basic("com.example.Conditions", "actorCondition", "org.jooq.Condition", List.of()), "")),
                List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY),
            List.of(SINGLE_CARDINALITY_STUB)),

        FIELD_CONDITION_RESOLVED("single cardinality with resolved @condition — single-cardinality stub still surfaces",
            new SplitTableField("Film", "actors", null, actorReturn(new FieldWrapper.Single(true)), List.of(),
                List.of(new ConditionFilter("com.example.Conditions", "actorCondition", List.of())),
                new OrderBySpec.None(), null, PARENT_BATCH_KEY),
            List.of(SINGLE_CARDINALITY_STUB));

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
