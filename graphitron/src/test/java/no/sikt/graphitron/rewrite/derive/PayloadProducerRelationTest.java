package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PAYLOAD_PRODUCER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code intent_field_payload_producer}, which says which coordinate produces a value and what type
 * it arrives as, the carrier rule's producer half. The errors fields and the outcomes a root producer
 * catches into are the fact document {@code producer-outcome.graphqls}.
 *
 * <p>Every case captures real SDL against the test catalog rather than seeding rows, for the reason
 * {@code CarrierDataFieldTest} states: a seeded fixture is free to declare a shape capture never
 * writes, and the case then pins behaviour no build can produce. Each case asserts the whole graph's
 * rows, so a shape that should contribute nothing fails the case it appears in.
 */
@PipelineTier
class PayloadProducerRelationTest {

    @TempDir
    Path tmp;

    private static final String GRAPH = "PayloadProducerRelationTest";

    /**
     * A producing coordinate carries its family and where it sits. The root binding is a column and
     * not a filter, so the child {@code @service} field is a row here beside the root one, with the
     * operation absent rather than the row.
     */
    @Test
    void aProducerCarriesItsFamilyAndItsRootBinding() {
        var sdl = """
            type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
            union WriteError = DbErr
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload {
                film: Film
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(producers(dsl)).containsExactly(
            "Mutation.createFilm SERVICE CreateFilmPayload MUTATION"));
    }

    /** A child {@code @service} field produces exactly as a root one does; what differs is the position. */
    @Test
    void aChildServiceFieldProducesWithNoRootBinding() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") {
                films: [Film]
                    @service(service: {className: "com.example.FilmService", method: "byActor"})
            }
            type Query { actors: [Actor] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(producers(dsl)).containsExactly(
            "Actor.films SERVICE Film null"));
    }

    /** Every producing coordinate: family, the type its value arrives as, and where it sits. */
    private static List<String> producers(DSLContext dsl) {
        var p = INTENT_FIELD_PAYLOAD_PRODUCER;
        return dsl.select(p.TYPE_NAME, p.FIELD_NAME, p.FAMILY, p.PAYLOAD_TYPE_NAME, p.ROOT_OPERATION)
            .from(p)
            .where(p.GRAPH_NAME.eq(GRAPH))
            .orderBy(p.TYPE_NAME, p.FIELD_NAME, p.FAMILY)
            .fetch()
            .map(row -> row.get(p.TYPE_NAME) + "." + row.get(p.FIELD_NAME) + " "
                + row.get(p.FAMILY) + " " + row.get(p.PAYLOAD_TYPE_NAME) + " "
                + row.get(p.ROOT_OPERATION));
    }

    private void withCapturedStore(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalogWith(tmp, GRAPH, sdl,
                CatalogInput.of(ctx),
                TestSchemaHelper.testClasspath())) {
            body.accept(store.dsl());
        }
    }
}
