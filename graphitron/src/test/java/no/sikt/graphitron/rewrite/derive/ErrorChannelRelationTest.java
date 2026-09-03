package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_ERRORS_FIELD;
import static no.sikt.graphitron.model.Tables.INTENT_ERRORS_FIELD_MEMBER;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_ERROR_CHANNEL;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PAYLOAD_PRODUCER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for the four relations the error channel is stated over:
 * {@code intent_errors_field} and its ordered child {@code intent_errors_field_member}, which say
 * which field of a type is a channel and what it maps;
 * {@code intent_field_payload_producer}, which says which coordinate produces a value and what type
 * it arrives as; and {@code intent_field_error_channel}, which joins the two into one row per
 * coordinate whose throws have somewhere to go.
 *
 * <p>Every case captures real SDL against the test catalog rather than seeding rows, for the reason
 * {@code CarrierDataFieldTest} states: a seeded fixture is free to declare a shape capture never
 * writes, and the case then pins behaviour no build can produce. The transport decode is the reason
 * that matters most here, its three arms turning on whether the carrier recognition admits the
 * payload, which is itself derived from the catalog.
 *
 * <p>Each case asserts the whole graph's rows for the relation under test rather than a projection,
 * so a shape that should contribute nothing fails the case it appears in.
 */
@PipelineTier
class ErrorChannelRelationTest {

    @TempDir
    Path tmp;

    private static final String GRAPH = "ErrorChannelRelationTest";

    /** Two {@code @error} types and a union over them; the shape every channel case builds on. */
    private static final String ERRORS = """
        type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
        type ConflictErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
        union WriteError = DbErr | ConflictErr
        """;

    // ===== Which field is a channel =====

    /**
     * The shape itself: a nullable list of a union whose every member carries {@code @error}. The
     * carrier's data field sits beside it and is no channel, and neither is anything on the
     * {@code @error} types themselves, whose own fields are plain lists of scalars.
     */
    @Test
    void aNullableListOfAllErrorMembersIsTheChannel() {
        var sdl = ERRORS + """
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
        withCapturedStore(sdl, dsl -> assertThat(errorsFields(dsl)).containsExactly(
            "CreateFilmPayload.errors 1 WriteError UNION"));
    }

    /**
     * An interface container answers the same way a union does, and the container kind is what says
     * which of the two the members came from.
     */
    @Test
    void anInterfaceContainerIsAChannelToo() {
        var sdl = """
            interface WriteError { message: String! }
            type DbErr implements WriteError @error(handlers: [{handler: DATABASE}]) {
                path: [String!]! message: String!
            }
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
        withCapturedStore(sdl, dsl -> assertThat(errorsFields(dsl)).containsExactly(
            "CreateFilmPayload.errors 1 WriteError INTERFACE"));
    }

    /**
     * One member without {@code @error} disqualifies the whole field. The container is then a mixed
     * result type rather than an error list, and nothing about the other members rescues it.
     */
    @Test
    void aMemberWithoutErrorDisqualifiesTheField() {
        var sdl = """
            type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
            type Note { text: String }
            union WriteError = DbErr | Note
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
        withCapturedStore(sdl, dsl -> assertThat(errorsFields(dsl)).isEmpty());
    }

    /**
     * A single-valued field naming the union is not a channel however error-shaped its type is: the
     * channel is a list because a dispatch can match more than one throw.
     */
    @Test
    void aNonListFieldIsNotAChannel() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload {
                film: Film
                errors: WriteError
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(errorsFields(dsl)).isEmpty());
    }

    /**
     * A non-null list is not a channel. On the success arm the channel resolves to no list at all,
     * so a non-null list would raise a non-nullable-field error there and take the sibling data
     * field down with it; the relation refuses the shape rather than recording a channel that
     * cannot work.
     */
    @Test
    void aNonNullListIsNotAChannel() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload {
                film: Film
                errors: [WriteError]!
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(errorsFields(dsl)).isEmpty());
    }

    /**
     * An {@code @asConnection} field is not a channel however error-shaped it is authored.
     *
     * <p>This pins the outcome and not the rule that produces it, which is worth saying because the
     * relation carries a term that looks like the rule and is not reached here. The macro rewrites
     * the field before capture writes it: what lands in {@code graphql_field} is a single-valued
     * {@code CreateFilmPayloadErrorsConnection}, so the shape conditions exclude it twice over
     * before the connection term is consulted. Mutating that term to name the wrong relation leaves
     * this case green. Whether any field can carry {@code @asConnection} and still read as a
     * nullable list of a polymorphic type, which is what the term is there to catch, is open; no
     * shape found here produces one.
     */
    @Test
    void anAsConnectionFieldIsNotAChannel() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload {
                film: Film
                errors: [WriteError] @asConnection
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(errorsFields(dsl)).isEmpty());
    }

    /**
     * A type nothing produces has its errors field named here exactly as a payload's does. The
     * relation is shaped by the field and not by the producer, which is what lets one relation
     * answer both the carrier scan's question and the channel's.
     */
    @Test
    void anUnproducedTypesErrorsFieldIsStillNamed() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type OrphanPayload {
                film: Film
                errors: [WriteError]
            }
            type Query { films: [Film] orphan: OrphanPayload }
            """;
        withCapturedStore(sdl, dsl -> assertThat(errorsFields(dsl)).containsExactly(
            "OrphanPayload.errors 1 WriteError UNION"));
    }

    // ===== What the channel maps =====

    /**
     * The members come back in the order the union declared them, which is the order the dispatch
     * table is tried in and the order the fingerprint that disambiguates two constants digests.
     * Declared here deliberately out of alphabetical order so a sort could not pass the case.
     */
    @Test
    void theMembersKeepTheUnionsDeclaredOrder() {
        var sdl = """
            type ZebraErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
            type AlphaErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
            union WriteError = ZebraErr | AlphaErr
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
        withCapturedStore(sdl, dsl -> assertThat(members(dsl)).containsExactly(
            "CreateFilmPayload.errors 0 ZebraErr",
            "CreateFilmPayload.errors 1 AlphaErr"));
    }

    /**
     * Two fields naming one container are two ordered lists rather than one shared by reference.
     * That costs rows and buys a reader the ability to join on its own coordinate without first
     * knowing which container the field named.
     */
    @Test
    void twoFieldsOverOneUnionEachGetTheirOwnList() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload {
                film: Film
                errors: [WriteError]
            }
            type UpdateFilmPayload {
                film: Film
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
                updateFilm: UpdateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "update"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(members(dsl)).containsExactly(
            "CreateFilmPayload.errors 0 DbErr",
            "CreateFilmPayload.errors 1 ConflictErr",
            "UpdateFilmPayload.errors 0 DbErr",
            "UpdateFilmPayload.errors 1 ConflictErr"));
    }

    // ===== Which coordinate produces =====

    /**
     * A producing coordinate carries its family and where it sits. The root binding is a column and
     * not a filter, so the child {@code @service} field is a row here beside the root one, with the
     * operation absent rather than the row.
     */
    @Test
    void aProducerCarriesItsFamilyAndItsRootBinding() {
        var sdl = ERRORS + """
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

    /**
     * A child {@code @service} field produces exactly as a root one does; what differs is the
     * position, and the position is what one of the transport arms turns on.
     */
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

    // ===== The channel itself =====

    /**
     * A root {@code @service} field rides the typed {@code Outcome} wrapper, and that arm is tested
     * before the carrier arm: this payload is a carrier under its own family too, so the two arms
     * both answer and the precedence is what decides. The payload is a plain SDL type no class
     * backs, so the class is absent and the count says which kind of absence it is.
     */
    @Test
    void aRootServiceChannelRidesTheOutcomeWrapper() {
        var sdl = ERRORS + """
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
        withCapturedStore(sdl, dsl -> assertThat(channels(dsl)).containsExactly(
            "Mutation.createFilm OUTCOME_WRAPPER SERVICE CreateFilmPayload errors@1 null 0"));
    }

    /**
     * A {@code @mutation} write whose payload the carrier recognition admits ferries its errors
     * through graphql-java's localContext: no root {@code @service} field is involved, so the first
     * arm declines and the carrier arm answers.
     */
    @Test
    void aStructuralCarrierFerriesThroughLocalContext() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type DeleteFilmPayload {
                deletedId: ID
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                deleteFilm(filmId: Int): DeleteFilmPayload
                    @mutation(typeName: DELETE, table: "film")
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(channels(dsl)).containsExactly(
            "Mutation.deleteFilm LOCAL_CONTEXT DML DeleteFilmPayload errors@1 null 0"));
    }

    /**
     * Neither arm above: a child {@code @service} field, so not a root, returning a payload the
     * carrier recognition does not admit, that scan reading mutation roots only. What is left
     * constructs a developer payload class at the catch site.
     */
    @Test
    void anythingElseConstructsAPayloadClass() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type ActorFilmsPayload {
                films: [Film]
                errors: [WriteError]
            }
            type Actor @table(name: "actor") {
                films: ActorFilmsPayload
                    @service(service: {className: "com.example.FilmService", method: "byActor"})
            }
            type Query { actors: [Actor] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(channels(dsl)).containsExactly(
            "Actor.films PAYLOAD_CLASS SERVICE ActorFilmsPayload errors@1 null 0"));
    }

    /**
     * A payload declaring two errors-shaped fields routes through the first in declaration order,
     * and the ordinal that decided is carried so a reader can see the pick rather than trust it.
     * Both fields remain rows on {@code intent_errors_field}; only the channel picks.
     */
    @Test
    void thePayloadsFirstErrorsFieldIsTheChannel() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload {
                film: Film
                problems: [WriteError]
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> {
            assertThat(errorsFields(dsl)).containsExactly(
                "CreateFilmPayload.errors 2 WriteError UNION",
                "CreateFilmPayload.problems 1 WriteError UNION");
            assertThat(channels(dsl)).containsExactly(
                "Mutation.createFilm OUTCOME_WRAPPER SERVICE CreateFilmPayload problems@1 null 0");
        });
    }

    /**
     * A producer whose payload declares no errors-shaped field is absent rather than present with a
     * null transport. This relation is total over channels and not over producers, and that the
     * coordinate produces at all is a fact {@code intent_field_payload_producer} already carries.
     */
    @Test
    void aProducerWithNoErrorsFieldIsAbsent() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload { film: Film }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> {
            assertThat(producers(dsl)).containsExactly(
                "Mutation.createFilm SERVICE CreateFilmPayload MUTATION");
            assertThat(channels(dsl)).isEmpty();
        });
    }

    /**
     * The class the channel routes through, where one stands behind the payload: a {@code @table}
     * binding puts the table's generated record there, and the count says it is the only one.
     */
    @Test
    void aBoundPayloadCarriesItsRecordClass() {
        var sdl = ERRORS + """
            type Film @table(name: "film") {
                title: String
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: Film
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(channels(dsl))
            .singleElement()
            .asString()
            .startsWith("Mutation.createFilm OUTCOME_WRAPPER SERVICE Film errors@1 ")
            .endsWith(" 1")
            .contains("FilmRecord"));
    }

    // ===== Readers =====

    /**
     * Every errors-shaped field the graph holds: the coordinate, its declaration index, and the
     * container it maps through. Asserted whole so a field that should be no channel cannot hide.
     */
    private static List<String> errorsFields(DSLContext dsl) {
        var e = INTENT_ERRORS_FIELD;
        return dsl.select(e.fields())
            .from(e)
            .where(e.GRAPH_NAME.eq(GRAPH))
            .orderBy(e.TYPE_NAME, e.FIELD_NAME)
            .fetch()
            .map(row -> row.get(e.TYPE_NAME) + "." + row.get(e.FIELD_NAME) + " "
                + row.get(e.ORDINAL) + " " + row.get(e.CONTAINER_NAME) + " "
                + row.get(e.CONTAINER_KIND));
    }

    /** Every mapped {@code @error} type, ordered as the relation orders them. */
    private static List<String> members(DSLContext dsl) {
        var m = INTENT_ERRORS_FIELD_MEMBER;
        return dsl.select(m.fields())
            .from(m)
            .where(m.GRAPH_NAME.eq(GRAPH))
            .orderBy(m.TYPE_NAME, m.FIELD_NAME, m.POSITION)
            .fetch()
            .map(row -> row.get(m.TYPE_NAME) + "." + row.get(m.FIELD_NAME) + " "
                + row.get(m.POSITION) + " " + row.get(m.ERROR_TYPE_NAME));
    }

    /** Every producing coordinate: family, the type its value arrives as, and where it sits. */
    private static List<String> producers(DSLContext dsl) {
        var p = INTENT_FIELD_PAYLOAD_PRODUCER;
        return dsl.select(p.fields())
            .from(p)
            .where(p.GRAPH_NAME.eq(GRAPH))
            .orderBy(p.TYPE_NAME, p.FIELD_NAME, p.FAMILY)
            .fetch()
            .map(row -> row.get(p.TYPE_NAME) + "." + row.get(p.FIELD_NAME) + " "
                + row.get(p.FAMILY) + " " + row.get(p.PAYLOAD_TYPE_NAME) + " "
                + row.get(p.ROOT_OPERATION));
    }

    /**
     * Every error channel: the producing coordinate, the transport, the family, the payload, which
     * field of it is the channel and at what ordinal, the class the channel routes through and how
     * many back the payload.
     */
    private static List<String> channels(DSLContext dsl) {
        var c = INTENT_FIELD_ERROR_CHANNEL;
        return dsl.select(c.fields())
            .from(c)
            .where(c.GRAPH_NAME.eq(GRAPH))
            .orderBy(c.TYPE_NAME, c.FIELD_NAME, c.FAMILY)
            .fetch()
            .map(row -> row.get(c.TYPE_NAME) + "." + row.get(c.FIELD_NAME) + " "
                + row.get(c.TRANSPORT) + " " + row.get(c.FAMILY) + " "
                + row.get(c.PAYLOAD_TYPE_NAME) + " "
                + row.get(c.ERRORS_FIELD_NAME) + "@" + row.get(c.ERRORS_FIELD_ORDINAL) + " "
                + row.get(c.PAYLOAD_CLASS_NAME) + " " + row.get(c.PAYLOAD_CLASSES));
    }

    private void withCapturedStore(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), census())) {
            body.accept(store.dsl());
        }
    }

    /**
     * The real scan over the test classes, so any class a payload resolves through is one a build
     * would have found rather than a reference written to make the case pass.
     */
    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(ErrorChannelRelationTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }
}
