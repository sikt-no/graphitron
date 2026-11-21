package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.TestRunContext;
import org.assertj.core.groups.Tuple;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_SITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A record class is a table's only where the graph reads the catalog that table is in. Graphs on
 * different catalogs share a store, so a rule that matches a class name against {@code sql_table}
 * without going through the graph's sources finds a sibling's table and judges this graph by it.
 * A fact document is one graph, which is why the pairing is made here: the document is read under
 * the fixture catalog and under another catalog that has none of the tables its records belong to.
 */
class RecordClassCatalogScopeTest {

    private static final String OTHER_PACKAGE = "no.sikt.graphitron.rewrite.singleschemafixture";

    @TempDir
    static Path tmp;

    private static CapturedStore store;

    @BeforeAll
    static void graphsOnTwoCatalogs() {
        var fixture = CatalogInput.of(TestRunContext.of());
        var other = new CatalogInput(OTHER_PACKAGE, fixture.loader());
        var classpath = ClasspathCorpus.entries();
        store = CapturedStore.ownStoreOfDocument(tmp, "defects/key-row", "keys-fixture", fixture,
            classpath);
        store.andDocument("defects/key-row", "keys-other", other, classpath);
    }

    @AfterAll
    static void close() {
        store.close();
    }

    @Test
    void aServiceKeyedByASiblingsRecordIsNotJudgedForItsKeyRow() {
        assertThat(defects(store.dsl(), "keys-fixture", "NO_KEY_FROM_PARENT", "KEY_ROW_AMBIGUOUS"))
            .contains(
                tuple("FilmBlurbHolder.keyCensus", "NO_KEY_FROM_PARENT"),
                tuple("FilmPair.keyCensus", "KEY_ROW_AMBIGUOUS"));
        assertThat(defects(store.dsl(), "keys-other", "NO_KEY_FROM_PARENT", "KEY_ROW_AMBIGUOUS"))
            .as("film is no table of this graph's catalog, so the source parameter collects no"
                + " table's records and there is no key row to look for")
            .isEmpty();
    }

    private static List<Tuple> defects(DSLContext dsl, String graph, String... codes) {
        var d = GRAPHITRON_ENTRY_DEFECT_SITE;
        return dsl.select(d.COORDINATE, d.CODE).from(d)
            .where(d.GRAPH_NAME.eq(graph), d.CODE.in(codes))
            .fetch(r -> tuple(r.value1(), r.value2()));
    }
}
