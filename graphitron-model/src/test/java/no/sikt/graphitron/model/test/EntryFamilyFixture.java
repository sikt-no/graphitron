package no.sikt.graphitron.model.test;

import java.nio.file.Path;
import java.util.List;

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
 * both documents deliberately, {@code graphitron_table}, {@code graphitron_field_binding} and
 * {@code graphitron_field_reference} among them, and the coverage gate holds that overlap rather
 * than leaving it to whoever edits the SDL next.
 *
 * <p>The schema is not meant to be a schema anybody would write. It names tables and columns that
 * need not exist, because an entry is what the author wrote and resolves against nothing: the whole
 * of what the decode does with {@code @table(name: "film")} is record the spelling. A fixture that
 * also had to satisfy the catalog would be a fixture about the catalog.
 *
 * <p>One application is deliberately malformed. {@code Mutation.brokenMapping} carries an
 * {@code argMapping} the grammar rejects, because quarantining a value it cannot decode is one of
 * the things the decode does: a fixture that only ever hands it decodable values leaves
 * {@code graphitron_undecoded_argument} empty and says nothing about the path that writes it.
 */
public final class EntryFamilyFixture {

    private EntryFamilyFixture() {}

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
     * {@code graphitron_link_import.alias} holds a value somewhere rather than being NULL on every
     * row. The coverage gate counts rows and would not notice, which is the point of saying it here.
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
          id: ID!
          fullName: String @field(name: "full_name")
          agency: Agency @sourceRow(className: "no.example.ActorRows", method: "agencyKey")
        }

        type Agency @record(record: {className: "no.example.AgencyRecord"}) {
          id: ID!
        }

        type FilmError @error(handlers: [
          {handler: GENERIC, className: "java.lang.IllegalStateException", matches: "missing", description: "The film is gone"},
          {handler: DATABASE, sqlState: "23503"}
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
            agencyId: ID @reference(path: [{table: "agency"}, {table: "actor", key: "actor_agency_fk"}])
            filter: ActorFilter
            scope: ID
              @condition(
                condition: {className: "no.example.Conditions", method: "inScope", argMapping: "scope: scopeId"}
                contextArguments: ["tenantId"]
              )
            target: ID @referenceFor(type: "Book", path: [{table: "book", key: "book_actor_fk"}])
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
        return CapturedStore.ofFiles(directory, FIRST, CORE, SECOND, EXTENSION);
    }

    /**
     * The as-written half of the {@code graphitron_} family: every relation the directive decode
     * writes, which is every relation whose rows are a function of one document and nothing else.
     *
     * <p>Listed rather than derived, and the coverage gate is what keeps the list honest: it holds
     * this list and {@link #ANCHOR_RELATIONS} to partitioning the family, so a relation added to
     * neither fails rather than being quietly uncovered. The list dissolves into a suffix rule once
     * the entry half carries {@code _entry}, at which point the gate reads the name instead.
     */
    public static final List<String> ENTRY_RELATIONS = List.of(
        "graphitron_argmapping_entry",
        "graphitron_argument_binding",
        "graphitron_argument_condition",
        "graphitron_argument_condition_context_arg",
        "graphitron_argument_lookup_key",
        "graphitron_argument_node_id",
        "graphitron_argument_reference",
        "graphitron_argument_reference_for",
        "graphitron_argument_reference_for_step",
        "graphitron_argument_reference_step",
        "graphitron_connection",
        "graphitron_default_order",
        "graphitron_default_order_field",
        "graphitron_discriminate",
        "graphitron_discriminator",
        "graphitron_enum",
        "graphitron_enum_value_binding",
        "graphitron_error",
        "graphitron_error_handler",
        "graphitron_external_field",
        "graphitron_facet",
        "graphitron_federation_key",
        "graphitron_federation_key_field",
        "graphitron_federation_key_field_segment",
        "graphitron_field_binding",
        "graphitron_field_condition",
        "graphitron_field_condition_context_arg",
        "graphitron_field_lookup_key",
        "graphitron_field_node_id",
        "graphitron_field_reference",
        "graphitron_field_reference_step",
        "graphitron_index",
        "graphitron_link",
        "graphitron_link_import",
        "graphitron_method_reference",
        "graphitron_multitable_reference",
        "graphitron_mutation",
        "graphitron_node_entry",
        "graphitron_node_keycolumn_entry",
        "graphitron_order",
        "graphitron_order_by",
        "graphitron_order_field",
        "graphitron_pivot",
        "graphitron_record",
        "graphitron_reference_for",
        "graphitron_reference_for_step",
        "graphitron_routine",
        "graphitron_routine_column_mapping_pair",
        "graphitron_scalar_type",
        "graphitron_service",
        "graphitron_service_context_arg",
        "graphitron_spelled_reference",
        "graphitron_split_query",
        "graphitron_table",
        "graphitron_tenant_fan_out",
        "graphitron_undecoded_argument");

    /**
     * The resolved half: the fifteen relations a gatherer stage writes by joining, ranking or
     * reaching the catalog, plus {@code graphitron_argmapping_match}, which is a view joining an
     * entry to a candidate and so is written by nothing at all. None of them is this fixture's
     * subject, and none could be: a bare SDL capture has no catalog to resolve against, which is the
     * same statement as their not being entries. The list is here to make the other one checkable
     * rather than to say anything about these, which is why one non-entry shape it does not
     * distinguish, a view, sits in it without a category of its own.
     */
    public static final List<String> ANCHOR_RELATIONS = List.of(
        "graphitron_argmapping_candidate",
        "graphitron_argmapping_match",
        "graphitron_argument",
        "graphitron_element",
        "graphitron_field",
        "graphitron_field_chain_application",
        "graphitron_field_navigation",
        "graphitron_field_table",
        "graphitron_minted_argument",
        "graphitron_minted_conflict",
        "graphitron_minted_field",
        "graphitron_minted_type",
        "graphitron_node",
        "graphitron_node_keycolumn",
        "graphitron_tabletype",
        "graphitron_type");
}
