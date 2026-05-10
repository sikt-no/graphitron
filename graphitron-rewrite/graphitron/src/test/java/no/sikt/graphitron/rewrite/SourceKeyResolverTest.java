package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.AccessorRef;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LifterRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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

    // ===== SplitTableField (RowKeyed only by record narrowing) =====

    @Test
    void splitTableField_projectsToColumnReadRowMany() {
        var stf = new ChildField.SplitTableField(
            "Language", "films", null,
            tableBoundFilm(nonNullList()),
            List.of(),
            List.of(),
            new OrderBySpec.None(), null,
            new BatchKey.RowKeyed(List.of(languageIdCol())));

        var resolved = SourceKeyResolver.resolve(stf);

        assertThat(resolved).isInstanceOf(SourceKeyResolver.Resolved.Ok.class);
        SourceKey key = ((SourceKeyResolver.Resolved.Ok) resolved).key();
        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
        assertThat(key.wrap()).isEqualTo(SourceKey.Wrap.ROW);
        assertThat(key.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
        assertThat(key.target().tableName()).isEqualTo("film");
        assertThat(key.columns()).containsExactly(languageIdCol());
        assertThat(key.path()).isEmpty();
    }

    // ===== RecordTableField permits =====

    @Test
    void recordTableField_rowKeyed_projectsToColumnRead() {
        var rtf = recordTableField(new BatchKey.RowKeyed(List.of(languageIdCol())));
        var key = okKey(SourceKeyResolver.resolve(rtf));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
        assertThat(key.wrap()).isEqualTo(SourceKey.Wrap.ROW);
        assertThat(key.path()).isEmpty();
        assertThat(key.columns()).containsExactly(languageIdCol());
    }

    @Test
    void recordTableField_lifterLeafKeyed_projectsToSourceRowsCall() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "films_0");
        var rtf = recordTableField(new BatchKey.LifterLeafKeyed(hop, LIFTER));
        var key = okKey(SourceKeyResolver.resolve(rtf));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
        assertThat(((SourceKey.Reader.SourceRowsCall) key.reader()).lifter()).isEqualTo(LIFTER);
        assertThat(key.wrap()).isEqualTo(SourceKey.Wrap.ROW);
        assertThat(key.path()).containsExactly(hop);
    }

    @Test
    void recordTableField_lifterPathKeyed_projectsToSourceRowsCall() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "films_0");
        var lpk = new BatchKey.LifterPathKeyed(List.of(hop), LIFTER);
        var rtf = recordTableField(lpk);
        var key = okKey(SourceKeyResolver.resolve(rtf));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
        assertThat(((SourceKey.Reader.SourceRowsCall) key.reader()).lifter()).isEqualTo(LIFTER);
        assertThat(key.wrap()).isEqualTo(SourceKey.Wrap.ROW);
        assertThat(key.path()).containsExactly(hop);
    }

    @Test
    void recordTableField_accessorKeyedSingle_projectsToAccessorCallRecordOne() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "film_0");
        var aks = new BatchKey.AccessorKeyedSingle(hop, ACCESSOR);
        var rtf = recordTableField(aks, single());
        var key = okKey(SourceKeyResolver.resolve(rtf));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
        assertThat(((SourceKey.Reader.AccessorCall) key.reader()).accessor()).isEqualTo(ACCESSOR);
        assertThat(key.wrap()).isEqualTo(SourceKey.Wrap.RECORD);
        assertThat(key.cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
    }

    @Test
    void recordTableField_accessorKeyedMany_projectsToAccessorCallRecordMany() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "films_0");
        var akm = new BatchKey.AccessorKeyedMany(hop, ACCESSOR);
        var rtf = recordTableField(akm, listWrapper());
        var key = okKey(SourceKeyResolver.resolve(rtf));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
        assertThat(key.wrap()).isEqualTo(SourceKey.Wrap.RECORD);
        // AccessorKeyedMany forces MANY regardless of field wrapper (per-element walk).
        assertThat(key.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
    }

    // ===== ServiceTableField (six ParentKeyed permits) =====

    @Test
    void serviceTableField_rowKeyed_projectsToServiceTableRecordWrapRow() {
        var stf = serviceTableField(new BatchKey.RowKeyed(List.of(filmIdCol())));
        var key = okKey(SourceKeyResolver.resolve(stf));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ServiceTableRecord.class);
        assertThat(((SourceKey.Reader.ServiceTableRecord) key.reader()).recordType())
            .isEqualTo(FILM_TABLE.recordClass());
        assertThat(key.wrap()).isEqualTo(SourceKey.Wrap.ROW);
    }

    @Test
    void serviceTableField_tableRecordKeyed_projectsToWrapTableRecord() {
        var trk = new BatchKey.TableRecordKeyed(
            List.of(filmIdCol()),
            no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord.class);
        var stf = serviceTableField(trk);
        var key = okKey(SourceKeyResolver.resolve(stf));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ServiceTableRecord.class);
        assertThat(key.wrap()).isEqualTo(SourceKey.Wrap.TABLE_RECORD);
    }

    @Test
    void serviceTableField_mappedRecordKeyed_projectsToWrapRecord() {
        var mrk = new BatchKey.MappedRecordKeyed(List.of(filmIdCol()));
        var stf = serviceTableField(mrk);
        var key = okKey(SourceKeyResolver.resolve(stf));

        assertThat(key.wrap()).isEqualTo(SourceKey.Wrap.RECORD);
    }

    // ===== ServiceRecordField =====

    @Test
    void serviceRecordField_projectsToServiceUntypedRecord() {
        var rk = new BatchKey.RowKeyed(List.of(filmIdCol()));
        var srf = new ChildField.ServiceRecordField(
            "Query", "filmTitle", null,
            new no.sikt.graphitron.rewrite.model.ReturnTypeRef.ScalarReturnType("String", single()),
            List.of(),
            stubMethodRef(),
            rk,
            Optional.empty());
        var key = okKey(SourceKeyResolver.resolve(srf));

        assertThat(key.reader()).isInstanceOf(SourceKey.Reader.ServiceUntypedRecord.class);
        // Scalar return + empty joinPath → null target.
        assertThat(key.target()).isNull();
        assertThat(key.cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
    }

    // ===== Helpers =====

    private static SourceKey okKey(SourceKeyResolver.Resolved resolved) {
        assertThat(resolved).isInstanceOf(SourceKeyResolver.Resolved.Ok.class);
        return ((SourceKeyResolver.Resolved.Ok) resolved).key();
    }

    private static ChildField.RecordTableField recordTableField(BatchKey.RecordParentBatchKey bk) {
        return recordTableField(bk, listWrapper());
    }

    private static ChildField.RecordTableField recordTableField(
            BatchKey.RecordParentBatchKey bk,
            no.sikt.graphitron.rewrite.model.FieldWrapper wrapper) {
        return new ChildField.RecordTableField(
            "FilmPayload", "film", null,
            tableBoundFilm(wrapper),
            List.of(),
            List.of(),
            new OrderBySpec.None(),
            null,
            bk);
    }

    private static ChildField.ServiceTableField serviceTableField(BatchKey.ParentKeyed bk) {
        return new ChildField.ServiceTableField(
            "Query", "filmsByActor", null,
            tableBoundFilm(nonNullList()),
            List.of(),
            List.of(),
            new OrderBySpec.None(),
            null,
            stubMethodRef(),
            bk,
            Optional.empty());
    }

    private static no.sikt.graphitron.rewrite.model.MethodRef stubMethodRef() {
        return new no.sikt.graphitron.rewrite.model.MethodRef.Service(
            "com.example.FilmService",
            "loadFilms",
            ClassName.OBJECT,
            List.of(),
            List.of(),
            new no.sikt.graphitron.rewrite.model.MethodRef.CallShape.Static(false));
    }
}
