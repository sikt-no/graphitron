package no.sikt.graphitron.rewrite.classifieddsl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The R281 spec-by-example corpus: the annotated fixture schemas that, between them, demonstrate the
 * classifier's dimensional verdicts. It is the single source of truth shared by everything that reads
 * the corpus, the DSL assertions ({@link ClassifiedDslTest}), the leaf-coverage bridge
 * ({@code VariantCoverageTest}), and the query-as-view documentation renderer.
 *
 * <p>Each {@code @classified} coordinate asserts the three-axis {@code (source, operation, target)}
 * verdict R316 migrated the corpus onto (from R299's {@code (carrier, intent, mapping)}, itself from
 * R281's original {@code (producer, mapping)}); between them the fixtures exercise every
 * {@link no.sikt.graphitron.rewrite.model.Source} wrapper arm, every
 * {@link no.sikt.graphitron.rewrite.model.Target} wrapper and {@code TargetShape} arm, and every
 * populated {@link no.sikt.graphitron.rewrite.model.Operation} arm, with the modeled-but-unpopulated
 * arms tracked as known gaps in {@link ClassifiedDslTest}. The set grows example by example as the
 * {@code code-generation-triggers} documentation pulls each one in (see {@link #coveredLeaves()}).
 */
public final class ClassifiedCorpus {

    private ClassifiedCorpus() {}

    /**
     * One corpus entry: a stable id (used as the test display name), its annotated fixture SDL, and an
     * optional documentation projection {@code query}. When {@code query} is non-null the entry is also
     * a documentation example, the query selects the coordinates the {@code code-generation-triggers}
     * page renders for it (via {@link QueryViewRenderer}); see {@link #docExamples()}.
     */
    public record Example(String id, String sdl, String query) {
        public Example(String id, String sdl) {
            this(id, sdl, null);
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private static final List<Example> EXAMPLES = List.of(
        /* Catalog side: a root query, a Relay connection, an inline column, and a TableType. */
        new Example("catalog", """
            type Query @classifiedType(as: RootType) {
              film: Film @classified(source: Query, operation: Fetch, target: Single, targetShape: Table)
              films: [Film!]! @asConnection @classified(source: Query, operation: Paginate, target: Single, targetShape: Connection)
            }

            type Film @table(name: "film") @classifiedType(as: TableType) {
              title: String @classified(source: Child, operation: Fetch, target: Single, targetShape: Column)
            }
            """,
            """
            {
              # A single film, fetched by primary key.
              film {
                # The film's display title.
                title
              }
            }
            """),

        /*
         * Enum-typed scalar: a field whose GraphQL return type is an enum still resolves to a real DB
         * column on the @table parent, so it classifies exactly like any other inline scalar (source
         * Child(Table), operation Fetch, target Single(Column), ColumnField). The enum-ness lives in the
         * GraphQL-to-Java conversion, not the classification. Corpus-only: it lands on the already-taught
         * Child / Fetch / Column coordinate; this pins the "enum returns are columns" edge that the retired
         * ENUM_RETURN_TYPE enum row asserted.
         */
        new Example("enum-column", """
            enum Rating @classifiedType(as: EnumType) { G PG PG13 R NC17 }
            type Film @table(name: "film") {
              rating: Rating @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Column)
            }
            type Query { film: Film }
            """),

        /*
         * Child table fields over the same city -> country FK. Both return the same @table type and hold
         * target Single(Table), operation Fetch. They differ only on the derived new-query layer (not a
         * tuple axis): `country` inlines as a correlated subquery folded into city's SELECT; `@splitQuery`
         * flips `countrySplit` to a new keyed query. The verdict is identical (Child / Fetch / Table);
         * the split is a derived consequence of the @splitQuery slot.
         */
        new Example("child-table", """
            type Country @table(name: "country") @classifiedType(as: TableType) {
              name: String @field(name: "country") @classified(source: Child, operation: Fetch, target: Single, targetShape: Column)
            }

            type City @table(name: "city") @classifiedType(as: TableType) {
              country: Country @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Table)
              countrySplit: Country @splitQuery @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Table)
            }

            type Query {
              city: City @classified(source: Query, operation: Fetch, target: Single, targetShape: Table)
            }
            """,
            "{ city { country { name } countrySplit { name } } }"),

        /*
         * Keyed split lookup: a list child whose @lookupKey argument establishes a positional
         * input-list <-> output-list correspondence, fetched by a @splitQuery keyed batch
         * (SplitLookupTableField). The @lookupKey makes its operation Lookup; it lands on participant @table
         * rows (target Table); the new-query batch shape is derived, not a tuple axis. Corpus-only: it
         * is another Child / Lookup / Table leaf.
         */
        new Example("split-lookup", """
            type Customer @table(name: "customer") { firstName: String @field(name: "FIRST_NAME") }
            type Store @table(name: "store") {
              customers(customer_id: ID! @lookupKey): [Customer!]! @splitQuery
                @classified(source: OnlyChild, operation: Lookup, target: List, targetShape: Table)
            }
            type Query { store: Store }
            """),

        /*
         * Target-shape minimal pair: Column vs Field. A scalar under the @table parent Film projects a
         * Column (`title` is a real DB column); a scalar under a record-backed parent projects a Field
         * (`FilmStats.count` is a POJO property, the record having no @table). The non-table object
         * field `FilmDetails.stats` is the object flavor of Field (RecordField). All three are inline
         * Fetch; only the parent's table-ness moves the source shape (Table vs Record) and with it the
         * target shape. The two parents are record-bound by being service producers' return types
         * (`makeFilmDetailsRecord` -> FilmDetailsRecord, whose sole component is `stats`;
         * `makeFilmStatsRecord` -> FilmStatsRecord, whose sole component is `count`).
         */
        new Example("mapping", """
            type FilmStats {
              count: Int @classified(source: Child, operation: Fetch, target: Single, targetShape: Field, sourceShape: Record)
            }

            type FilmDetails {
              stats: FilmStats @classified(source: Child, operation: Fetch, target: Single, targetShape: Field, sourceShape: Record)
            }

            type Film @table(name: "film") {
              title: String @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Column)
              details: FilmDetails
            }

            type Query {
              film: Film
              prodFilmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmDetailsRecord"})
              prodFilmStats: FilmStats
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmStatsRecord"})
            }
            """,
            "{ film { title details { stats { count } } } }"),

        /*
         * The record-handoff boundary. The same FK-reached @table child (`language` via
         * film_language_id_fkey) inlines into the parent SELECT under the @table parent Film (TableField)
         * but becomes a keyed re-query under the record-backed parent FilmDetails (RecordTableField),
         * because the record handoff has already opened a new DataLoader-backed scope; it cannot fold
         * back into the parent SELECT. Both hold the same operation/target (Fetch / Table); they differ
         * only on the source shape (Table vs Record), the new-query a derived consequence of the
         * record-handoff slot, not a distinct operation. FilmDetails is
         * record-bound as makeDummyRecord's return type; the explicit @reference disambiguates film's two
         * FKs to language.
         */
        new Example("record-table", """
            type Language @table(name: "language") { name: String }

            type FilmDetails {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: Child, operation: Fetch, target: Single, targetShape: Table, sourceShape: Record)
            }

            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Table)
              details: FilmDetails
            }

            type Query {
              film: Film
              prodFilmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            "{ film { language { name } details { language { name } } } }"),

        /* Service side: a terminal record, a service re-query into a @table, and a pojo field. */
        new Example("service", """
            type Language @table(name: "language") { name: String }

            type FilmDetails {
              title: String @classified(source: Child, operation: Fetch, target: Single, targetShape: Field, sourceShape: Record)
            }

            type Film @table(name: "film") {
              details: FilmDetails
              rating: String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
                @classified(source: Child, operation: ServiceCall, target: Single, targetShape: Record)
              language: Language
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
                @classified(source: Child, operation: ServiceCall, target: Single, targetShape: Table)
            }

            type Query {
              film: Film
              prodFilmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDetailsProps"})
              externalFilm: Film
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
                @classified(source: Query, operation: ServiceCall, target: Single, targetShape: Table)
            }
            """),

        /*
         * Root @service into a record-backed type: a root query field whose @service resolver returns a non-table
         * record-backed type (QueryServiceRecordField). The service call produces the record, which is then
         * materialized rather than projected from the catalog, so it is source Query, operation
         * ServiceCall, target Single(Record), the root analog of the ServiceRecordField child field above
         * (Film.rating). Corpus-only: it lands on the already-taught Query / ServiceCall / Record coordinate.
         * The @service producer's reflected return type binds the payload here.
         */
        new Example("query-service-record", """
            type FilmDetails { title: String }
            type Query {
              filmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getDetails"})
                @classified(source: Query, operation: ServiceCall, target: Single, targetShape: Record)
            }
            """),

        /*
         * Result-type backing (a type-verdict cluster). A non-@table result type acquires its backing
         * class by reflection on the @service producer's return type (R276), never from a directive, and
         * the GraphitronType leaf reflects what that class is: a plain Java class is PojoResultType.Backed
         * (`as: Backed`), a Java record is JavaRecordType, a jOOQ TableRecord is JooqTableRecordType.
         * Corpus-only: the @classifiedType axis is asserted directly; there is no field-side dimensional
         * lesson here. The `name` field on the Java-record-backed type (a record component of TestRecordDto)
         * doubles as the fixture's required field coordinate, classifying Child / Fetch / Field off the
         * record-shaped source backing.
         */
        new Example("result-backing", """
            type PojoBacked @classifiedType(as: Backed) { id: ID }
            type JavaRecordBacked @classifiedType(as: JavaRecordType) {
              name: String @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Field, sourceShape: Record)
            }
            type JooqTableRecordBacked @classifiedType(as: JooqTableRecordType) { id: ID }
            type Query {
              pojo: PojoBacked
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
              javaRecord: JavaRecordBacked
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeTestRecordDto"})
              jooqRecord: JooqTableRecordBacked
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """),

        /*
         * Fields on an @error parent. The @error contract restricts the field set to exactly
         * `path: [String!]!` and `message: String!`; both resolve off the developer-supplied error
         * class via graphql-java's default PropertyDataFetcher, so both classify as PropertyField
         * (Child / Fetch / Field). Corpus-only: it lands on the already-taught Child / Fetch / Field
         * coordinate, and the @error type itself is not a documentation-query selection shape.
         */
        new Example("error-field", """
            type MyError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
              path: [String!]! @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Field, sourceShape: Record)
              message: String! @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Field, sourceShape: Record)
            }
            type Query { err: MyError }
            """),

        /*
         * @error type-verdict admission nuance. An @error type classifies as ErrorType (the GraphQL
         * type whose @error contract carries the handler set). A field beyond the mandatory
         * path/message (`severity`) does not break the verdict: the per-handler accessor check fires
         * on the carrier, not the @error type, so the type stays ErrorType. Corpus-only: the
         * @classifiedType axis is asserted directly; `path` doubles as the fixture's required field
         * coordinate (Child / Fetch / Field). (The @error + @record silently-ignored verdict, the
         * D1 precedence rule, is covered by RecordDirectiveIgnoredWarningTest, the one place applied
         * @record remains; see R307.)
         */
        new Example("error-type", """
            enum Severity { LOW HIGH }
            type ExtraFieldError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}])
                @classifiedType(as: ErrorType) {
              path: [String!]! @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Field, sourceShape: Record)
              message: String!
              severity: Severity!
            }
            type Query { err: ExtraFieldError }
            """),

        /*
         * Nesting: a plain object child (no @table, no @record) on a @table parent inlines into the
         * parent's projection, inheriting the parent's table context (NestingField). Its scalars resolve
         * against the parent table, so the field projects Table and its operation is Nest (a distinct
         * structural operation, asserted, not derived from an absent join-path). Corpus-only; this adds
         * the NestingField leaf and the Nest operation to the corpus's covered set.
         */
        new Example("nesting", """
            type FilmDetails @classifiedType(as: NestingType) { title: String description: String }
            type Film @table(name: "film") {
              details: FilmDetails @classified(source: OnlyChild, operation: Nest, target: Single, targetShape: Table)
            }
            type Query { film: Film }
            """),

        // The former "constructor" example (a record-backed child type under a @table parent, Film.details
        // building the record-backed FilmDetails from the parent's row) left the classified corpus when
        // R290 dissolved ConstructorField as wrong-by-design: that table-and-service clash is now a
        // build-time rejection, asserted at the validator tier by ConstructorFieldValidationTest rather
        // than as a clean classification here. The Record target shape stays exercised by ErrorsField,
        // ServiceRecordField, and the DML record carriers.

        /*
         * Polymorphic children and roots are catalog-bound over their participant tables: the target shape
         * is Interface / Union (the projection lands on participant @table rows), with the participant set
         * carried as a derived slot rather than a distinct shape value, and the operation is Fetch. A
         * plain-interface or union child (InterfaceField / UnionField) and any polymorphic root
         * (QueryInterfaceField / QueryUnionField) share that Fetch verdict; the new-query they open is
         * derived, not an axis. The exception's verdict is the same: a @table+@discriminate interface child
         * (TableInterfaceField) is FK-correlatable from the parent and classifies as a plain Fetch,
         * though the generator currently emits a per-parent query (the R288 defect; the corpus asserts the
         * correct verdict). Of the four shapes below (plain interface, union, table-interface, Relay Node)
         * the interface and the union render doc examples, the interface over its shared interface-level
         * field and the union through inline fragments on its participants (renderer hardening item 3);
         * table-interface and Relay Node stay corpus-only.
         */
        new Example("interface", """
            interface Named @classifiedType(as: InterfaceType) { name: String }
            type Address implements Named @table(name: "address") { name: String @field(name: "ADDRESS") }
            type Customer @table(name: "customer") {
              address: Named @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Interface)
            }
            type Query {
              customer: Customer
              anyNamed: Named @classified(source: Query, operation: Fetch, target: Single, targetShape: Interface)
            }
            """,
            "{ customer { address { name } } }"),

        new Example("union", """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
            union FilmOrActor @classifiedType(as: UnionType) = Film | Actor
            type FilmActor @table(name: "film_actor") {
              related: FilmOrActor @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Union)
            }
            type Query {
              filmActor: FilmActor
              search: FilmOrActor @classified(source: Query, operation: Fetch, target: Single, targetShape: Union)
            }
            """,
            "{ filmActor { related { ... on Film { title } ... on Actor { firstName } } } }"),

        /*
         * R365 route (a): a root @service field returning a multitable interface
         * (QueryServicePolymorphicField, single cardinality). The service hands back a PK-populated
         * TableRecord per branch; the verdict is source Query, operation ServiceCall (the developer method
         * replaces the catalog read), and target Single, target shape Interface. Distinct-table
         * participants (film, actor) so record-class dispatch is well-defined. Interface only — a @service
         * returning a union is permanently unsupported (rejected at classify). Corpus-only: it adds the
         * QueryServicePolymorphicField leaf and lands on the Query / ServiceCall coordinate.
         */
        new Example("query-service-polymorphic", """
            interface Searchable @classifiedType(as: InterfaceType) { name: String }
            type Film implements Searchable @table(name: "film") { name: String @field(name: "TITLE") }
            type Actor implements Searchable @table(name: "actor") { name: String @field(name: "FIRST_NAME") }
            type Query {
              searchService: Searchable
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
                @classified(source: Query, operation: ServiceCall, target: Single, targetShape: Interface)
            }
            """),

        /*
         * R365 route (a): mutation analogue (MutationServicePolymorphicField), list cardinality. The
         * service returns a Result<FilmRecord>; the fetcher dispatches each returned record on its runtime
         * class. Verdict: source Mutation, operation ServiceCall, target List. Corpus-only: adds the
         * MutationServicePolymorphicField leaf and lands on the Mutation / ServiceCall coordinate.
         */
        new Example("mutation-service-polymorphic", """
            interface Searchable @classifiedType(as: InterfaceType) { name: String }
            type Film implements Searchable @table(name: "film") { name: String @field(name: "TITLE") }
            type Actor implements Searchable @table(name: "actor") { name: String @field(name: "FIRST_NAME") }
            type Query { film: Film }
            type Mutation {
              doSearch: [Searchable]
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilms"})
                @classified(source: Mutation, operation: ServiceCall, target: List, targetShape: Interface)
            }
            """),

        /*
         * R405: a root @service field returning a single-table discriminated interface
         * (QueryServiceTableInterfaceField, single cardinality). Unlike route (a) above, all
         * implementers share one @table @discriminate table, so the service hands back records of that
         * one table; the emitted fetcher collects their PKs and re-fetches by PK, routing each row off
         * the live discriminator via the TableInterfaceType TypeResolver. Verdict: source Query,
         * operation ServiceCall, target Single, target shape Interface (same wiring shape as route (a),
         * keeping requiresReFetch() false). Corpus-only: adds the QueryServiceTableInterfaceField leaf.
         */
        new Example("query-service-table-interface", """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") @classifiedType(as: TableInterfaceType) { title: String }
            type FilmItem implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Query {
              mediaService: MediaItem
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
                @classified(source: Query, operation: ServiceCall, target: Single, targetShape: Interface)
            }
            """),

        /*
         * R405: mutation analogue (MutationServiceTableInterfaceField), list cardinality. Same
         * single-table by-PK re-fetch as the query arm. Verdict: source Mutation, operation
         * ServiceCall, target List, target shape Interface. Corpus-only: adds the
         * MutationServiceTableInterfaceField leaf.
         */
        new Example("mutation-service-table-interface", """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") @classifiedType(as: TableInterfaceType) { title: String }
            type FilmItem implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Query { film: FilmItem }
            type Mutation {
              mediaSearch: [MediaItem]
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilms"})
                @classified(source: Mutation, operation: ServiceCall, target: List, targetShape: Interface)
            }
            """),

        new Example("table-interface", """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") @classifiedType(as: TableInterfaceType) { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Inventory @table(name: "inventory") {
              media: MediaItem @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Table)
            }
            type Query {
              inventory: Inventory
              topMedia: MediaItem @classified(source: Query, operation: Fetch, target: Single, targetShape: Table)
            }
            """),

        new Example("joined-table-interface", """
            interface Party @table(name: "party") @discriminate(on: "party_kind") @classifiedType(as: TableInterfaceType) {
              partyId: Int! @field(name: "party_id")
              displayName: String! @field(name: "display_name")
            }
            type Individual implements Party @table(name: "party_individual") @discriminator(value: "INDIVIDUAL") {
              partyId: Int! @field(name: "party_id")
              displayName: String! @reference(path: [{key: "party_individual_party_id_fkey"}]) @field(name: "display_name")
              birthDate: String @field(name: "birth_date")
            }
            type Company implements Party @table(name: "party_company") @discriminator(value: "COMPANY") {
              partyId: Int! @field(name: "party_id")
              displayName: String! @reference(path: [{key: "party_company_party_id_fkey"}]) @field(name: "display_name")
              orgNumber: String @field(name: "org_number")
            }
            type Query {
              allParties: [Party!]! @classified(source: Query, operation: Fetch, target: List, targetShape: Table)
            }
            """),

        new Example("relay-node", """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") { id: ID! title: String }
            type Query {
              node(id: ID!): Node @classified(source: Query, operation: NodeResolve, target: Single, targetShape: Interface)
              nodes(ids: [ID!]!): [Node] @classified(source: Query, operation: NodeResolve, target: List, targetShape: Interface)
              internalFilmNode(id: ID): Node @classified(source: Query, operation: NodeResolve, target: Single, targetShape: Interface)
            }
            """),

        /*
         * Slice-3 coverage sweep. The fixtures from here to the end of the list are the swept long
         * tail: corpus entries authored to bring every output-field and (non-failure) type leaf under
         * the corpus as single source of truth (VariantCoverageTest), tested but not necessarily
         * prose-featured. Each ports the SDL shape of the GraphitronSchemaBuilderTest case that used to
         * own the leaf, annotated with its dimensional verdict (the field model's
         * {@code source()}/{@code operation()}/{@code target()} triple) or its
         * @classifiedType verdict.
         */

        /*
         * Scalar @reference and @externalField on a @table parent: both are inline catalog-column
         * carriers (Child / Fetch / Column). `languageName` resolves a FK and projects the joined
         * column (ColumnReferenceField); `computedRating` inlines a developer-supplied jOOQ Field<X> into
         * the parent SELECT (ComputedField; its target shape stays Column).
         */
        new Example("reference-and-computed", """
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Column)
              computedRating: String
                @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
                @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Column)
            }
            type Query { film: Film }
            """),

        /*
         * @lookupKey without @splitQuery, on a child and on a root. The child `FilmActor.actors` stays
         * an inline correlated subquery keyed by the lookup args (LookupTableField, Child / Lookup /
         * Table); the root `Query.filmById` is a new query keyed by the lookup args (QueryLookupTableField,
         * Query / Lookup / Table). @lookupKey makes the operation Lookup; the batch-key shape is a slot.
         */
        new Example("lookup", """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type FilmActor @table(name: "film_actor") {
              actors(actor_id: [Int!]! @lookupKey): [Actor!]!
                @classified(source: OnlyChild, operation: Lookup, target: List, targetShape: Table)
            }
            type Query {
              filmActor: FilmActor
              filmById(film_id: [ID] @lookupKey): [Film!]!
                @classified(source: Query, operation: Lookup, target: List, targetShape: Table)
            }
            """),

        /*
         * @tableMethod (a developer-supplied table source FK-correlatable from the parent) on a child
         * and on a root. The child `Film.language` is inline-correlatable (TableMethodField, Child /
         * Fetch / Table; the generator's current per-parent query is the R288 defect, the corpus asserts
         * the correct verdict). The root `Query.filteredFilms` starts a new query
         * (QueryTableMethodTableField, Query / Fetch / Table).
         */
        new Example("table-method", """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
              title: String
              language: Language
                @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getLanguage")
                @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: Child, operation: Fetch, target: Single, targetShape: Table)
            }
            type Query {
              film: Film
              filteredFilms: [Film!]!
                @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getFilmWithContext", contextArguments: ["tenantId"])
                @classified(source: Query, operation: Fetch, target: List, targetShape: Table)
            }
            """),

        /*
         * @routine (R300): a table-valued read function backing a root list field. jOOQ models the
         * function as a catalog Table<R>, so the verdict is the same shape as a plain catalog read
         * (QueryRoutineTableField, Query / Fetch / List(Table)); only the FROM source differs (the
         * generated Routines convenience method, with IN params bound from GraphQL arguments). The
         * routine resolves against the sakila-db fixture catalog.
         */
        new Example("routine-table-valued-read", """
            type Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr") {
              organisasjonskode: Int
              rollekode: String
            }
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
                @classified(source: Query, operation: Fetch, target: List, targetShape: Table)
            }
            """),

        /*
         * @routine on Mutation (R451): the routine call IS the write and commits before the
         * follow-up query. The chain form (@routine plus at least one @reference hop) lands
         * MutationRoutineWriteField (Mutation / RoutineWrite / List(Table)): step 1 runs the
         * VOLATILE set-returning function inside the per-field transaction and captures hop 0's
         * key columns; step 2 re-reads the committed rows from the hop's table, so the response
         * always observes committed state. The routine resolves against the sakila-db fixture
         * catalog's rent_film write function.
         */
        new Example("routine-mutation-write", """
            type Rental @table(name: "rental") {
              rentalId: Int! @field(name: "rental_id")
            }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
                @classified(source: Mutation, operation: RoutineWrite, target: List, targetShape: Table)
            }
            """),

        /*
         * @table children under a jOOQ-TableRecord-backed parent, reached by @lookupKey and by
         * @tableMethod. The record handoff has already opened a new keyed scope, so both re-query (the
         * new-query is derived): `FilmDetails.language` is a RecordLookupTableField (its @lookupKey makes
         * the operation Lookup, target Table) and `FilmDetails.inventories` a RecordTableMethodField (Fetch,
         * Table). FilmDetails is record-bound as getFilm's jOOQ-TableRecord return type, which supplies
         * the FK source key for both.
         */
        new Example("record-method", """
            type Language @table(name: "language") { name: String }
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              language(language_id: ID! @lookupKey): Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: Child, operation: Lookup, target: Single, targetShape: Table, sourceShape: Record)
              inventories: [Inventory!]!
                @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getInventory")
                @classified(source: Child, operation: Fetch, target: List, targetShape: Table, sourceShape: Record)
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
              film: Film
              prodFilmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """),

        /*
         * A scalar @reference on a @table+@discriminate interface participant whose FK targets a
         * different table: ParticipantColumnReferenceField (Child / Fetch / Column). It gets its own
         * leaf so the interface fetcher's conditional LEFT JOIN wires the cross-table projection and the
         * per-field DataFetcher reads it back by alias.
         */
        new Example("participant-reference", """
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              contentId: Int! @field(name: "CONTENT_ID")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              contentId: Int! @field(name: "CONTENT_ID")
              rating: String @reference(path: [{key: "content_film_id_fkey"}]) @field(name: "RATING")
                @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Column)
            }
            type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
              contentId: Int! @field(name: "CONTENT_ID")
            }
            type Query { content: Content }
            """),

        /*
         * A custom @scalarType scalar classifies as ScalarType (the consumer's Coercing constant is
         * registered; Graphitron reflects its Java type). @classifiedType asserts the type verdict
         * directly; there is no field-side dimensional lesson.
         */
        new Example("scalar-type", """
            scalar Money @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.MONEY")
                @classifiedType(as: ScalarType)
            type Query { x: Money }
            """),

        /*
         * The Relay pagination wrapper triad, written structurally (a hand-written Connection / Edge /
         * PageInfo shape, the form the classifier promotes without the @asConnection transform, so the
         * types exist in source SDL to carry @classifiedType). ConnectionType / EdgeType / PageInfoType
         * are pagination wrappers, asserted directly; no field-side lesson.
         */
        new Example("connection", """
            type Film @table(name: "film") { id: ID }
            type FilmsConnection @classifiedType(as: ConnectionType) {
              edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo!
            }
            type FilmsEdge @classifiedType(as: EdgeType) { cursor: String! node: Film! }
            type PageInfo @classifiedType(as: PageInfoType) {
              hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String
            }
            type Query { films: FilmsConnection }
            """),

        /*
         * Input-type backing (the input-side type-verdict cluster, mirroring `result-backing` on the
         * output side). An input type acquires its leaf by reflection on the @service consumer's
         * parameter class: a plain Java class is PojoInputType, a Java record is JavaRecordInputType, a
         * jOOQ TableRecord is JooqTableRecordInputType. @classifiedType asserts each directly; input-field
         * classification stays out of scope (the enum truth table's game), so no @classified here.
         */
        new Example("input-backing", """
            input PojoBackedInput @classifiedType(as: PojoInputType) { id: ID }
            input JavaRecordBackedInput @classifiedType(as: JavaRecordInputType) { id: ID }
            input JooqTableRecordBackedInput @classifiedType(as: JooqTableRecordInputType) { id: ID }
            type Query {
              pojo(in: PojoBackedInput): String
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "consumeDummyRecord"})
              javaRecord(in: JavaRecordBackedInput): String
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "consumeTestRecordDto"})
              jooqRecord(in: JooqTableRecordBackedInput): String
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "consumeFilmRecord"})
            }
            """),

        /*
         * A @table+@node type classifies as NodeType (the Relay-identified table, key columns resolved).
         * @classifiedType asserts it directly; `id` carries the @nodeId encode but is the type's own key,
         * not a separate dimensional lesson.
         */
        new Example("node-type", """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"])
                @classifiedType(as: NodeType) {
              id: ID! @nodeId
            }
            type Query { film: Film }
            """),

        /*
         * The @service ID-carrier: a mutation whose @service producer returns rows and whose payload
         * exposes an [ID] @nodeId(typeName:) data field. That data field encodes node ids straight off
         * the producer's in-memory records (no re-fetch), an inline catalog-column carrier
         * (SingleRecordIdField, Child / Fetch / Column). The @nodeId(typeName: "Film") grounds the encode
         * on Film's @table; the errors field is the payload's error channel.
         */
        new Example("node-id-carrier", """
            interface Node { id: ID! }
            type Film implements Node @node @table(name: "film") { id: ID! @nodeId  title: String }
            type FilmErr @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
              path: [String!]!
              message: String!
            }
            union DeleteFilmsError = FilmErr
            type FilmIdsPayload {
              filmIds: [ID] @nodeId(typeName: "Film") @classified(source: OnlyChild, operation: Fetch, target: List, targetShape: Column, sourceShape: Record)
              errors: [DeleteFilmsError]
            }
            type Query { x: String }
            type Mutation {
              deleteFilms: FilmIdsPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """),

        /*
         * R329 — the @service record-composite carrier: a mutation whose @service producer returns a
         * list of a consumer-authored composite (one FilmRecord plus a List<ActorRecord>). The payload
         * is a two-level carrier: a data field that is a list of an intermediate result type
         * (CreateFilmsResult, reflection-bound to the composite class → JavaRecordType), whose
         * @field-mapped @table children read off the composite through the record-backed accessor path
         * (RecordTableField). The data field itself is a source-passthrough projection of the producer's
         * in-memory composite list — no re-fetch, no DataLoader (RecordCompositeField, source Child(Record),
         * operation Fetch, target List(Record)). The errors field rides the Outcome WrapperArm. The payload
         * no longer dangles: it classifies as JavaRecordType naming the per-element composite class, with
         * the arrival cardinality on the data field (the element-naming convention the bulk @table carrier
         * also uses).
         */
        new Example("service-record-composite-carrier", """
            type Film @table(name: "film") @classifiedType(as: TableType) {
              title: String @classified(source: Child, operation: Fetch, target: Single, targetShape: Column)
            }
            type Actor @table(name: "actor") @classifiedType(as: TableType) {
              firstName: String @field(name: "first_name") @classified(source: Child, operation: Fetch, target: Single, targetShape: Column)
            }
            type CreateFilmsError @error(handlers: [{handler: DATABASE}]) {
              path: [String!]!
              message: String!
            }
            union CreateFilmsErr = CreateFilmsError
            type CreateFilmsResult @classifiedType(as: JavaRecordType) {
              film: Film! @field(name: "filmRecord")
                @classified(source: Child, operation: Fetch, target: Single, targetShape: Table, sourceShape: Record)
              actors: [Actor] @field(name: "actorRecords")
                @classified(source: Child, operation: Fetch, target: List, targetShape: Table, sourceShape: Record)
            }
            type CreateFilmsPayload @classifiedType(as: JavaRecordType) {
              results: [CreateFilmsResult]
                @classified(source: OnlyChild, operation: Fetch, target: List, targetShape: Record, sourceShape: Record)
              errors: [CreateFilmsErr]
            }
            type Query { x: String }
            type Mutation {
              createFilms: CreateFilmsPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmsWithActors"})
                @classified(source: Mutation, operation: ServiceCall, target: Single, targetShape: Record)
            }
            """),

        /*
         * DML payload-carrier mutations (UPDATE and its bulk sibling, plus the bulk INSERT carrier).
         * Each returns a plain object wrapping one @table data field and exposes the affected rows as a
         * record, so the mutation field is source Mutation, target Record, with the write verb as the
         * operation: Update for MutationUpdatePayloadField / MutationBulkUpdatePayloadField, and Insert for
         * the bulk INSERT carrier (MutationBulkDmlRecordField, whose DmlKind reads INSERT). Distinct
         * payload types keep the per-kind carrier scans isolated. The DELETE payload siblings
         * (MutationDeletePayloadField / MutationBulkDeletePayloadField) are not corpus-covered: post-R287
         * their only admissible data field is an ID-element (a @table-element projection off a deleted
         * row is impossible), which needs the synthesised __NODE_TYPE_ID metadata absent from the corpus
         * catalog; they are covered by MutationDmlNodeIdClassificationTest under the nodeidfixture and
         * carried in VariantCoverageTest's NO_CASE_REQUIRED.
         */
        new Example("dml-payloads", """
            type Film @table(name: "film") { title: String }
            type FilmInsertBulkPayload { films: [Film!] }
            type FilmUpdatePayload { film: Film }
            type FilmUpdateBulkPayload { films: [Film!] }
            input FilmCreateInput @table(name: "film") { title: String }
            input FilmUpdateInput @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type Query { x: String }
            type Mutation {
              createFilmsPayload(in: [FilmCreateInput!]!): FilmInsertBulkPayload
                @mutation(typeName: INSERT)
                @classified(source: Mutation, operation: Insert, target: Single, targetShape: Record)
              updateFilmPayload(in: FilmUpdateInput!): FilmUpdatePayload
                @mutation(typeName: UPDATE)
                @classified(source: Mutation, operation: Update, target: Single, targetShape: Record)
              updateFilmsPayload(in: [FilmUpdateInput!]!): FilmUpdateBulkPayload
                @mutation(typeName: UPDATE)
                @classified(source: Mutation, operation: Update, target: Single, targetShape: Record)
            }
            """),

        /*
         * DML side: an INSERT that writes then projects the inserted row. The write produces the row,
         * then a follow-up SELECT projects the @table return; the verdict is source Mutation, operation
         * Insert, target Table, and the follow-up re-fetch is derived (not a tuple axis). Doc example:
         * the projection query pulls in the FilmInput argument's input-object closure (renderer hardening
         * item 3), so the rendered excerpt shows the input the mutation consumes rather than dangling.
         */
        new Example("dml", """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") @classifiedType(as: TableInputType) { title: String }
            type Query { x: String }
            type Mutation {
              createFilm(in: FilmInput!): Film
                @mutation(typeName: INSERT)
                @classified(source: Mutation, operation: Insert, target: Single, targetShape: Table)
            }
            """,
            "mutation { createFilm { title } }"),

        /*
         * The remaining root mutation forms (INSERT is the `dml` example above). UPDATE is a DML write
         * that projects the affected @table row back, so it is Mutation / Update / Table
         * (MutationUpdateTableField; the projection re-fetch is derived). DELETE (R287) cannot project a
         * @table (the row is gone; RETURNING carries only the PK), so it tops out at an encoded-ID return:
         * Mutation / Delete / Column (MutationDeleteTableField with an Encoded* return-expression arm).
         * DELETE admits two ways onto the same verdict: a PK-covering filter input (`deleteFilm`) or an
         * explicit `multiRow: true` broadcast over a non-PK filter (`deleteFilmsBroadcast`). An @service
         * mutation re-queries the catalog for its @table return (MutationServiceTableField, Mutation /
         * ServiceCall / Table) or materializes a non-table record-backed type (MutationServiceRecordField,
         * Mutation / ServiceCall / Record). A DML payload carrier (a plain object wrapping one @table
         * data field) exposes the RETURNING rows as a record, so the carrier itself is Mutation / Insert /
         * Record (MutationDmlRecordField, DmlKind INSERT), the follow-up projection being the data field's
         * own concern (a Child / Fetch / Table RecordTableField on the payload). Corpus-only: these
         * remaining root forms are additional leaves on the principles the `dml` and `dml-payloads`
         * examples teach (their input objects render fine since hardening item 3).
         */
        new Example("mutation-roots", """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
            type FilmDetails { title: String }
            type FilmPayload { film: Film @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Table, sourceShape: Record) }
            input FilmKeyInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            input FilmUpdateInput @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            input FilmTitleInput @table(name: "film") { title: String @field(name: "title") }
            input FilmCreateInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
              updateFilm(in: FilmUpdateInput!): Film
                @mutation(typeName: UPDATE)
                @classified(source: Mutation, operation: Update, target: Single, targetShape: Table)
              deleteFilm(in: FilmKeyInput!): ID
                @mutation(typeName: DELETE)
                @classified(source: Mutation, operation: Delete, target: Single, targetShape: Column)
              deleteFilmsBroadcast(in: FilmTitleInput!): ID
                @mutation(typeName: DELETE, multiRow: true)
                @classified(source: Mutation, operation: Delete, target: Single, targetShape: Column)
              externalMutation: Film
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
                @classified(source: Mutation, operation: ServiceCall, target: Single, targetShape: Table)
              externalRecord: FilmDetails
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runDetails"})
                @classified(source: Mutation, operation: ServiceCall, target: Single, targetShape: Record)
              createFilmPayload(in: FilmCreateInput!): FilmPayload
                @mutation(typeName: INSERT)
                @classified(source: Mutation, operation: Insert, target: Single, targetShape: Record)
            }
            """),

        /*
         * R463 arrival-fold edge cases. The fixtures below pin the ancestor-product arrival fold's
         * corners with hand-asserted arrivals (source OnlyChild = One, Child = Many), each isolating one
         * rule so a regression in the fold surfaces on the specific case rather than diffusely. They are
         * corpus-only (no doc query); the dimensional verdict is the whole lesson.
         */

        /*
         * Deep single chain (One). Query.film (single) -> Film -> Film.language (single @reference) ->
         * Language: no list wrapper and no fan-in anywhere on the chain, so arrival stays the One
         * identity all the way down. Both the intermediate object edge (Film.language) and the terminal
         * column (Language.name) fold to OnlyChild.
         */
        new Example("arrival-deep-single-chain", """
            type Language @table(name: "language") {
              name: String @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Column)
            }
            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Table)
            }
            type Query {
              film: Film @classified(source: Query, operation: Fetch, target: Single, targetShape: Table)
            }
            """),

        /*
         * List ancestor (Many). A single list wrapper anywhere above a type absorbs the whole subtree to
         * Many: Query.films is a list, so Film arrives Many, and its single @reference child Language
         * inherits Many through the tensor (Many (x) One = Many). Both Film.language and Language.name are
         * Child even though the Film -> Language edge is itself single.
         */
        new Example("arrival-list-ancestor", """
            type Language @table(name: "language") {
              name: String @classified(source: Child, operation: Fetch, target: Single, targetShape: Column)
            }
            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: Child, operation: Fetch, target: Single, targetShape: Table)
            }
            type Query {
              films: [Film!]! @classified(source: Query, operation: Fetch, target: List, targetShape: Table)
            }
            """),

        /*
         * Fan-in of two single edges (Many). Film reaches Language over both of its FKs (language_id and
         * original_language_id); each edge is single, but two coordinates co-materialize Language
         * instances in one request, so the multi-edge rule folds Language to Many with no fixed point.
         * The Film -> Language edges are themselves OnlyChild (Film arrives One from the single root),
         * demonstrating that fan-in is a property of the reached type, not of the reaching edge.
         */
        new Example("arrival-fan-in", """
            type Language @table(name: "language") {
              name: String @classified(source: Child, operation: Fetch, target: Single, targetShape: Column)
            }
            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Table)
              originalLanguage: Language @reference(path: [{key: "film_original_language_id_fkey"}])
                @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Table)
            }
            type Query {
              film: Film @classified(source: Query, operation: Fetch, target: Single, targetShape: Table)
            }
            """),

        /*
         * Recursion (Many). Store and Staff reach each other over the store <-> staff FK cycle
         * (store.manager_staff_id, staff.store_id). A reachable cycle implies a second reaching edge
         * (Store is reached by Query.store and by Staff.store), so the multi-edge rule folds both to Many
         * without a fixed point; every field on both types is Child.
         */
        new Example("arrival-recursion", """
            type Staff @table(name: "staff") {
              store: Store @reference(path: [{key: "staff_store_id_fkey"}])
                @classified(source: Child, operation: Fetch, target: Single, targetShape: Table)
            }
            type Store @table(name: "store") {
              manager: Staff @reference(path: [{key: "store_manager_staff_id_fkey"}])
                @classified(source: Child, operation: Fetch, target: Single, targetShape: Table)
            }
            type Query {
              store: Store @classified(source: Query, operation: Fetch, target: Single, targetShape: Table)
            }
            """),

        /*
         * A @node-seeded type (Many). Film carries @node, so node/entity lookups arrive batched
         * regardless of how few field edges reach it: even reached by a single root query, its arrival is
         * the absorbing Many, so Film.title is Child. This is the seed arm of the fold, distinct from the
         * multi-edge and list-ancestor arms above.
         */
        new Example("arrival-node-seeded", """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"])
                @classifiedType(as: NodeType) {
              id: ID! @nodeId
              title: String @classified(source: Child, operation: Fetch, target: Single, targetShape: Column)
            }
            type Query {
              film: Film @classified(source: Query, operation: Fetch, target: Single, targetShape: Table)
            }
            """),

        /*
         * Connection ancestor (Many). The many-ness of a Relay connection's element arrives through the
         * connection type's edges/nodes list edges, not through the (single) connection field: Query.films
         * returns a single FilmsConnection, but Film is reached through the FilmsEdge.node / nodes list, so
         * Film arrives Many and Film.title is Child. The connection-internal fields stay generator-only
         * (no @classified); the lesson is the arrival the edges list transmits.
         */
        new Example("arrival-connection-ancestor", """
            type Film @table(name: "film") {
              title: String @classified(source: Child, operation: Fetch, target: Single, targetShape: Column)
            }
            type FilmsConnection {
              edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo!
            }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo {
              hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String
            }
            type Query { films: FilmsConnection }
            """),

        /*
         * Single mutation payload carrier (One). A single-carrier @service payload arrives once: the
         * payload type is reached by exactly one single mutation field, so it folds to One and its @table
         * data field is OnlyChild(Record) (a re-fetch off the produced record). Complements the bulk /
         * fan-in Record carriers elsewhere in the corpus, which stay Child.
         */
        new Example("arrival-single-payload-carrier", """
            type Film @table(name: "film") { title: String }
            type FilmPayload {
              film: Film
                @classified(source: OnlyChild, operation: Fetch, target: Single, targetShape: Table, sourceShape: Record)
            }
            type Query { x: String }
            type Mutation {
              runFilm: FilmPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
            }
            """));

    /** The corpus entries, in declaration order. */
    public static List<Example> examples() {
        return EXAMPLES;
    }

    /** The corpus entries that carry a documentation projection query, in declaration order. */
    public static List<Example> docExamples() {
        return EXAMPLES.stream().filter(e -> e.query() != null).toList();
    }

    /**
     * The set of sealed {@code GraphitronField} / {@code GraphitronType} leaves the corpus demonstrates
     * classification for, by classifying every fixture and collecting the leaf each {@code @classified}
     * / {@code @classifiedType} coordinate landed on. {@code VariantCoverageTest} unions this with the
     * legacy enum cases, so an enum row may be retired once its leaf is covered here without the
     * coverage meta-test regressing.
     */
    public static Set<Class<?>> coveredLeaves() {
        var leaves = new HashSet<Class<?>>();
        for (Example example : EXAMPLES) {
            var result = ClassifiedHarness.classify(example.sdl());
            for (var fc : result.fields()) {
                leaves.add(fc.leaf());
            }
            for (var tc : result.types()) {
                if (tc.leaf() != null) {
                    leaves.add(tc.leaf());
                }
            }
        }
        return leaves;
    }
}
