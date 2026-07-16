package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of the {@link SourceKey} residue: the {@code (columns, wrap)} pair and the
 * {@link SourceKey#keyElementType()} derivation.
 *
 * <p>Dispositions of the retired compact-constructor invariant families (slice 3; the
 * {@code target} / {@code path} families are dispositioned in the slice-1/2 history of this
 * javadoc):
 *
 * <ul>
 *   <li>{@code SourceRowsCall} ⇒ {@code Wrap.Row} and {@code AccessorCall} ⇒ {@code Wrap.Record}:
 *       unrepresentable by construction — the lift arms pin their key shape via
 *       {@link KeyLift#wrap()}, the derivation every lift-carrying leaf constructs its residue
 *       through; {@link KeyLiftTest} pins the derivation and the
 *       {@link KeyLift#checkResidueAgreement} construction-rule tripwire.</li>
 *   <li>{@code ResultRowWalk} ⇒ {@code Wrap.Record}/{@code Wrap.TableRecord} and
 *       {@code ResultRowWalk(OUTCOME_SUCCESS)} ⇒ {@code Wrap.TableRecord} (the hard one): the
 *       named join site is {@link ChildField.SingleRecordIdField}'s compact constructor — the
 *       only envelope-bearing typed-record read left once the DML {@code Wrap.Record} walk died
 *       into the re-fetch — which requires {@code Wrap.TableRecord} unconditionally
 *       (strictly stronger than the retired conditional coupling). Pinned by
 *       {@link SingleRecordIdFieldKeyShapeInvariantTest}.</li>
 *   <li>The service arms' shape duplication ({@code ServiceTableRecord} carrying the producer's
 *       record class) left with the reader seal; the service residue is read straight off the
 *       {@link MethodRef.Param.Sourced} signature fact.</li>
 * </ul>
 */
@UnitTier
class SourceKeyTest {

    private static final ColumnRef FILM_ID =
        new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final ColumnRef LANGUAGE_ID =
        new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Long");
    private static final TableRef FILM_TABLE =
        TestFixtures.tableRef("film", "FILM", "Film", List.of(FILM_ID));

    @Test
    void rowWrapDerivesRowNKeyElementType() {
        var key = new SourceKey(List.of(FILM_ID, LANGUAGE_ID), new SourceKey.Wrap.Row());
        assertThat(key.keyElementType()).isEqualTo(ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Row2"),
            ClassName.get("java.lang", "Integer"),
            ClassName.get("java.lang", "Long")));
    }

    @Test
    void recordWrapDerivesRecordNKeyElementType() {
        var key = new SourceKey(List.of(FILM_ID), new SourceKey.Wrap.Record());
        assertThat(key.keyElementType()).isEqualTo(ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Record1"),
            ClassName.get("java.lang", "Integer")));
    }

    @Test
    void tableRecordWrapDerivesTheCapturedClassName() {
        var key = new SourceKey(
            List.of(FILM_ID),
            new SourceKey.Wrap.TableRecord(FILM_TABLE.recordClass()));
        assertThat(key.keyElementType()).isEqualTo(FILM_TABLE.recordClass());
    }

    @Test
    void staticKeyElementTypeMatchesInstanceDerivation() {
        // The partial carriers (MethodRef.Param.Sourced, ParamSource.Sources) hold the
        // (wrap, columns) pair without a full SourceKey; the static overload must agree.
        var wrap = new SourceKey.Wrap.Record();
        var key = new SourceKey(List.of(FILM_ID), wrap);
        assertThat(SourceKey.keyElementType(wrap, List.of(FILM_ID)))
            .isEqualTo(key.keyElementType());
    }

    @Test
    void columnsAreImmutable() {
        var mutableColumns = new java.util.ArrayList<ColumnRef>();
        mutableColumns.add(FILM_ID);
        var key = new SourceKey(mutableColumns, new SourceKey.Wrap.Row());
        // Mutating the original list must not affect the SourceKey's columns().
        mutableColumns.clear();
        assertThat(key.columns()).containsExactly(FILM_ID);
    }
}
