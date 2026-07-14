package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier coverage of the {@link SourceKey} compact-constructor invariants. Pins the
 * cross-axis rejections classifiers must respect:
 *
 * <ul>
 *   <li>{@link SourceKey.Reader.SourceRowsCall} ⇒ {@link SourceKey.Wrap.Row}.</li>
 *   <li>{@link SourceKey.Reader.AccessorCall} ⇒ {@link SourceKey.Wrap.Record}.</li>
 *   <li>{@link SourceKey.Reader.ResultRowWalk} ⇒ {@link SourceKey.Wrap.Record} /
 *       {@link SourceKey.Wrap.TableRecord}, empty {@link SourceKey#path()}, and
 *       {@code OUTCOME_SUCCESS} only on {@link SourceKey.Wrap.TableRecord}.</li>
 * </ul>
 *
 * <p>R431 dispositions (the {@code target} component is deleted): the
 * {@code ServiceTableRecord}(target-aligned) ⇒ empty-path and
 * {@code ResultRowWalk} {@code TableRecord}-className-equals-target rejections asserted a
 * denormalized copy agreed with its source, exactly the drift class the decomposition removes;
 * both left with the component ({@code serviceTableRecordAcceptsNonEmptyPath} pins the widened
 * acceptance).
 *
 * <p>Exercises the canonical-constructor invariants and the {@link SourceKey#keyElementType()}
 * derivation on the flat-record model.
 */
@UnitTier
class SourceKeyTest {

    private static final ColumnRef FILM_ID =
        new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final TableRef FILM_TABLE =
        TestFixtures.tableRef("film", "FILM", "Film", List.of(FILM_ID));

    private static final AccessorRef ACCESSOR = new AccessorRef(
        ClassName.bestGuess("com.example.Payload"),
        "getOwner",
        ClassName.bestGuess("com.example.jooq.tables.records.LanguageRecord"));

    private static final LifterRef LIFTER = new LifterRef(
        ClassName.bestGuess("com.example.lifters.PayloadLifters"),
        "filmKey");

    @Test
    void rowKeyedColumnReadProjectsToCleanShape() {
        var key = new SourceKey(
            List.of(FILM_ID),
            List.of(),
            new SourceKey.Wrap.Row(),
            SourceKey.Cardinality.MANY,
            new SourceKey.Reader.ColumnRead());

        assertThat(key.columns()).containsExactly(FILM_ID);
        assertThat(key.path()).isEmpty();
        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
    }

    @Test
    void sourceRowsCallRequiresWrapRow() {
        assertThatThrownBy(() -> new SourceKey(
                List.of(FILM_ID),
                List.of(),
                new SourceKey.Wrap.Record(),
                SourceKey.Cardinality.ONE,
                new SourceKey.Reader.SourceRowsCall(LIFTER)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SourceRowsCall")
            .hasMessageContaining("Wrap.Row");
    }

    @Test
    void sourceRowsCallAcceptsWrapRow() {
        var key = new SourceKey(
            List.of(FILM_ID),
            List.of(),
            new SourceKey.Wrap.Row(),
            SourceKey.Cardinality.ONE,
            new SourceKey.Reader.SourceRowsCall(LIFTER));
        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
    }

    @Test
    void accessorCallRequiresWrapRecord() {
        assertThatThrownBy(() -> new SourceKey(
                List.of(FILM_ID),
                List.of(),
                new SourceKey.Wrap.Row(),
                SourceKey.Cardinality.ONE,
                new SourceKey.Reader.AccessorCall(ACCESSOR)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AccessorCall")
            .hasMessageContaining("Wrap.Record");
    }

    @Test
    void accessorCallAcceptsWrapRecord() {
        var key = new SourceKey(
            List.of(FILM_ID),
            List.of(),
            new SourceKey.Wrap.Record(),
            SourceKey.Cardinality.MANY,
            new SourceKey.Reader.AccessorCall(ACCESSOR));
        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
    }

    @Test
    void serviceTableRecordAcceptsNonEmptyPath() {
        // R431: with the target component deleted, the former "target-aligned ⇒ empty path"
        // rejection has no subject; a ServiceTableRecord key may carry a bridging path
        // regardless of what record type the service returns. The alignment concern re-homes
        // with the reader decomposition (the producer's declared return shape is a MethodRef
        // signature fact, not a SourceKey slot).
        ClassName filmRecordClass = FILM_TABLE.recordClass();
        var key = new SourceKey(
            List.of(FILM_ID),
            List.of(TestFixtures.liftedHop(FILM_TABLE, List.of(FILM_ID), "step_0")),
            new SourceKey.Wrap.TableRecord(filmRecordClass),
            SourceKey.Cardinality.ONE,
            new SourceKey.Reader.ServiceTableRecord(filmRecordClass));
        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ServiceTableRecord.class);
    }

    @Test
    void resultRowWalkAcceptsWrapRecord() {
        var key = new SourceKey(
            List.of(FILM_ID),
            List.of(),
            new SourceKey.Wrap.Record(),
            SourceKey.Cardinality.MANY,
            new SourceKey.Reader.ResultRowWalk(SourceKey.Reader.SourceEnvelope.DIRECT));
        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ResultRowWalk.class);
        assertThat(key.wrap()).isInstanceOf(SourceKey.Wrap.Record.class);
    }

    @Test
    void resultRowWalkAcceptsWrapTableRecord() {
        // R158: ResultRowWalk admits Wrap.TableRecord (the @service payload producer's typed
        // XRecord return). R431: the className-equals-target sub-check left with the target
        // component.
        var key = new SourceKey(
            List.of(FILM_ID),
            List.of(),
            new SourceKey.Wrap.TableRecord(FILM_TABLE.recordClass()),
            SourceKey.Cardinality.MANY,
            new SourceKey.Reader.ResultRowWalk(SourceKey.Reader.SourceEnvelope.DIRECT));
        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ResultRowWalk.class);
        assertThat(key.wrap()).isInstanceOf(SourceKey.Wrap.TableRecord.class);
    }

    @Test
    void resultRowWalkOutcomeSuccessEnvelopeAcceptsTableRecord() {
        // R275: the OUTCOME_SUCCESS envelope (the @service error-channel carrier) pairs with
        // Wrap.TableRecord — the producer wrapped its typed record in Outcome.
        var key = new SourceKey(
            List.of(FILM_ID),
            List.of(),
            new SourceKey.Wrap.TableRecord(FILM_TABLE.recordClass()),
            SourceKey.Cardinality.ONE,
            new SourceKey.Reader.ResultRowWalk(SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS));
        assertThat(((SourceKey.Reader.ResultRowWalk) key.reader()).envelope())
            .isEqualTo(SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS);
    }

    @Test
    void resultRowWalkOutcomeSuccessEnvelopeRejectsWrapRecord() {
        // R275: the OUTCOME_SUCCESS envelope only ever pairs with Wrap.TableRecord (the @service
        // carrier). Wrap.Record is the DML carrier, which delivers its row bare and is always DIRECT.
        assertThatThrownBy(() -> new SourceKey(
                List.of(FILM_ID),
                List.of(),
                new SourceKey.Wrap.Record(),
                SourceKey.Cardinality.ONE,
                new SourceKey.Reader.ResultRowWalk(SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("OUTCOME_SUCCESS")
            .hasMessageContaining("Wrap.TableRecord");
    }

    @Test
    void resultRowWalkRejectsNonEmptyPathUnderRecordWrap() {
        assertThatThrownBy(() -> new SourceKey(
                List.of(FILM_ID),
                List.of(TestFixtures.liftedHop(FILM_TABLE, List.of(FILM_ID), "step_0")),
                new SourceKey.Wrap.Record(),
                SourceKey.Cardinality.MANY,
                new SourceKey.Reader.ResultRowWalk(SourceKey.Reader.SourceEnvelope.DIRECT)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ResultRowWalk")
            .hasMessageContaining("empty path");
    }

    @Test
    void resultRowWalkRejectsNonEmptyPathUnderTableRecordWrap() {
        // R158: even the admitted TableRecord wrap must carry empty path.
        assertThatThrownBy(() -> new SourceKey(
                List.of(FILM_ID),
                List.of(TestFixtures.liftedHop(FILM_TABLE, List.of(FILM_ID), "step_0")),
                new SourceKey.Wrap.TableRecord(FILM_TABLE.recordClass()),
                SourceKey.Cardinality.MANY,
                new SourceKey.Reader.ResultRowWalk(SourceKey.Reader.SourceEnvelope.DIRECT)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ResultRowWalk")
            .hasMessageContaining("empty path");
    }

    @Test
    void resultRowWalkRejectsWrapRow() {
        assertThatThrownBy(() -> new SourceKey(
                List.of(FILM_ID),
                List.of(),
                new SourceKey.Wrap.Row(),
                SourceKey.Cardinality.MANY,
                new SourceKey.Reader.ResultRowWalk(SourceKey.Reader.SourceEnvelope.DIRECT)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ResultRowWalk");
    }

    @Test
    void columnsAndPathAreImmutable() {
        var mutableColumns = new java.util.ArrayList<ColumnRef>();
        mutableColumns.add(FILM_ID);
        var key = new SourceKey(
            mutableColumns,
            List.of(),
            new SourceKey.Wrap.Row(),
            SourceKey.Cardinality.MANY,
            new SourceKey.Reader.ColumnRead());
        // Mutating the original list must not affect the SourceKey's columns().
        mutableColumns.clear();
        assertThat(key.columns()).containsExactly(FILM_ID);
    }
}
