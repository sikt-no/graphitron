package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DATA_CHANNEL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_type_data_channel}: what an OBJECT type's data
 * channels are, before any producer is consulted. The relation was the {@code data_channel} CTE
 * inside {@code intent_carrier_data_field} and is now registered in its own right, so what it states
 * is public and the cases here are the ones its reader's test structurally cannot make.
 *
 * <p>{@link CarrierDataFieldTest} pins that reader, and every case there sees this relation only
 * through the carrier's three rejections: a payload with an unresolved channel contributes nothing
 * there, so the row that made it contribute nothing is invisible. Three claims are only visible from
 * here. An unresolved element is a row present with a null kind and not an absent row, which is what
 * the rejection reads. The wrapper columns are carried, which is what the two family-specific ID
 * refusals turn on. And the population is every OBJECT type in the graph, so a type no producer
 * returns has its channels named exactly as a payload's are.
 *
 * <p>Each case asserts the whole graph's rows, which is the sibling's discipline and matters more
 * here: the arity is a window over the type's surviving fields, so a case that projected one type
 * away could not show what the subtraction of the errors channels did to the count.
 *
 * <p>Two things the expectations make visible that are easy to read as noise. An {@code @error} type
 * is an OBJECT type like any other, so its own fields are channels here; the relation is total over
 * object types and being a member of some field's error container is not a property of the type that
 * would exclude it. And {@code itemNonNull} is null off a list rather than false, being a property of
 * a list's element and not of a field: {@code DbErr.path} is the one field in these fixtures that
 * carries it as true.
 */
@PipelineTier
class TypeDataChannelTest {

    @TempDir
    Path tmp;

    private static final String GRAPH = CapturedStore.GRAPH;

    /** The error channel the payload fixtures declare, so the subtraction has something to remove. */
    private static final String ERRORS = """
        type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
        union WriteError = DbErr
        """;

    /**
     * The row the carrier's rejection reads and its own relation cannot show: a scalar field is a
     * channel of no recognized kind, and it is present here with a null kind rather than absent. An
     * absent row would read as a type with one channel instead of two, which is the opposite of what
     * the rejection needs to see.
     */
    @Test
    void anUnresolvedElementIsAPresentRowWithNoKind() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload {
                film: Film
                note: String
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(channels(dsl)).containsExactly(
            "CreateFilmPayload.film TABLE list=false itemNonNull=null of 2",
            "CreateFilmPayload.note null list=false itemNonNull=null of 2",
            "DbErr.message null list=false itemNonNull=null of 2",
            "DbErr.path null list=true itemNonNull=true of 2",
            "Film.title null list=false itemNonNull=null of 1",
            "Mutation.createFilm null list=false itemNonNull=null of 1",
            "Query.films TABLE list=true itemNonNull=false of 1"));
    }

    /**
     * The wrapper columns, on the row the DML family's {@code [ID]} refusal turns on: a nullable list
     * of the ID scalar. The refusal is the carrier relation's, and what it reads is these two columns
     * beside the kind, so a case that did not carry them would leave that rejection standing on
     * nothing this relation states.
     */
    @Test
    void theWrapperIsCarriedBesideTheIdElement() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type DeleteFilmPayload {
                deletedIds: [ID]
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                deleteFilm(filmId: Int): DeleteFilmPayload
                    @mutation(typeName: DELETE, table: "film")
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(channels(dsl)).containsExactly(
            "DbErr.message null list=false itemNonNull=null of 2",
            "DbErr.path null list=true itemNonNull=true of 2",
            "DeleteFilmPayload.deletedIds ID list=true itemNonNull=false of 1",
            "Film.title null list=false itemNonNull=null of 1",
            "Mutation.deleteFilm null list=false itemNonNull=null of 1",
            "Query.films TABLE list=true itemNonNull=false of 1"));
    }

    /**
     * The population is the type and not the producer: a graph that writes nothing at all still has
     * every OBJECT type's channels named here. That is what separates this relation from its reader,
     * and it is the property that let the shape be promoted out of the carrier view rather than
     * duplicated beside it.
     */
    @Test
    void aGraphWithNoProducerStillNamesEveryObjectTypesChannels() {
        var sdl = """
            type Film @table(name: "film") {
                title: String
                actors: [Actor]
            }
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(channels(dsl)).containsExactly(
            "Actor.firstName null list=false itemNonNull=null of 1",
            "Film.actors TABLE list=true itemNonNull=false of 2",
            "Film.title null list=false itemNonNull=null of 2",
            "Query.films TABLE list=true itemNonNull=false of 1"));
    }

    /**
     * A type whose every field is an errors channel contributes no row at all, rather than a row
     * counting zero. The subtraction runs ahead of the window, so there is no partition left to count
     * over, and a reader meeting such a payload meets absence.
     */
    @Test
    void aTypeThatIsAllErrorsChannelContributesNothing() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload { errors: [WriteError] }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(channels(dsl)).containsExactly(
            "DbErr.message null list=false itemNonNull=null of 2",
            "DbErr.path null list=true itemNonNull=true of 2",
            "Film.title null list=false itemNonNull=null of 1",
            "Mutation.createFilm null list=false itemNonNull=null of 1",
            "Query.films TABLE list=true itemNonNull=false of 1"));
    }

    private static List<String> channels(DSLContext dsl) {
        var c = INTENT_TYPE_DATA_CHANNEL;
        return dsl.select(c.fields())
            .from(c)
            .where(c.GRAPH_NAME.eq(GRAPH))
            .orderBy(c.TYPE_NAME, c.FIELD_NAME)
            .fetch()
            .map(row -> row.get(c.TYPE_NAME) + "." + row.get(c.FIELD_NAME) + " "
                + row.get(c.ELEMENT_KIND) + " list=" + row.get(c.IS_LIST)
                + " itemNonNull=" + row.get(c.ITEM_NON_NULL)
                + " of " + row.get(c.DATA_FIELDS));
    }

    private void withCapturedStore(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), census())) {
            body.accept(store.dsl());
        }
    }

    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(TypeDataChannelTest.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
