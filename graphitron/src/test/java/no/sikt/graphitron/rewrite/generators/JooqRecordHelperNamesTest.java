package no.sikt.graphitron.rewrite.generators;

import com.palantir.javapoet.ClassName;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tier for {@link JooqRecordHelperNames}: one {@code <Type>Fetchers} class's jOOQ-record helpers
 * named by binding shape. One shape of a record class keeps the bare name; several are contended,
 * each named by an ordinal in a stable order; and the ordinal composes on the cross-schema base stem {@link FetchersHelperNames} hands in, which
 * {@link FetchersHelperNamesTest} pins on its own.
 */
@UnitTier
class JooqRecordHelperNamesTest {

    private static final TableRef FILM = new TableRef("film", "FILM", "x.tables.Film",
        "x.tables.records.FilmRecord", "x.Tables", List.of(), List.of());
    private static final ColumnRef TITLE = new ColumnRef("title", "TITLE", "java.lang.String");
    private static final ColumnRef RELEASE_YEAR = new ColumnRef("release_year", "RELEASE_YEAR", "java.lang.Integer");

    private static CallSiteExtraction.JooqRecord film(CallSiteExtraction.ColumnBinding... bindings) {
        return new CallSiteExtraction.JooqRecord(FILM, List.of(bindings), List.of());
    }

    private static CallSiteExtraction.ColumnBinding binds(String path, ColumnRef column) {
        return CallSiteExtraction.ColumnBinding.of(List.of(path), column);
    }

    @Test
    void oneShapeKeepsTheBareNameAndItsDuplicatesCollapse() {
        var shape = film(binds("title", TITLE));
        var names = JooqRecordHelperNames.of(List.of(shape, film(binds("title", TITLE))), Map.of());

        assertThat(names.distinctShapes()).containsExactly(shape);
        assertThat(names.singularName(shape)).isEqualTo("createFilmRecord");
        assertThat(names.pluralName(shape)).isEqualTo("createFilmRecordList");
    }

    @Test
    void twoShapesAreContendedEachNamedByAStableOrdinal() {
        var titleOnly = film(binds("title", TITLE));
        var withYear = film(binds("title", TITLE), binds("releaseYear", RELEASE_YEAR));

        for (var carriers : List.of(List.of(titleOnly, withYear), List.of(withYear, titleOnly))) {
            var names = JooqRecordHelperNames.of(carriers, Map.of());
            assertThat(names.singularName(withYear)).isEqualTo("createFilmRecord1");
            assertThat(names.pluralName(withYear)).isEqualTo("createFilmRecord1List");
            assertThat(names.singularName(titleOnly)).isEqualTo("createFilmRecord2");
        }
    }

    @Test
    void shapesDifferingOnlyInAnAliasAreDistinct() {
        var live = film(binds("title", TITLE));
        var aliased = film(new CallSiteExtraction.ColumnBinding(List.of(List.of("title"), List.of("name")), TITLE));
        var names = JooqRecordHelperNames.of(List.of(live, aliased), Map.of());

        assertThat(names.distinctShapes()).containsExactly(live, aliased);
        assertThat(List.of(names.singularName(live), names.singularName(aliased)))
            .containsExactlyInAnyOrder("createFilmRecord1", "createFilmRecord2");
    }

    @Test
    void theOrdinalComposesOnTheCrossSchemaStemAndAnUncontendedClassKeepsItsOwn() {
        var a = new TableRef("event", "EVENT", "com.acme.jooq.multischema_a.tables.Event",
            "com.acme.jooq.multischema_a.tables.records.EventRecord", "com.acme.jooq.multischema_a.Tables",
            List.of(), List.of());
        var b = new TableRef("event", "EVENT", "com.acme.jooq.multischema_b.tables.Event",
            "com.acme.jooq.multischema_b.tables.records.EventRecord", "com.acme.jooq.multischema_b.Tables",
            List.of(), List.of());
        var name = new ColumnRef("name", "NAME", "java.lang.String");
        var aOne = new CallSiteExtraction.JooqRecord(a, List.of(binds("name", name)), List.of());
        var aTwo = new CallSiteExtraction.JooqRecord(a, List.of(binds("title", name)), List.of());
        var bOne = new CallSiteExtraction.JooqRecord(b, List.of(binds("name", name)), List.of());
        Map<ClassName, String> stems = Map.of(
            ClassName.get("com.acme.jooq.multischema_a.tables.records", "EventRecord"), "MultischemaAEventRecord",
            ClassName.get("com.acme.jooq.multischema_b.tables.records", "EventRecord"), "MultischemaBEventRecord");

        var names = JooqRecordHelperNames.of(List.of(aOne, aTwo, bOne), stems);

        assertThat(List.of(names.singularName(aOne), names.singularName(aTwo)))
            .containsExactlyInAnyOrder("createMultischemaAEventRecord1", "createMultischemaAEventRecord2");
        assertThat(names.singularName(bOne)).isEqualTo("createMultischemaBEventRecord");
    }

    @Test
    void aPopulatedResolverRefusesACarrierItNeverCollected() {
        var names = JooqRecordHelperNames.of(List.of(film(binds("title", TITLE))), Map.of());

        assertThatThrownBy(() -> names.singularName(film(binds("releaseYear", RELEASE_YEAR))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("never collected");
    }
}
