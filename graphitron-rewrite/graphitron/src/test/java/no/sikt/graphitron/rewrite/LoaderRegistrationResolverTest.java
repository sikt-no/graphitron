package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.AccessorRef;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.rewrite.TestFixtures.filmIdCol;
import static no.sikt.graphitron.rewrite.TestFixtures.languageIdCol;
import static no.sikt.graphitron.rewrite.TestFixtures.liftedHop;
import static no.sikt.graphitron.rewrite.TestFixtures.listWrapper;
import static no.sikt.graphitron.rewrite.TestFixtures.nonNullList;
import static no.sikt.graphitron.rewrite.TestFixtures.single;
import static no.sikt.graphitron.rewrite.TestFixtures.tableBoundFilm;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of {@link LoaderRegistrationResolver}'s container, dispatch, and
 * {@code valueIsList} projections. Sibling of {@link SourceKeyResolverTest}; one test per
 * orthogonal axis (positional vs mapped, list vs single, AccessorKeyedMany loadMany contract).
 */
@UnitTier
class LoaderRegistrationResolverTest {

    private static final no.sikt.graphitron.rewrite.model.TableRef FILM_TABLE =
        TestFixtures.filmTableWithPk();

    private static final AccessorRef ACCESSOR = new AccessorRef(
        ClassName.bestGuess("com.example.Payload"),
        "films",
        ClassName.bestGuess("com.example.jooq.tables.records.FilmRecord"));

    @Test
    void rowKeyedListField_positionalListLoadOneListValue() {
        var bk = new BatchKey.RowKeyed(List.of(languageIdCol()));
        LoaderRegistration reg = LoaderRegistrationResolver.resolve(bk, tableBoundFilm(nonNullList()));

        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
        assertThat(reg.dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_ONE);
        assertThat(reg.valueIsList()).isTrue();
    }

    @Test
    void rowKeyedSingleField_positionalListLoadOneSingleValue() {
        var bk = new BatchKey.RowKeyed(List.of(languageIdCol()));
        LoaderRegistration reg = LoaderRegistrationResolver.resolve(bk, tableBoundFilm(single()));

        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
        assertThat(reg.dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_ONE);
        assertThat(reg.valueIsList()).isFalse();
    }

    @Test
    void mappedRowKeyed_serviceField_mappedSetLoadOne() {
        var bk = new BatchKey.MappedRowKeyed(List.of(filmIdCol()));
        LoaderRegistration reg = LoaderRegistrationResolver.resolve(bk, tableBoundFilm(nonNullList()));

        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
        assertThat(reg.dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_ONE);
        assertThat(reg.valueIsList()).isTrue();
    }

    @Test
    void mappedTableRecordKeyed_serviceField_mappedSetLoadOne() {
        var bk = new BatchKey.MappedTableRecordKeyed(List.of(filmIdCol()),
            no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord.class);
        LoaderRegistration reg = LoaderRegistrationResolver.resolve(bk, tableBoundFilm(nonNullList()));

        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
        assertThat(reg.dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_ONE);
    }

    @Test
    void accessorKeyedMany_recordTableField_positionalListLoadManySingleValuePerKey() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "films_0");
        var bk = new BatchKey.AccessorKeyedMany(hop, ACCESSOR);
        LoaderRegistration reg = LoaderRegistrationResolver.resolve(bk, tableBoundFilm(listWrapper()));

        // AccessorKeyedMany: loadMany contract on a positional DataLoader. Container axis
        // (newDataLoader) and dispatch axis (loader.loadMany) are independent: the per-fetch
        // dispatch fans the parent's accessor-yielded keys out across the same positional
        // BatchLoader the rest of the RecordTableField shapes use. The per-key value is one
        // Record (loadMany supplies one record per element-PK), so valueIsList is FALSE even
        // though the field's outer cardinality is list.
        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
        assertThat(reg.dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_MANY);
        assertThat(reg.valueIsList()).isFalse();
    }

    @Test
    void accessorKeyedSingle_recordTableField_positionalListLoadOneSingleValue() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "film_0");
        var bk = new BatchKey.AccessorKeyedSingle(hop, ACCESSOR);
        LoaderRegistration reg = LoaderRegistrationResolver.resolve(bk, tableBoundFilm(single()));

        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
        assertThat(reg.dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_ONE);
        assertThat(reg.valueIsList()).isFalse();
    }
}
