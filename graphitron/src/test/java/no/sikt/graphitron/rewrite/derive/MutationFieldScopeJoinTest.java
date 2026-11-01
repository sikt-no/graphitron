package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The join the owned-connection path's transaction scope reads to know which mutation fields
 * graphitron's own fetcher opens a nested transaction for: {@code graphitron_producer.kind}
 * against {@code graphql_root_operation}. No relation is stored for the question; this pins that
 * the two existing relations answer it. The writes graphitron generates (INSERT, UPDATE, DELETE
 * and a routine) are the mutation fields whose kind says so, a {@code @service} mutation is not
 * one of them, and a query field never is.
 */
@PipelineTier
class MutationFieldScopeJoinTest {

    @TempDir
    Path tmp;

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String SERVICE = "no.sikt.graphitron.rewrite.test.services.MutationScopeService";

    @Test
    void theMutationFieldsGraphitronOpensAScopeFor_areTheGeneratedWritesAndNotTheService() {
        String sdl = """
            input FilmCreateInput {
                title: String! @field(name: "title")
                languageId: Int! @field(name: "language_id")
            }
            input FilmUpdateInput {
                filmId: Int! @field(name: "film_id")
                title: String! @field(name: "title")
            }
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { films: [Film!]! }
            type Mutation {
                createFilm(in: FilmCreateInput!): Film @mutation(typeName: INSERT)
                updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE)
                deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE, table: "film")
                rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                    @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                    @reference(path: [{table: "rental"}])
                writeByService(marker: String!): String
                    @service(service: {className: "%s", method: "writeOne"})
            }
            """.formatted(SERVICE);
        try (var store = CapturedStore.ofCatalogWith(tmp, GRAPH, sdl, CatalogInput.of(testContext()),
                ClasspathCorpus.entries())) {
            assertThat(mutationFieldsTheScopeIsOpenedFor(store.dsl()))
                .as("INSERT, UPDATE, DELETE and the routine are generated writes; the service and every"
                    + " query field are not")
                .containsExactly(
                    "Mutation.createFilm INSERT",
                    "Mutation.deleteFilm DELETE",
                    "Mutation.rentFilm ROUTINE",
                    "Mutation.updateFilm UPDATE");
            assertThat(producersOf(store.dsl()))
                .as("the service mutation is a producer, of kind SERVICE, and so is excluded by kind")
                .contains("Mutation.writeByService SERVICE");
        }
    }

    /** The mutation-root producers whose kind is a write graphitron generates, as {@code Type.field KIND}. */
    private static List<String> mutationFieldsTheScopeIsOpenedFor(DSLContext dsl) {
        return dsl.select(GRAPHITRON_PRODUCER.TYPE_NAME, GRAPHITRON_PRODUCER.FIELD_NAME, GRAPHITRON_PRODUCER.KIND)
            .from(GRAPHITRON_PRODUCER)
            .join(GRAPHQL_ROOT_OPERATION)
            .on(GRAPHQL_ROOT_OPERATION.GRAPH_NAME.eq(GRAPHITRON_PRODUCER.GRAPH_NAME))
            .and(GRAPHQL_ROOT_OPERATION.TYPE_NAME.eq(GRAPHITRON_PRODUCER.TYPE_NAME))
            .where(GRAPHITRON_PRODUCER.GRAPH_NAME.eq(GRAPH))
            .and(GRAPHQL_ROOT_OPERATION.OPERATION.eq("MUTATION"))
            .and(GRAPHITRON_PRODUCER.KIND.in("INSERT", "UPDATE", "DELETE", "ROUTINE"))
            .orderBy(GRAPHITRON_PRODUCER.FIELD_NAME)
            .fetch(r -> r.value1() + "." + r.value2() + " " + r.value3());
    }

    private static List<String> producersOf(DSLContext dsl) {
        return dsl.select(GRAPHITRON_PRODUCER.TYPE_NAME, GRAPHITRON_PRODUCER.FIELD_NAME, GRAPHITRON_PRODUCER.KIND)
            .from(GRAPHITRON_PRODUCER)
            .where(GRAPHITRON_PRODUCER.GRAPH_NAME.eq(GRAPH))
            .fetch(r -> r.value1() + "." + r.value2() + " " + r.value3());
    }
}
