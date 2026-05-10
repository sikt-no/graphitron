package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.AccessorRef;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.TestFixtures.filmIdCol;
import static no.sikt.graphitron.rewrite.TestFixtures.languageIdCol;
import static no.sikt.graphitron.rewrite.TestFixtures.liftedHop;
import static no.sikt.graphitron.rewrite.TestFixtures.listWrapper;
import static no.sikt.graphitron.rewrite.TestFixtures.nonNullList;
import static no.sikt.graphitron.rewrite.TestFixtures.single;
import static no.sikt.graphitron.rewrite.TestFixtures.tableBoundFilm;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of {@link LoaderRegistrationResolver}'s container projection plus the
 * {@code valueIsList} flag. Sibling of {@link SourceKeyResolverTest}; one test per orthogonal
 * axis (positional vs mapped, list vs single, AccessorKeyedMany loadMany contract).
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
    void rowKeyedListField_positionalListWithListValue() {
        var stf = new ChildField.SplitTableField(
            "Language", "films", null,
            tableBoundFilm(nonNullList()),
            List.of(),
            List.of(),
            new OrderBySpec.None(), null,
            new BatchKey.RowKeyed(List.of(languageIdCol())));

        LoaderRegistration reg = LoaderRegistrationResolver.resolve(stf);

        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
        assertThat(reg.valueIsList()).isTrue();
        assertThat(reg.loaderName()).isEqualTo("Language.films");
    }

    @Test
    void rowKeyedSingleField_positionalListWithSingleValue() {
        var stf = new ChildField.SplitTableField(
            "Film", "language", null,
            tableBoundFilm(single()),
            List.of(),
            List.of(),
            new OrderBySpec.None(), null,
            new BatchKey.RowKeyed(List.of(languageIdCol())));

        LoaderRegistration reg = LoaderRegistrationResolver.resolve(stf);

        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
        assertThat(reg.valueIsList()).isFalse();
    }

    @Test
    void mappedRowKeyed_serviceField_mappedSet() {
        var srf = new ChildField.ServiceTableField(
            "Query", "filmsByActor", null,
            tableBoundFilm(nonNullList()),
            List.of(),
            List.of(),
            new OrderBySpec.None(), null,
            stubServiceMethod(),
            new BatchKey.MappedRowKeyed(List.of(filmIdCol())),
            Optional.empty());

        LoaderRegistration reg = LoaderRegistrationResolver.resolve(srf);

        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
        assertThat(reg.valueIsList()).isTrue();
    }

    @Test
    void mappedTableRecordKeyed_serviceField_mappedSet() {
        var srf = new ChildField.ServiceTableField(
            "Query", "filmsByActor", null,
            tableBoundFilm(nonNullList()),
            List.of(),
            List.of(),
            new OrderBySpec.None(), null,
            stubServiceMethod(),
            new BatchKey.MappedTableRecordKeyed(List.of(filmIdCol()),
                no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord.class),
            Optional.empty());

        LoaderRegistration reg = LoaderRegistrationResolver.resolve(srf);

        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
    }

    @Test
    void accessorKeyedMany_recordTableField_mappedSetWithSingleValuePerKey() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "films_0");
        var rtf = new ChildField.RecordTableField(
            "Payload", "films", null,
            tableBoundFilm(listWrapper()),
            List.of(),
            List.of(),
            new OrderBySpec.None(), null,
            new BatchKey.AccessorKeyedMany(hop, ACCESSOR));

        LoaderRegistration reg = LoaderRegistrationResolver.resolve(rtf);

        // AccessorKeyedMany: loadMany contract → MAPPED_SET. The per-key value is one Record
        // (loadMany supplies one record per element-PK), so valueIsList is FALSE even though
        // the field's outer cardinality is list.
        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
        assertThat(reg.valueIsList()).isFalse();
    }

    @Test
    void accessorKeyedSingle_recordTableField_positionalListWithSingleValue() {
        var hop = liftedHop(FILM_TABLE, List.of(filmIdCol()), "film_0");
        var rtf = new ChildField.RecordTableField(
            "Payload", "film", null,
            tableBoundFilm(single()),
            List.of(),
            List.of(),
            new OrderBySpec.None(), null,
            new BatchKey.AccessorKeyedSingle(hop, ACCESSOR));

        LoaderRegistration reg = LoaderRegistrationResolver.resolve(rtf);

        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
        assertThat(reg.valueIsList()).isFalse();
    }

    private static no.sikt.graphitron.rewrite.model.MethodRef stubServiceMethod() {
        return new no.sikt.graphitron.rewrite.model.MethodRef.Service(
            "com.example.FilmService",
            "loadFilms",
            ClassName.OBJECT,
            List.of(),
            List.of(),
            new no.sikt.graphitron.rewrite.model.MethodRef.CallShape.Static(false));
    }
}
