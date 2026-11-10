package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.EmailRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.InventoryRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.RentalRecord;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NodeIdDecodeQuery}'s resolutions over a captured schema: a node id decoded into a record of
 * the table its path starts on loads the columns the path carries the key back to, into a record of
 * the node's own table with no such path its key, and what nothing in the store states is refused
 * with the reason. Read over the {@code scenarios/node-id-decode-record} and
 * {@code scenarios/service-record-landing} fact documents, and for an id of several node types over
 * {@code defects/polymorphic-node-id-decode-containers}.
 */
@PipelineTier
class NodeIdDecodeQueryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String TABLES = "no.sikt.graphitron.rewrite.test.jooq.public_.Tables";

    private static NodeIdDecodeQuery query;

    /** Read over a document whose union of node types is recorded as a defect, so it is no scenario. */
    private static NodeIdDecodeQuery severalTypes;

    /** Read over the document whose reply input writes a path over email's self-referencing key. */
    private static NodeIdDecodeQuery replies;

    @BeforeAll
    static void read() {
        query = FACTS.read("scenarios/node-id-decode-record", graph -> NodeIdDecodeQuery.read(graph.handle()));
        severalTypes = FACTS.read("defects/polymorphic-node-id-decode-containers",
            graph -> NodeIdDecodeQuery.read(graph.handle()));
        replies = FACTS.read("scenarios/service-record-landing", graph -> NodeIdDecodeQuery.read(graph.handle()));
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
    void aWrittenPathOntoTheNodesOwnTableLoadsTheColumnsItCarriesTheKeyBackTo() {
        assertThat(replies.resolve("Query.describeEmailReply(in.inReplyTo:)", EmailRecord.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Resolved(new NodeIdDecodeQuery.Decode(
                "Email", "Email", TABLES, "EMAIL", EmailRecord.class.getName(),
                List.of("MAILBOX_ID", "IN_REPLY_TO_NO"))));
    }

    @Test
    void whatTheStoreDoesNotStateIsRefused() {
        assertThat(query.resolve("Language.id", FilmRecord.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused(
                "no path from film to language is stated at the id, so nothing says which columns it fills"));
        assertThat(query.resolve("Query.rentals(filmId:)", RentalRecord.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused(
                "the path from rental to film does not carry key column film_id back to a column of rental"));
        assertThat(severalTypes.resolve("Query.occupant(occupant:)", InventoryRecord.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused(
                "the id is one of several node types, which is not built here yet"));
        assertThat(query.resolve("Inventory.inventoryId", InventoryRecord.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused("no node type is bound to the id"));
        assertThat(query.resolve("Query.inventories(filmId:)", String.class.getName()))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused(
                "member type java.lang.String is not a jOOQ record a node id can decode into"));
    }
}
