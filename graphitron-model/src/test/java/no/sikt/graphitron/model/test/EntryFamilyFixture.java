package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.capture.document.GraphitronEntries;
import no.sikt.graphitron.model.capture.document.SdlEntries;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaSource;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * A schema that applies every graphitron directive the decode writes a relation for, spread over two
 * documents. Two declared names are left out because nothing decodes them: the rewrite rejects every
 * application of {@code @notGenerated} as no longer supported, and no code reads an application of
 * {@code @experimental_constructType} at all. Both stay declared so a consumer's existing use still
 * parses, so their absence here is not a coverage hole.
 *
 * <p>It exists because a gate over the as-written half of the {@code graphitron_} family had nothing
 * to compare. The schemas the capture cases carry are each about one question, so most of that half
 * holds no row in any of them, and a differential over relations nobody wrote to agrees by being
 * empty twice rather than by agreeing. {@code EntryFamilyCoverageTest} is what holds this fixture to
 * filling the half, so a gate resting on it rests on something.
 *
 * <p>Two documents rather than one, and the second is not a spillover. A refresh that deletes one
 * source's rows has to be shown deleting one source's rows, which needs a relation holding rows from
 * two sources at once so that what survives is visible. Several relations are therefore written from
 * both documents deliberately, {@code graphitron_table_entry},
 * {@code graphitron_field_binding_entry} and {@code graphitron_field_reference_entry} among them,
 * and the coverage gate holds that overlap rather than leaving it to whoever edits the SDL next.
 *
 * <p>The schema is not meant to be a schema anybody would write. It names tables and columns that
 * need not exist, because an entry is what the author wrote and resolves against nothing: the whole
 * of what the decode does with {@code @table(name: "film")} is record the spelling. A fixture that
 * also had to satisfy the catalog would be a fixture about the catalog.
 *
 * <p>{@code @nodeId} is written both ways on purpose, bare on {@code Film.id} and naming its type on
 * {@code Actor.id}. The two are different things said: a bare application asks for the type to be
 * deduced and writes no row, so a fixture carrying only that one would leave the relation empty and
 * the gate below would be right to say so.
 *
 * <p>Two path elements carry a {@code condition:} of their own. That arm of a step reaches the
 * classpath census where the others reach the catalog, so it is the one element shape whose decode
 * relation the rest of this schema would leave empty, and an empty relation is what the coverage
 * gate exists to refuse.
 *
 * <p>One application is deliberately malformed. {@code Mutation.brokenMapping} carries an
 * {@code argMapping} the grammar rejects, because quarantining a value it cannot decode is one of
 * the things the decode does: a fixture that only ever hands it decodable values leaves
 * {@code graphitron_undecoded_argument_entry} empty and says nothing about the path that writes it.
 */
public final class EntryFamilyFixture {

    private EntryFamilyFixture() {}

    /**
     * The instant every row of a capture carries, fixed rather than read off the clock. Two
     * captures of this fixture are compared row for row by the corpus-isolation differential, and a
     * stamp taken at capture time would make every stamped row differ on when the test ran.
     */
    private static final LocalDateTime READ_AT = LocalDateTime.of(2026, 1, 1, 12, 0);

    /** The first document's name, and so the stem of the file the fixture writes it to. */
    public static final String FIRST = "core";

    /** The second document's name. */
    public static final String SECOND = "extension";

    /**
     * Type-level vocabulary, the output-field vocabulary, and the enum ordering vocabulary.
     *
     * <p>Federation's {@code @link} and {@code @key} are declared here rather than assumed, the way
     * a federated consumer's own schema declares them. {@code @link}'s import list carries both
     * spellings the grammar admits, a bare name and an aliased object, so that
     * {@code graphitron_link_import_entry.alias} holds a value somewhere rather than being NULL on
     * every row. The coverage gate counts rows and would not notice, which is the point of saying
     * it here.
     */
    public static final String CORE = """
        scalar link__Import

        directive @link(url: String!, import: [link__Import]) repeatable on SCHEMA
        directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT

        extend schema @link(
          url: "https://specs.apollo.dev/federation/v2.10"
          import: ["@key", {name: "@shareable", as: "@federatedShareable"}]
        )

        scalar Money @scalarType(scalar: "graphql.scalars.ExtendedScalars.GraphQLBigDecimal")

        type Query {
          films(
            orderBy: FilmOrder @orderBy
            titleFilter: String @field(name: "title")
            actorId: ID @nodeId(typeName: "Actor")
          ): [Film!]
            @asConnection(defaultFirstValue: 25)
            @defaultOrder(fields: [{name: "title", collate: "xdanish_ai", direction: DESC}])

          film(id: ID! @nodeId(typeName: "Film") @lookupKey): Film

          reportedFilms: [Film!]
            @routine(name: "public.reported_films", argMapping: "pEnv: env", columnMapping: "pFilmId: film_id")

          media: [Media!] @referenceFor(type: "Book", path: [{table: "book", key: "book_media_fk"}])
        }

        interface Media @discriminate(on: "media_type") {
          id: ID!
        }

        type Book implements Media @table(name: "book") @discriminator(value: "BOOK") {
          id: ID!
        }

        type Film implements Media
          @table(name: "film")
          @node(typeId: "F", keyColumns: ["film_id"])
          @key(fields: "id reviews { id }", resolvable: true)
        {
          id: ID! @nodeId
          title: String @field(name: "title")
          rentalRate: Money
          actors: [Actor!] @reference(path: [{table: "film_actor"}, {table: "actor", key: "film_actor_actor_id_fk"}])
          reviews: [Review!] @splitQuery
          awardCount: Int @externalField(reference: {className: "no.example.FilmFields", method: "awardCount"})
          ratingBreakdown: RatingBreakdown
            @reference(path: [{table: "film_rating"}])
            @pivot(on: "rating_code", value: "score", vocabulary: "Rating")
          branchStock: [Stock!] @tenantFanOut
          summary: String
            @condition(
              condition: {className: "no.example.Conditions", method: "visibleFilm", argMapping: "tenant: tenantId"}
              override: true
              contextArguments: ["tenantId"]
            )
          legacyRoutes: [Media!] @multitableReference(routes: [{typeName: "Book", path: [{table: "book"}]}])
        }

        type RatingBreakdown {
          good: Int
          bad: Int
        }

        type Stock @table(name: "stock") {
          id: ID!
        }

        type Review @table(name: "review") {
          id: ID!
        }

        type Actor @table(name: "actor") @node(typeId: "A") {
          id: ID! @nodeId(typeName: "Actor")
          fullName: String @field(name: "full_name")
          agency: Agency @sourceRow(className: "no.example.ActorRows", method: "agencyKey")
        }

        type Agency @record(record: {className: "no.example.AgencyRecord"}) {
          id: ID!
        }

        type FilmError @error(handlers: [
          {handler: GENERIC, className: "java.lang.IllegalStateException", matches: "missing", description: "The film is gone"},
          {handler: DATABASE, sqlState: "23503"},
          {handler: VALIDATION}
        ]) {
          path: [String]
          message: String
        }

        enum FilmOrder @enum(enumReference: {className: "no.example.FilmOrderEnum"}) {
          TITLE @order(fields: [{name: "title", collate: "xdanish_ai", direction: DESC}])
          RELEASED @index(name: "idx_film_released")
          RATING @field(name: "rating_code")
        }
        """;

    /**
     * The input side, the argument side, and the mutation vocabulary.
     *
     * <p>It repeats several of the first document's directives on coordinates of its own, which is
     * the overlap a per-source refresh needs: a relation written from one document only cannot show
     * that deleting the other document's rows left it alone.
     */
    public static final String EXTENSION = """
        extend type Query {
          actorsByAgency(
            agencyId: ID @reference(path: [
                {table: "agency"},
                {table: "actor", key: "actor_agency_fk",
                 condition: {className: "no.example.Conditions", method: "liveAgency"}}
              ])
            filter: ActorFilter
            scope: ID
              @condition(
                condition: {className: "no.example.Conditions", method: "inScope", argMapping: "scope: scopeId"}
                contextArguments: ["tenantId"]
              )
            target: ID @referenceFor(type: "Book", path: [
                {table: "book", key: "book_actor_fk",
                 condition: {className: "no.example.Conditions", method: "liveBook"}}
              ])
            nameFilter: String @field(name: "full_name")
          ): [Actor!] @field(name: "actor")

          searchFilms(filter: FilmFilter): [Film!] @asConnection
        }

        input FilmFilter {
          title: String @field(name: "title") @asFacet
          category: String @field(name: "category") @asFacet
          actorId: ID @nodeId(typeName: "Actor")
          rentalKey: ID @lookupKey
          joined: String @reference(path: [{table: "film_actor"}])
          guarded: String @condition(condition: {className: "no.example.Conditions", method: "guard"})
        }

        input ActorFilter @table(name: "actor") {
          name: String @field(name: "full_name")
        }

        type Mutation {
          deleteFilm(input: DeleteFilmInput!): Boolean
            @mutation(typeName: DELETE, multiRow: true, table: "film")
          importFilms(payload: ImportInput!): [Film!]
            @service(
              service: {className: "no.example.FilmService", method: "importFilms", argMapping: "payload: payload"}
              contextArguments: ["tenantId"]
            )
          brokenMapping(payload: ImportInput!): Boolean
            @service(service: {className: "no.example.FilmService", method: "broken", argMapping: "this is ] not [ a mapping"})
        }

        input DeleteFilmInput {
          id: ID @nodeId(typeName: "Film")
        }

        input ImportInput {
          title: String
        }
        """;

    /** Captures both documents into one graph, which is the shape every case here wants. */
    public static CapturedStore capture(Path directory) {
        return capture(directory, null);
    }

    /**
     * The same capture with a jOOQ catalog beside it, or without one when {@code jooq} is null. The
     * two arms of a catalog differential are one call each, and the fixture is what makes that
     * differential worth running: the schema names tables the catalog need not hold, so what the
     * arms are compared on is the decode's own output rather than a resolution.
     */
    public static CapturedStore capture(Path directory, JooqCatalog jooq) {
        var captured = CapturedStore.ofFiles(directory, FIRST, CORE, SECOND, EXTENSION, jooq);
        perDocument(captured, directory);
        return captured;
    }

    /**
     * The per-document writers over the same two files. They take one parse at a time where the
     * walk above takes the merged registry, because their rows are keyed by the position a node was
     * written at and two documents writing one name are two rows.
     */
    private static void perDocument(CapturedStore captured, Path directory) {
        var files = List.of(SchemaSource.file(CapturedStore.fixtureFile(directory, FIRST)),
            SchemaSource.file(CapturedStore.fixtureFile(directory, SECOND)));
        for (var document : SchemaLoader.parsePerSource(files).perSource()) {
            SeededStore.seedSource(captured.dsl(), document.sourceName(), "SCHEMA_FILE");
            SdlEntries.write(captured.dsl(), captured.graphName(), document.sourceName(),
                document.registry(), READ_AT);
            GraphitronEntries.write(captured.dsl(), captured.graphName(), document.sourceName(),
                document.registry(), READ_AT);
        }
    }

    /**
     * The as-written half of the {@code graphitron_} family: every relation the directive decode
     * writes, which is every relation whose rows are a function of one document and nothing else.
     *
     * <p>Read off the generated model by suffix rather than listed. The list this replaced was a
     * stand-in for a name: it had to be edited whenever the family grew, and a relation added to
     * neither half slipped quietly out of every gate that read it. Now the name is the
     * classification, which is the whole point of the suffix and is what a reader looking for the
     * anti-join between the two halves needs anyway. What no longer has a mechanical statement here
     * is that a new decode relation gets named right, and that is deliberately somewhere else:
     * {@code EntryNamingGuardTest} holds the decode to naming only suffixed relations, and the
     * declaration pass will say it a second time by giving each relation a declared owner.
     *
     * <p>Only this half is offered. Its complement is every other {@code graphitron_} relation, and
     * that is a bag rather than a half: the stages write most of it, but the
     * {@code graphitron_argmapping_match} view is written by nothing at all, and a suffix cannot
     * tell those apart. A caller wanting the resolved half wants a declared owner, which the
     * declaration pass gives it and a name never could.
     */
    public static List<String> entryRelations() {
        return Public.PUBLIC.getTables().stream()
            .map(table -> table.getName().toLowerCase(Locale.ROOT))
            .filter(name -> name.startsWith("graphitron_") && name.endsWith("_entry"))
            .sorted()
            .toList();
    }
}
