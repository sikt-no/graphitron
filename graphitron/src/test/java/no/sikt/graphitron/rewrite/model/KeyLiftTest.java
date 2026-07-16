package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins for the {@link KeyLift} lift fact: the {@link KeyLift#wrap()} shape derivation and
 * the {@link KeyLift#checkResidueAgreement} construction rule.
 *
 * <p>These are the migrated homes of the retired {@code SourceKey} compact-constructor pairings
 * ({@code SourceRowsCall} ⇒ {@code Wrap.Row}, {@code AccessorCall} ⇒ {@code Wrap.Record}): the
 * shape is now a total function of the lift arm, so a disagreeing stored wrap is
 * unrepresentable when the residue is constructed through the derivation — and
 * {@code checkResidueAgreement}, called from every lift-carrying {@link ChildField} compact
 * constructor, trips on any hand-built pair that bypasses it.
 */
@UnitTier
class KeyLiftTest {

    private static final ColumnRef FILM_ID =
        new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");

    private static final AccessorRef ACCESSOR = new AccessorRef(
        ClassName.bestGuess("com.example.Payload"),
        "getOwner",
        ClassName.bestGuess("com.example.jooq.tables.records.LanguageRecord"));

    private static final LifterRef LIFTER = new LifterRef(
        ClassName.bestGuess("com.example.lifters.PayloadLifters"),
        "filmKey");

    @Test
    void wrapIsATotalDerivationOfTheArm() {
        assertThat(new KeyLift.FkColumns().wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
        assertThat(new KeyLift.Lifter(LIFTER).wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
        assertThat(new KeyLift.Accessor(ACCESSOR, Arity.ONE).wrap())
            .isInstanceOf(SourceKey.Wrap.Record.class);
        assertThat(new KeyLift.ProducedRecords(Arity.MANY).wrap())
            .isInstanceOf(SourceKey.Wrap.Row.class);
    }

    @Test
    void residueAgreementAcceptsTheDerivedWrap() {
        var lift = new KeyLift.Accessor(ACCESSOR, Arity.MANY);
        var key = new SourceKey(List.of(FILM_ID), lift.wrap());
        KeyLift.checkResidueAgreement(lift, key, "TestVariant");
    }

    @Test
    void residueAgreementRejectsADisagreeingWrap() {
        // The retired AccessorCall => Wrap.Record rejection, re-expressed: an accessor lift
        // paired with a Row-shaped residue is a classifier-side bug.
        var lift = new KeyLift.Accessor(ACCESSOR, Arity.ONE);
        var key = new SourceKey(List.of(FILM_ID), new SourceKey.Wrap.Row());
        assertThatThrownBy(() -> KeyLift.checkResidueAgreement(lift, key, "TestVariant"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("TestVariant")
            .hasMessageContaining("Accessor");
    }

    @Test
    void residueAgreementRejectsLifterWithNonRowWrap() {
        // The retired SourceRowsCall => Wrap.Row rejection, re-expressed.
        var lift = new KeyLift.Lifter(LIFTER);
        var key = new SourceKey(List.of(FILM_ID), new SourceKey.Wrap.Record());
        assertThatThrownBy(() -> KeyLift.checkResidueAgreement(lift, key, "TestVariant"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Lifter");
    }
}
