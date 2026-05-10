package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.AccessorRef;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.LifterRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.rewrite.TestFixtures.filmIdCol;
import static no.sikt.graphitron.rewrite.TestFixtures.languageIdCol;
import static no.sikt.graphitron.rewrite.TestFixtures.languageTableWithPk;
import static no.sikt.graphitron.rewrite.TestFixtures.liftedHop;
import static no.sikt.graphitron.rewrite.TestFixtures.listWrapper;
import static no.sikt.graphitron.rewrite.TestFixtures.nonNullList;
import static no.sikt.graphitron.rewrite.TestFixtures.single;
import static no.sikt.graphitron.rewrite.TestFixtures.tableBoundFilm;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of {@link SourceKeyResolver}'s projection rules — one fixture per
 * (BatchKeyField permit × BatchKey permit) row in the spec's projection table. Each test
 * asserts the produced {@link SourceKey}'s {@code reader}, {@code wrap}, {@code cardinality},
 * and (where structurally relevant) {@code path} / {@code columns}.
 *
 * <p>Sibling of {@link SourceKeyTest}, which covers the model-level invariants. This test
 * pins the projection — that the resolver routes today's {@link BatchKey} permits onto the
 * right {@link SourceKey.Reader} sub-permit and passes the right per-axis values.
 */
@UnitTier
class SourceKeyResolverTest {

    private static final TableRef FILM_TABLE = TestFixtures.filmTableWithPk();
    private static final TableRef LANGUAGE_TABLE = languageTableWithPk();

    private static final AccessorRef ACCESSOR = new AccessorRef(
        ClassName.bestGuess("com.example.Payload"),
        "getFilm",
        ClassName.bestGuess("com.example.jooq.tables.records.FilmRecord"));

    private static final LifterRef LIFTER = new LifterRef(
        ClassName.bestGuess("com.example.lifters.PayloadLifters"),
        "filmKey");

    // ===== Split (RowKeyed only by record narrowing) =====

    @Test
    void splitTableField_projectsToColumnReadRowMany() {
        var bk = new BatchKey.RowKeyed(List.of(languageIdCol()));
        var key = SourceKeyResolver.resolveSplit(bk, tableBoundFilm(nonNullList()));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
        assertThat(key.wrap()).isEqualTo(new SourceKey.Wrap.Row());
        assertThat(key.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
        assertThat(key.target().tableName()).isEqualTo("film");
        assertThat(key.columns()).containsExactly(languageIdCol());
        assertThat(key.path()).isEmpty();
    }

    // ===== RecordParent permits =====

    @Test
    void recordParent_rowKeyed_projectsToColumnRead() {
        var bk = new BatchKey.RowKeyed(List.of(languageIdCol()));
        var key = SourceKeyResolver.resolveRecordParent(bk, tableBoundFilm(listWrapper()));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
        assertThat(key.wrap()).isEqualTo(new SourceKey.Wrap.Row());
        assertThat(key.path()).isEmpty();
        assertThat(key.columns()).containsExactly(languageIdCol());
    }

    @Test
    void recordParent_lifterLeafKeyed_projectsToSourceRowsCall() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "films_0");
        var bk = new BatchKey.LifterLeafKeyed(hop, LIFTER);
        var key = SourceKeyResolver.resolveRecordParent(bk, tableBoundFilm(listWrapper()));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
        assertThat(((SourceKey.Reader.SourceRowsCall) key.reader()).lifter()).isEqualTo(LIFTER);
        assertThat(key.wrap()).isEqualTo(new SourceKey.Wrap.Row());
        assertThat(key.path()).containsExactly(hop);
    }

    @Test
    void recordParent_lifterPathKeyed_projectsToSourceRowsCall() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "films_0");
        var bk = new BatchKey.LifterPathKeyed(List.of(hop), LIFTER);
        var key = SourceKeyResolver.resolveRecordParent(bk, tableBoundFilm(listWrapper()));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
        assertThat(((SourceKey.Reader.SourceRowsCall) key.reader()).lifter()).isEqualTo(LIFTER);
        assertThat(key.wrap()).isEqualTo(new SourceKey.Wrap.Row());
        assertThat(key.path()).containsExactly(hop);
    }

    @Test
    void recordParent_accessorKeyedSingle_projectsToAccessorCallRecordOne() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "film_0");
        var bk = new BatchKey.AccessorKeyedSingle(hop, ACCESSOR);
        var key = SourceKeyResolver.resolveRecordParent(bk, tableBoundFilm(single()));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
        assertThat(((SourceKey.Reader.AccessorCall) key.reader()).accessor()).isEqualTo(ACCESSOR);
        assertThat(key.wrap()).isEqualTo(new SourceKey.Wrap.Record());
        assertThat(key.cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
    }

    @Test
    void recordParent_accessorKeyedMany_projectsToAccessorCallRecordMany() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "films_0");
        var bk = new BatchKey.AccessorKeyedMany(hop, ACCESSOR);
        var key = SourceKeyResolver.resolveRecordParent(bk, tableBoundFilm(listWrapper()));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
        assertThat(key.wrap()).isEqualTo(new SourceKey.Wrap.Record());
        // AccessorKeyedMany forces MANY regardless of field wrapper (per-element walk).
        assertThat(key.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
    }

    // ===== ServiceTableField (six ParentKeyed permits) =====

    @Test
    void serviceTableField_rowKeyed_projectsToServiceTableRecordWrapRow() {
        var bk = new BatchKey.RowKeyed(List.of(filmIdCol()));
        var key = SourceKeyResolver.resolveServiceTable(bk, tableBoundFilm(nonNullList()));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ServiceTableRecord.class);
        assertThat(((SourceKey.Reader.ServiceTableRecord) key.reader()).recordType())
            .isEqualTo(FILM_TABLE.recordClass());
        assertThat(key.wrap()).isEqualTo(new SourceKey.Wrap.Row());
    }

    @Test
    void serviceTableField_tableRecordKeyed_projectsToWrapTableRecord() {
        var trk = new BatchKey.TableRecordKeyed(
            List.of(filmIdCol()),
            no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord.class);
        var key = SourceKeyResolver.resolveServiceTable(trk, tableBoundFilm(nonNullList()));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ServiceTableRecord.class);
        assertThat(key.wrap()).isInstanceOf(SourceKey.Wrap.TableRecord.class);
        assertThat(((SourceKey.Wrap.TableRecord) key.wrap()).className())
            .isEqualTo(ClassName.get(no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord.class));
    }

    @Test
    void serviceTableField_mappedRecordKeyed_projectsToWrapRecord() {
        var mrk = new BatchKey.MappedRecordKeyed(List.of(filmIdCol()));
        var key = SourceKeyResolver.resolveServiceTable(mrk, tableBoundFilm(nonNullList()));

        assertThat(key.wrap()).isEqualTo(new SourceKey.Wrap.Record());
    }

    // ===== ServiceRecordField =====

    @Test
    void serviceRecordField_projectsToServiceUntypedRecord() {
        var rk = new BatchKey.RowKeyed(List.of(filmIdCol()));
        var key = SourceKeyResolver.resolveServiceRecord(rk,
            new no.sikt.graphitron.rewrite.model.ReturnTypeRef.ScalarReturnType("String", single()),
            List.of());

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ServiceUntypedRecord.class);
        // Scalar return + empty joinPath → null target.
        assertThat(key.target()).isNull();
        assertThat(key.cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
    }
}
