package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_CARRIER_DATA_FIELD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_carrier_data_field}: where a mutation payload's
 * data arrives. The relation answers the question a surface offering or judging the {@code $source}
 * sigil asks, and its boundary is most of what it claims, so half the cases here are payload shapes
 * that contribute nothing.
 *
 * <p>Every case captures real SDL against the test catalog rather than seeding rows, for the reason
 * {@code ReferenceStepTargetTest} states: a seeded fixture is free to declare a shape capture never
 * writes, and the case then pins behaviour no build can produce. The catalog supplies the bound
 * element types, and the classpath census the record-backed one.
 *
 * <p>Each case asserts the whole graph's rows rather than a projection of them, so a payload that
 * should contribute nothing fails the case it appears in.
 */
@PipelineTier
class CarrierDataFieldTest {

    @TempDir
    Path tmp;

    /** The error channel every carrier fixture below declares, so no case is a payload without one. */
    private static final String ERRORS = """
        type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
        union WriteError = DbErr
        """;

    // ===== The three families =====

    /**
     * The ordinary {@code @service} carrier: one {@code @table}-element data field beside an errors
     * channel, and the errors channel is not counted among the data fields.
     */
    @Test
    void aServiceCarrierNamesItsTableElementDataField() {
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
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).containsExactly(
            "CreateFilmPayload.film SERVICE TABLE 1"));
    }

    /**
     * A DELETE echo: the data field is the {@code ID} scalar the encoded primary key arrives on, and
     * the family is the one {@code @mutation} names.
     */
    @Test
    void aDmlCarrierNamesItsIdElementDataField() {
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
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).containsExactly(
            "DeleteFilmPayload.deletedId DML ID 1"));
    }

    /** A hop-less {@code @routine} write returns its result through a carrier of the third family. */
    @Test
    void aRoutineCarrierNamesItsTableElementDataField() {
        var sdl = ERRORS + """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload {
                rental: Rental
                errors: [WriteError]
            }
            type Query { rentals: [Rental] }
            type Mutation {
                rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                    @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).containsExactly(
            "RentFilmPayload.rental ROUTINE TABLE 1"));
    }

    /**
     * One payload two producers return is a row per family and not a pick, which is why the family
     * is a column: the two rejections that differ between families are answered per row.
     */
    @Test
    void aPayloadTwoFamiliesReturnIsARowPerFamily() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type FilmPayload {
                film: Film
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: FilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
                updateFilm(in: FilmInput!): FilmPayload @mutation(typeName: UPDATE)
            }
            input FilmInput @table(name: "film") { title: String }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).containsExactly(
            "FilmPayload.film DML TABLE 1",
            "FilmPayload.film SERVICE TABLE 1"));
    }

    // ===== What the element may be =====

    /**
     * The third element kind: a data field whose named type the backing closure reaches. Here the
     * producer's class backs the payload and the payload's own member hop backs the data field's
     * named type, which is the population the closure holds. Read on {@code declared_via} so a
     * {@code @table} type's generated record cannot answer here, that being the first arm's.
     */
    @Test
    void aClassBackedElementIsTheThirdKind() {
        var sdl = ERRORS + """
            type LanguageDto { name: String }
            type FilmPayload {
                language: LanguageDto
                errors: [WriteError]
            }
            type Query { placeholder: String }
            type Mutation {
                createFilm: FilmPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.derive.TestBackingService", method: "films"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).containsExactly(
            "FilmPayload.language SERVICE RECORD 1"));
    }

    /**
     * The two-level carrier is the one shape the store cannot see yet, and the cause is the backing
     * closure's own stated departure rather than anything here: the producer's class backs the
     * payload wrapper, where the walk reaches past the wrapper and grounds the data field's element
     * to it. So the element resolves to no kind and the payload names nothing. Pinned rather than
     * left to be noticed, since it is a shape the generator supports.
     */
    @Test
    void aTwoLevelCarrierNamesNothingBecauseTheClosureBacksTheWrapperInstead() {
        var sdl = ERRORS + """
            type ResultDto { title: String }
            type CreateFilmsPayload {
                results: [ResultDto]
                errors: [WriteError]
            }
            type Query { placeholder: String }
            type Mutation {
                createFilms: CreateFilmsPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.derive.TestBackingService", method: "films"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).isEmpty());
    }

    /**
     * An element of no recognized kind rejects the whole payload rather than its own coordinate: a
     * scalar data field is not a shape the generator carries a value on, so the type is not a
     * carrier and neither of its fields is named here.
     */
    @Test
    void anUnrecognizedElementDropsThePayloadAndNotOnlyItsOwnField() {
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
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).isEmpty());
    }

    // ===== The arity, and whose refusal it is =====

    /**
     * Two data channels are two rows counting two, which is the payload the generator rejects for
     * having no single data field. The relation reports rather than refuses, and a reader demanding
     * one transcribes the refusal without re-counting.
     */
    @Test
    void twoDataChannelsAreTwoRowsAndAnArityOfTwo() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            type CreateFilmPayload {
                film: Film
                actor: Actor
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).containsExactly(
            "CreateFilmPayload.actor SERVICE TABLE 2",
            "CreateFilmPayload.film SERVICE TABLE 2"));
    }

    /**
     * A payload that is all error channel has no data at all, so there is nothing to name; the
     * errors field is skipped as a channel of its own rather than counted as an unrecognized one.
     */
    @Test
    void aPayloadWithOnlyAnErrorsChannelNamesNothing() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload { errors: [WriteError] }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).isEmpty());
    }

    /**
     * The errors shape is a union or interface whose every member carries {@code @error}. A
     * polymorphic field whose members do not all is a data channel of no recognized kind, so it
     * drops its payload instead of quietly passing as an error channel.
     */
    @Test
    void aPolymorphicFieldWhoseMembersAreNotAllErrorsIsNotAnErrorChannel() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type Note { text: String }
            union Outcome = DbErr | Note
            type CreateFilmPayload {
                film: Film
                notes: [Outcome]
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).isEmpty());
    }

    // ===== The rejections that are a family's own =====

    /**
     * A directive naming a different fetcher contract routes the type out of the carrier mold
     * outright. {@code @notGenerated} is on every family's list, so no family answers for this
     * payload.
     */
    @Test
    void aForbiddenDataFieldDirectiveDropsThePayload() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload {
                film: Film @notGenerated
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).isEmpty());
    }

    /**
     * The rest of the forbidden set, one case per directive. Each names a fetcher contract of its
     * own, so a payload whose data field carries one is not a carrier and the whole payload drops.
     *
     * <p>Every spelling here is answered by the decoded relation the directive writes rather than by
     * the application row, so a probe pointed at the wrong relation passes the arm and admits the
     * payload. That is what this case is for: the arms were one name list against
     * {@code graphql_field_directive} and are now one probe each, and a list of strings is checked
     * by nothing where a relation name is checked by the compiler.
     *
     * <p>{@code @notGenerated} and {@code @splitQuery} have cases of their own, the first because it
     * is the one member with no decoded relation to ask and the second because it is the one the
     * families disagree about. {@code @lookupKey} is on the arm list and has no case because it
     * cannot reach this population at all: it is declared on ARGUMENT_DEFINITION and
     * INPUT_FIELD_DEFINITION, and a payload is an output object.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "@service(service: {className: \"com.example.FilmService\", method: \"create\"})",
        "@reference(path: [{table: \"film\"}])",
        "@externalField(reference: {className: \"com.example.FilmService\", method: \"create\"})",
        "@condition(condition: {className: \"no.sikt.graphitron.rewrite.TestConditionStub\","
            + " method: \"lifterFieldCondition\"})",
        "@defaultOrder(primaryKey: true)",
        "@multitableReference",
        "@sourceRow(className: \"com.example.FilmService\", method: \"create\")",
        "@asConnection",
    })
    void aDataChannelDirectiveDropsThePayload(String directive) {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type CreateFilmPayload {
                film: Film %s
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """.formatted(directive);
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).isEmpty());
    }

    /**
     * The one forbidden-set difference between the families, and the reason the family is a column:
     * on a producer-backed carrier {@code @splitQuery} is redundant rather than a different
     * contract, so {@code @service} tolerates it where {@code @mutation} does not.
     */
    @Test
    void splitQueryOnTheDataFieldIsToleratedByServiceAndNotByDml() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type FilmPayload {
                film: Film @splitQuery
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                createFilm: FilmPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
                updateFilm(in: FilmInput!): FilmPayload @mutation(typeName: UPDATE)
            }
            input FilmInput @table(name: "film") { title: String }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).containsExactly(
            "FilmPayload.film SERVICE TABLE 1"));
    }

    /**
     * The ID-element permit exists for the DELETE primary-key echo, and a routine write has no
     * echo shape, so a {@code @routine} carrier admits no ID data field at any wrapper.
     */
    @Test
    void aRoutineCarrierAdmitsNoIdElement() {
        var sdl = ERRORS + """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload {
                rentalId: ID
                errors: [WriteError]
            }
            type Query { rentals: [Rental] }
            type Mutation {
                rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                    @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).isEmpty());
    }

    /**
     * The other family-local ID rule: every element of a successful DELETE response is the encoded
     * key of a row that was actually deleted, so the slot cannot be null and {@code [ID]} is
     * refused. The same wrapper under {@code @service} is admitted, which is the case beside it.
     */
    @Test
    void aDmlCarrierRefusesTheListOfNullableIdWrapperAServiceCarrierAdmits() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type FilmIdsPayload {
                ids: [ID]
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                deleteFilms(filmId: Int): FilmIdsPayload @mutation(typeName: DELETE, table: "film")
                createFilms: FilmIdsPayload
                    @service(service: {className: "com.example.FilmService", method: "create"})
            }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).containsExactly(
            "FilmIdsPayload.ids SERVICE ID 1"));
    }

    // ===== What is not a carrier =====

    /**
     * Carrier-ness comes from the producing field and not from the payload's own shape: an ordinary
     * nesting type with one table-typed field looks exactly like a carrier and is not one, which is
     * the false reading this relation exists to not make.
     */
    @Test
    void aTypeNoMutationFieldReturnsIsNoCarrierHoweverItIsShaped() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type FilmHolder {
                film: Film
                errors: [WriteError]
            }
            type Query { holder: FilmHolder }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).isEmpty());
    }

    /**
     * The producing field has to be a write: the same {@code @service} application on a Query field
     * returns a payload-shaped type that is a fetch result rather than a carrier.
     */
    @Test
    void aServiceOnAQueryFieldIsNoCarrier() {
        var sdl = ERRORS + """
            type Film @table(name: "film") { title: String }
            type FilmResult {
                film: Film
                errors: [WriteError]
            }
            type Query {
                findFilm: FilmResult
                    @service(service: {className: "com.example.FilmService", method: "find"})
            }
            type Mutation { touch: Boolean }
            """;
        withCapturedStore(sdl, dsl -> assertThat(carriers(dsl)).isEmpty());
    }

    // ===== Helpers =====

    private static final String GRAPH = "CarrierDataFieldTest";

    /**
     * Every carrier data field the graph holds, one string per row: the coordinate, the producing
     * family, the element kind and the arity. Asserted whole so a payload that should name nothing
     * cannot hide behind a filter.
     */
    private static List<String> carriers(DSLContext dsl) {
        var c = INTENT_CARRIER_DATA_FIELD;
        return dsl.select(c.fields())
            .from(c)
            .where(c.GRAPH_NAME.eq(GRAPH))
            .orderBy(c.TYPE_NAME, c.FIELD_NAME, c.FAMILY)
            .fetch()
            .map(row -> row.get(c.TYPE_NAME) + "." + row.get(c.FIELD_NAME) + " "
                + row.get(c.FAMILY) + " " + row.get(c.ELEMENT_KIND) + " " + row.get(c.DATA_FIELDS));
    }

    private void withCapturedStore(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), census())) {
            body.accept(store.dsl());
        }
    }

    /**
     * The real scan over the test classes, so the class the record-backed element resolves through is
     * one a build would have found rather than a reference written to make the case pass.
     */
    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(CarrierDataFieldTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }
}
