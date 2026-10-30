package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code key:} element naming a table's own primary key, in the store: the element resolves into
 * {@code graphitron_path_element_primary_key}, and the walk takes it as one step from the table back
 * to itself only where it is the path's only element on a field reading one row. Anywhere else the
 * element names something the catalog has and no move leaves from it, so the defect says there is
 * no route rather than that the name is unknown.
 *
 * <p>Every case captures real SDL against the test catalog.
 */
@PipelineTier
class PrimaryKeySelfJoinPathTest {

    @TempDir
    Path tmp;

    private static final String SELF = """
        type FilmSelf @table(name: "film") { title: String }
        type Language @table(name: "language") { name: String }
        """;

    /**
     * A primary key's generated constant is its SQL name upper-cased, so the case-insensitive SQL
     * name answers both spellings and the constant arm, which answers only where no SQL name does,
     * is never reached for one.
     */
    @Test
    void theElementResolvesBySqlNameAndByJooqConstantSpelling() {
        withCaptured(SELF + """
            type Film @table(name: "film") {
              title: String
              bySql: FilmSelf @reference(path: [{key: "film_pkey"}])
              byConstant: FilmSelf @reference(path: [{key: "FILM_PKEY"}])
            }
            type Query { films: [Film] }
            """, dsl -> {
            var e = GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
            assertThat(dsl.select(e.TABLE_NAME, e.MATCHED_BY).from(e)
                .where(e.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .fetch(r -> lower(r.value1()) + ":" + r.value2()))
                .containsExactly("film:SQL_NAME", "film:SQL_NAME");
        });
    }

    @Test
    void theWalkStepsFromTheTableToItselfOnItsKeyColumns() {
        withCaptured(SELF + """
            type FilmActorRow @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
            type Film @table(name: "film") {
              title: String
              self: FilmSelf @reference(path: [{key: "film_pkey"}])
            }
            type FilmActor @table(name: "film_actor") {
              row: FilmActorRow @reference(path: [{key: "film_actor_pkey"}])
            }
            type Query { films: [Film] filmActors: [FilmActor] }
            """, dsl -> {
            var p = GRAPHITRON_FIELD_PATH;
            assertThat(dsl.select(p.FIELD_NAME, p.FROM_TABLE, p.TO_TABLE).from(p)
                .where(p.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .orderBy(p.FIELD_NAME)
                .fetch(r -> r.value1() + ":" + lower(r.value2()) + "->" + lower(r.value3())))
                .containsExactly("row:film_actor->film_actor", "self:film->film");
            var k = GRAPHITRON_FIELD_PATH_PRIMARY_KEY;
            assertThat(dsl.select(k.FIELD_NAME, k.TABLE_NAME).from(k)
                .where(k.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .orderBy(k.FIELD_NAME)
                .fetch(r -> r.value1() + ":" + lower(r.value2())))
                .containsExactly("row:film_actor", "self:film");
            var c = GRAPHITRON_FIELD_PATH_COLUMN;
            assertThat(dsl.select(c.FIELD_NAME, c.FROM_COLUMN, c.TO_COLUMN).from(c)
                .where(c.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .orderBy(c.FIELD_NAME, c.ORDINAL)
                .fetch(r -> r.value1() + ":" + lower(r.value2()) + "=" + lower(r.value3())))
                .containsExactly("row:actor_id=actor_id", "row:film_id=film_id",
                    "self:film_id=film_id");
            assertNoForeignKeyRows(dsl);
            assertThat(defects(dsl)).isEmpty();
        });
    }

    @Test
    void asASecondElement_hasNoRoute() {
        withCaptured(SELF + """
            type Film @table(name: "film") {
              title: String
              lang: Language @reference(path: [{key: "film_language_id_fkey"}, {key: "language_pkey"}])
            }
            type Query { films: [Film] }
            """, dsl -> {
            assertThat(defects(dsl)).containsExactly("NO_ROUTE_FROM_DEPARTURE");
            assertNoForeignKeyRows(dsl, "lang", 1);
        });
    }

    @Test
    void asAFirstElementFollowedByAnother_hasNoRoute() {
        withCaptured(SELF + """
            type Film @table(name: "film") {
              title: String
              lang: Language @reference(path: [{key: "film_pkey"}, {key: "film_language_id_fkey"}])
            }
            type Query { films: [Film] }
            """, dsl -> assertThat(defects(dsl)).containsExactly("NO_ROUTE_FROM_DEPARTURE"));
    }

    @Test
    void onAListField_hasNoRoute() {
        withCaptured(SELF + """
            type Film @table(name: "film") {
              title: String
              rows: [FilmSelf] @reference(path: [{key: "film_pkey"}])
            }
            type Query { films: [Film] }
            """, dsl -> {
            assertThat(defects(dsl)).containsExactly("NO_ROUTE_FROM_DEPARTURE");
            assertNoForeignKeyRows(dsl);
        });
    }

    @Test
    void aForeignKeyElementIsUntouched() {
        withCaptured(SELF + """
            type Film @table(name: "film") {
              title: String
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { films: [Film] }
            """, dsl -> {
            var k = GRAPHITRON_FIELD_PATH_KEY;
            assertThat(dsl.select(k.CONSTRAINT_NAME).from(k)
                .where(k.GRAPH_NAME.eq(CapturedStore.GRAPH)).fetch(r -> lower(r.value1())))
                .containsExactly("film_language_id_fkey");
            assertThat(dsl.fetchCount(GRAPHITRON_FIELD_PATH_PRIMARY_KEY)).isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY)).isZero();
            assertThat(defects(dsl)).isEmpty();
        });
    }

    // ===== Helpers =====

    private static List<String> defects(DSLContext dsl) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        return dsl.select(d.CODE).from(d).where(d.GRAPH_NAME.eq(CapturedStore.GRAPH)).fetch(r -> r.value1());
    }

    private static void assertNoForeignKeyRows(DSLContext dsl) {
        assertThat(dsl.fetchCount(GRAPHITRON_FIELD_PATH_KEY))
            .as("a primary-key step rides no foreign key")
            .isZero();
    }

    private static void assertNoForeignKeyRows(DSLContext dsl, String field, int position) {
        var k = GRAPHITRON_FIELD_PATH_KEY;
        assertThat(dsl.fetchCount(k, k.FIELD_NAME.eq(field).and(k.POSITION.eq(position))))
            .as("no foreign-key row for the primary-key element's position")
            .isZero();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private void withCaptured(String sdl, Consumer<DSLContext> body) {
        try (var store = CapturedStore.ofCatalog(tmp, sdl, CatalogInput.of(testContext()))) {
            body.accept(store.dsl());
        }
    }
}
