package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.InventoryRecord;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NodeIdDecodeQuery}'s resolutions over a captured schema: a node id decoded into a record of
 * the node's own table loads its key, into a record of a table referencing it the one foreign key's
 * columns, and what nothing in the store states is refused with the reason.
 */
@PipelineTier
class NodeIdDecodeQueryTest {

    private static final String TABLES = "no.sikt.graphitron.rewrite.test.jooq.public_.Tables";

    private static final String SDL = """
        interface Node { id: ID! }
        type Film implements Node @table(name: "film") @node(typeId: "F") { id: ID! }
        type Language implements Node @table(name: "language") @node { id: ID! }
        type Actor implements Node @table(name: "actor") @node { id: ID! }
        type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
        union Credit = Film | Actor
        type Query {
          inventories(filmId: ID @nodeId(typeName: "Film")): [Inventory!]!
          films(languageId: ID @nodeId(typeName: "Language")): [Film!]!
          credits(id: ID @nodeId(typeName: "Credit")): [Inventory!]!
          plain(code: ID): [Inventory!]!
          languages: [Language!]!
          actors: [Actor!]!
        }
        """;

    @TempDir
    static Path tmp;

    private static NodeIdDecodeQuery query;

    @BeforeAll
    static void read() {
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, SDL,
                CatalogInput.of(testContext()), ClasspathCorpus.entries())) {
            query = NodeIdDecodeQuery.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH));
        }
    }

    @Test
    void aRecordOfTheNodesTableLoadsItsKey() {
        assertThat(query.resolve("Query.inventories(filmId:)", FilmRecord.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Resolved(new NodeIdDecodeQuery.Decode(
                "Film", "F", TABLES, "FILM", FilmRecord.class.getName(), List.of("FILM_ID"))));
        assertThat(query.resolveOwn("Query.inventories(filmId:)"))
            .isEqualTo(query.resolve("Query.inventories(filmId:)", FilmRecord.class.getName()));
    }

    @Test
    void aRecordReferencingTheNodeLoadsItsForeignKey() {
        assertThat(query.resolve("Query.inventories(filmId:)", InventoryRecord.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Resolved(new NodeIdDecodeQuery.Decode(
                "Film", "F", TABLES, "INVENTORY", InventoryRecord.class.getName(), List.of("FILM_ID"))));
    }

    @Test
    void whatTheStoreDoesNotStateIsRefused() {
        assertThat(query.resolve("Query.films(languageId:)", FilmRecord.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused(
                "film has 2 foreign keys to language, so nothing says which the id fills"));
        assertThat(query.resolve("Query.credits(id:)", InventoryRecord.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused(
                "the id is one of several node types, which is not built here yet"));
        assertThat(query.resolve("Query.plain(code:)", InventoryRecord.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused("no node type is bound to the id"));
        assertThat(query.resolve("Query.inventories(filmId:)", String.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused(
                "member type java.lang.String is not a jOOQ record a node id can decode into"));
    }
}
