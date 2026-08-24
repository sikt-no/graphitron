package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ArgConditionRef;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of the two positions {@code GraphitronSchemaValidator}'s input-reference validator
 * takes on a filter path whose hops are not all foreign keys.
 *
 * <p>A plain {@code @reference} filter carrier is accepted: the reach emission dispatches per hop
 * on the {@code On} seal, so there is nothing for the validator to check and its silence is the
 * contract. An FK-target {@code @nodeId} carrier with a {@code @condition} still rejects, but as a
 * policy deferral standing on its own rather than as a mirror of an emitter precondition: that
 * carrier binds decoded id columns to the path's FK slots, and a predicate hop has no slots to
 * bind them to.
 */
@UnitTier
class InputColumnBackedReferenceFieldValidationTest {

    private static final ColumnRef LANGUAGE_ID =
        new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer");
    private static final ColumnRef NAME = new ColumnRef("name", "NAME", "java.lang.String");

    private static final List<JoinStep> CONDITION_PATH = List.of(TestFixtures.conditionJoin(
        TestFixtures.staticOnlyMethodRef("com.example.Conditions", "filmToLanguage",
            ClassName.get("org.jooq", "Condition")),
        TestFixtures.languageTable(), "languageName_0"));

    private static InputField.ColumnBackedReferenceField carrier(
            List<ColumnRef> columns, Optional<ArgConditionRef> condition, CallSiteExtraction extraction) {
        return new InputField.ColumnBackedReferenceField(
            "FilmFilter", "languageName", null, "String", false, false,
            columns, CONDITION_PATH, new FilterBinding.Remote(), false, condition, extraction);
    }

    private static Optional<ArgConditionRef> stubCondition() {
        return Optional.of(new ArgConditionRef(
            new ConditionFilter("com.example.Conditions", "languageCondition", List.of()), false));
    }

    @Test
    void plainReferenceFilter_throughAConditionHop_isAccepted() {
        // The widened contract: no shape check fires, so the carrier validates clean. The
        // pipeline cases on both filter surfaces are what enforce it end to end; this pins that
        // the validator itself holds no opinion.
        assertThat(validate(carrier(List.of(NAME), Optional.empty(), new CallSiteExtraction.Direct())))
            .isEmpty();
    }

    @Test
    void fkTargetNodeIdWithCondition_throughAConditionHop_isDeferred() {
        // The surviving rejection, and its kind is part of what is pinned: DEFERRED says "not yet
        // designed for this carrier", which is the claim, where INVALID_SCHEMA would say the
        // author wrote something wrong.
        var errors = validate(carrier(List.of(NAME), stubCondition(), new CallSiteExtraction.Direct()));
        assertThat(errors).hasSize(1);
        FieldValidationTestHelper.assertHasKind(errors, RejectionKind.DEFERRED,
            "requires a foreign-key join path");
        assertThat(errors.get(0).message())
            .as("the message states the deferral's own reason rather than an emitter precondition")
            .contains("decoded id columns")
            .contains("a plain `@reference` filter on the same path is supported");
    }

    @Test
    void compositeFkTargetNodeIdWithCondition_namesTheCompositeCarrier() {
        // Arity > 1 takes the composite wording; the deferral is arity-uniform otherwise.
        var decode = new CallSiteExtraction.ThrowOnMismatch(
            new no.sikt.graphitron.rewrite.model.HelperRef.Decode(
                ClassName.bestGuess("com.example.NodeIds"), "decodeLanguage",
                List.of(LANGUAGE_ID, NAME), "Language"));
        var errors = validate(carrier(List.of(LANGUAGE_ID, NAME), stubCondition(), decode));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("composite-key FK-target @nodeId field");
    }
}
