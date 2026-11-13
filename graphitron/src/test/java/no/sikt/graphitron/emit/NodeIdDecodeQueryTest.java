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
import java.util.Optional;

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
        assertThat(query.resolve("Query.inventories(filmId:)", "public.film", Optional.of(FilmRecord.class.getName())))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Resolved(new NodeIdDecodeQuery.Decode(
                "Film", "F", TABLES, "FILM", FilmRecord.class.getName(), List.of("FILM_ID"))));
        assertThat(query.resolve("Query.inventories(filmId:)", "public.film", Optional.empty()))
            .isEqualTo(query.resolve("Query.inventories(filmId:)", "public.film", Optional.of(FilmRecord.class.getName())));
    }

    @Test
    void aRecordReferencingTheNodeLoadsItsForeignKey() {
        assertThat(query.resolve("Query.inventories(filmId:)", "public.film", Optional.of(InventoryRecord.class.getName())))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Resolved(new NodeIdDecodeQuery.Decode(
                "Film", "F", TABLES, "INVENTORY", InventoryRecord.class.getName(), List.of("FILM_ID"))));
    }

    @Test
    void aWrittenPathOntoTheNodesOwnTableLoadsTheColumnsItCarriesTheKeyBackTo() {
        assertThat(replies.resolve("Query.describeEmailReply(in.inReplyTo:)",
                replies.bounds("Query.describeEmailReply(in.inReplyTo:)").getFirst(), Optional.of(EmailRecord.class.getName())))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Resolved(new NodeIdDecodeQuery.Decode(
                "Email", "Email", TABLES, "EMAIL", EmailRecord.class.getName(),
                List.of("MAILBOX_ID", "IN_REPLY_TO_NO"))));
    }

    @Test
    void whatTheStoreDoesNotStateIsRefused() {
        assertThat(query.resolve("Language.id", query.bounds("Language.id").getFirst(), Optional.of(FilmRecord.class.getName())))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused(
                "no path from film to language is stated at the id, so nothing says which columns it fills"));
        assertThat(query.resolve("Query.rentals(filmId:)", "public.film", Optional.of(RentalRecord.class.getName())))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused(
                "the path from rental to film does not carry key column film_id back to a column of rental"));
        assertThat(query.resolve("Inventory.inventoryId", "public.inventory", Optional.of(InventoryRecord.class.getName())))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused("the id binds no node on public.inventory"));
        assertThat(query.resolve("Query.inventories(filmId:)", "public.film", Optional.of(String.class.getName())))
            .isEqualTo(new NodeIdDecodeQuery.Resolution.Refused(
                "member type java.lang.String is not a jOOQ record a node id can decode into"));
    }

    /** An id of several node types decodes once per table it binds, each into that member's own record. */
    @Test
    void anIdOfSeveralNodeTypesDecodesPerMember() {
        assertThat(severalTypes.bounds("Query.occupant(occupant:)")).containsExactly("public.customer", "public.staff");
        assertThat(severalTypes.resolve("Query.occupant(occupant:)", "public.staff", Optional.empty()))
            .isInstanceOfSatisfying(NodeIdDecodeQuery.Resolution.Resolved.class, r -> assertThat(r.decode())
                .extracting(NodeIdDecodeQuery.Decode::nodeTypeName, NodeIdDecodeQuery.Decode::tableField)
                .containsExactly("Staff", "STAFF"));
    }
}
