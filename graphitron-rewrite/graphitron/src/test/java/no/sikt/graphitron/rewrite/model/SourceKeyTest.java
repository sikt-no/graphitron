package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier coverage of the {@link SourceKey} compact-constructor invariants. Pins the three
 * cross-axis rejections classifiers must respect:
 *
 * <ul>
 *   <li>{@link SourceKey.Reader.SourceRowsCall} ⇒ {@link SourceKey.Wrap.Row}.</li>
 *   <li>{@link SourceKey.Reader.AccessorCall} ⇒ {@link SourceKey.Wrap.Record}.</li>
 *   <li>{@link SourceKey.Reader.ServiceTableRecord} with {@code recordType} matching target's
 *       {@code recordClass} ⇒ empty {@link SourceKey#path()}.</li>
 * </ul>
 *
 * <p>Sibling of {@link BatchKeyTest} for the new model surface; the projection rules
 * (which {@link SourceKey} shape each {@link BatchKey} permit produces) live in the resolver
 * test in Phase 1b.
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
            FILM_TABLE,
            List.of(FILM_ID),
            List.of(),
            new SourceKey.Wrap.Row(),
            SourceKey.Cardinality.MANY,
            new SourceKey.Reader.ColumnRead());

        assertThat(key.target()).isEqualTo(FILM_TABLE);
        assertThat(key.columns()).containsExactly(FILM_ID);
        assertThat(key.path()).isEmpty();
        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
    }

    @Test
    void sourceRowsCallRequiresWrapRow() {
        assertThatThrownBy(() -> new SourceKey(
                FILM_TABLE,
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
            FILM_TABLE,
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
                FILM_TABLE,
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
            FILM_TABLE,
            List.of(FILM_ID),
            List.of(),
            new SourceKey.Wrap.Record(),
            SourceKey.Cardinality.MANY,
            new SourceKey.Reader.AccessorCall(ACCESSOR));
        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
    }

    @Test
    void serviceTableRecordTargetAlignedRequiresEmptyPath() {
        // Target has recordClass "FilmRecord"; ServiceTableRecord with the same recordType means
        // the service produced a target-aligned record. A non-empty path would walk past target.
        ClassName filmRecordClass = FILM_TABLE.recordClass();

        assertThatThrownBy(() -> new SourceKey(
                FILM_TABLE,
                List.of(FILM_ID),
                List.of(TestFixtures.liftedHop(FILM_TABLE, List.of(FILM_ID), "step_0")),
                new SourceKey.Wrap.TableRecord(filmRecordClass),
                SourceKey.Cardinality.ONE,
                new SourceKey.Reader.ServiceTableRecord(filmRecordClass)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ServiceTableRecord");
    }

    @Test
    void serviceTableRecordTargetMisalignedAcceptsNonEmptyPath() {
        // ServiceTableRecord whose recordType differs from target's recordClass is allowed to
        // carry a non-empty path — the chain bridges from the service-returned record to the
        // configured target table.
        ClassName otherRecord = ClassName.bestGuess("com.example.OtherRecord");
        var key = new SourceKey(
            FILM_TABLE,
            List.of(FILM_ID),
            List.of(TestFixtures.liftedHop(FILM_TABLE, List.of(FILM_ID), "step_0")),
            new SourceKey.Wrap.TableRecord(otherRecord),
            SourceKey.Cardinality.ONE,
            new SourceKey.Reader.ServiceTableRecord(otherRecord));
        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ServiceTableRecord.class);
    }

    @Test
    void columnsAndPathAreImmutable() {
        var mutableColumns = new java.util.ArrayList<ColumnRef>();
        mutableColumns.add(FILM_ID);
        var key = new SourceKey(
            FILM_TABLE,
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
