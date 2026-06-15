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
 * <p>Each {@code @classified} coordinate asserts the three-axis {@code (carrier, intent, mapping)}
 * verdict R299 migrated the corpus onto (from R281's original {@code (producer, mapping)}); between
 * them the fixtures exercise every {@link Carrier} and {@link Mapping} value and every populated
 * {@link Intent}, with the modeled-but-unpopulated intents tracked as known gaps in
 * {@link ClassifiedDslTest}. The set grows example by example as the {@code code-generation-triggers}
 * documentation pulls each one in (see {@link #coveredLeaves()}).
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
              film: Film @classified(carrier: Query, intent: Fetch, mapping: Table)
              films: [Film!]! @asConnection @classified(carrier: Query, intent: Fetch, mapping: TableConnection)
            }

            type Film @table(name: "film") @classifiedType(as: TableType) {
              title: String @classified(carrier: Source, intent: Fetch, mapping: Column)
            }
            """,
            "{ film { title } }"),

        /*
         * Enum-typed scalar: a field whose GraphQL return type is an enum still resolves to a real DB
         * column on the @table parent, so it classifies exactly like any other inline scalar (carrier
         * Source, intent Fetch, mapping Column, ColumnField). The enum-ness lives in the GraphQL-to-Java
         * mapping, not the classification. Corpus-only: it lands on the already-taught Source / Fetch /
         * Column coordinate; this pins the "enum returns are columns" edge that the retired
         * ENUM_RETURN_TYPE enum row asserted.
         */
        new Example("enum-column", """
            enum Rating @classifiedType(as: EnumType) { G PG PG13 R NC17 }
            type Film @table(name: "film") {
              rating: Rating @classified(carrier: Source, intent: Fetch, mapping: Column)
            }
            type Query { film: Film }
            """),

        /*
         * Child table fields over the same city -> country FK. Both return the same @table type and hold
         * mapping = Table, intent = Fetch. They differ only on the derived new-query layer (not a tuple
         * axis): `country` inlines as a correlated subquery folded into city's SELECT; `@splitQuery`
         * flips `countrySplit` to a new keyed query. The verdict is identical (Source / Fetch / Table);
         * the split is a derived consequence of the @splitQuery slot.
         */
        new Example("child-table", """
            type Country @table(name: "country") @classifiedType(as: TableType) {
              name: String @field(name: "country") @classified(carrier: Source, intent: Fetch, mapping: Column)
            }

            type City @table(name: "city") @classifiedType(as: TableType) {
              country: Country @classified(carrier: Source, intent: Fetch, mapping: Table)
              countrySplit: Country @splitQuery @classified(carrier: Source, intent: Fetch, mapping: Table)
            }

            type Query {
              city: City @classified(carrier: Query, intent: Fetch, mapping: Table)
            }
            """,
            "{ city { country { name } countrySplit { name } } }"),

        /*
         * Keyed split lookup: a list child whose @lookupKey argument establishes a positional
         * input-list <-> output-list correspondence, fetched by a @splitQuery keyed batch
         * (SplitLookupTableField). The @lookupKey makes its intent Lookup; it lands on participant @table
         * rows (mapping Table); the new-query batch shape is derived, not a tuple axis. Corpus-only: it
         * is another Source / Lookup / Table leaf.
         */
        new Example("split-lookup", """
            type Customer @table(name: "customer") { firstName: String @field(name: "FIRST_NAME") }
            type Store @table(name: "store") {
              customers(customer_id: ID! @lookupKey): [Customer!]! @splitQuery
                @classified(carrier: Source, intent: Lookup, mapping: Table)
            }
            type Query { store: Store }
            """),

        /*
         * Mapping minimal pair: Column vs Field. A scalar under the @table parent Film maps to Column
         * (`title` is a real DB column); a scalar under a record-backed parent maps to Field
         * (`FilmStats.count` is a POJO property, the record having no @table). The non-table object
         * field `FilmDetails.stats` is the object flavor of Field (RecordField). All three are inline
         * Fetch off the Source carrier; only the parent's table-ness moves the mapping axis. The two
         * parents are record-bound by being service producers' return types (`makeFilmDetailsRecord` ->
         * FilmDetailsRecord, whose sole component is `stats`; `makeFilmStatsRecord` -> FilmStatsRecord,
         * whose sole component is `count`).
         */
        new Example("mapping", """
            type FilmStats {
              count: Int @classified(carrier: Source, intent: Fetch, mapping: Field, sourceShape: Record)
            }

            type FilmDetails {
              stats: FilmStats @classified(carrier: Source, intent: Fetch, mapping: Field, sourceShape: Record)
            }

            type Film @table(name: "film") {
              title: String @classified(carrier: Source, intent: Fetch, mapping: Column)
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
         * back into the parent SELECT. Both hold the same verdict (Source / Fetch / Table): the new-query
         * is a derived consequence of the record-handoff slot, not a distinct intent. FilmDetails is
         * record-bound as makeDummyRecord's return type; the explicit @reference disambiguates film's two
         * FKs to language.
         */
        new Example("record-table", """
            type Language @table(name: "language") { name: String }

            type FilmDetails {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(carrier: Source, intent: Fetch, mapping: Table, sourceShape: Record)
            }

            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(carrier: Source, intent: Fetch, mapping: Table)
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
              title: String @classified(carrier: Source, intent: Fetch, mapping: Field, sourceShape: Record)
            }

            type Film @table(name: "film") {
              details: FilmDetails
              rating: String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
                @classified(carrier: Source, intent: QueryService, mapping: Record)
              language: Language
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
                @classified(carrier: Source, intent: QueryService, mapping: Table)
            }

            type Query {
              film: Film
              prodFilmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDetailsProps"})
              externalFilm: Film
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
                @classified(carrier: Query, intent: QueryService, mapping: Table)
            }
            """),

        /*
         * Root @service into a record-backed type: a root query field whose @service resolver returns a non-table
         * record-backed type (QueryServiceRecordField). The service call produces the record, which is then
         * materialized rather than projected from the catalog, so it is carrier Query, intent
         * QueryService, mapping Record, the root analog of the ServiceRecordField child field above
         * (Film.rating). Corpus-only: it lands on the already-taught QueryService / Record coordinate. The
         * @service producer's reflected return type binds the payload here.
         */
        new Example("query-service-record", """
            type FilmDetails { title: String }
            type Query {
              filmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getDetails"})
                @classified(carrier: Query, intent: QueryService, mapping: Record)
            }
            """),

        /*
         * Result-type backing (a type-verdict cluster). A non-@table result type acquires its backing
         * class by reflection on the @service producer's return type (R276), never from a directive, and
         * the GraphitronType leaf reflects what that class is: a plain Java class is PojoResultType.Backed
         * (`as: Backed`), a Java record is JavaRecordType, a jOOQ TableRecord is JooqTableRecordType.
         * Corpus-only: the @classifiedType axis is asserted directly; there is no field-side dimensional
         * lesson here. The `name` field on the Java-record-backed type (a record component of TestRecordDto)
         * doubles as the fixture's required field coordinate, classifying Source / Fetch / Field off the
         * backing.
         */
        new Example("result-backing", """
            type PojoBacked @classifiedType(as: Backed) { id: ID }
            type JavaRecordBacked @classifiedType(as: JavaRecordType) {
              name: String @classified(carrier: Source, intent: Fetch, mapping: Field, sourceShape: Record)
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
         * (Source / Fetch / Field). Corpus-only: it lands on the already-taught Source / Fetch / Field
         * coordinate, and the @error type itself is not a documentation-query selection shape.
         */
        new Example("error-field", """
            type MyError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
              path: [String!]! @classified(carrier: Source, intent: Fetch, mapping: Field, sourceShape: Record)
              message: String! @classified(carrier: Source, intent: Fetch, mapping: Field, sourceShape: Record)
            }
            type Query { x: String }
            """),

        /*
         * @error type-verdict admission nuance. An @error type classifies as ErrorType (the GraphQL
         * type whose @error contract carries the handler set). A field beyond the mandatory
         * path/message (`severity`) does not break the verdict: the per-handler accessor check fires
         * on the carrier, not the @error type, so the type stays ErrorType. Corpus-only: the
         * @classifiedType axis is asserted directly; `path` doubles as the fixture's required field
         * coordinate (Source / Fetch / Field). (The @error + @record silently-ignored verdict, the
         * D1 precedence rule, is covered by RecordDirectiveIgnoredWarningTest, the one place applied
         * @record remains; see R307.)
         */
        new Example("error-type", """
            enum Severity { LOW HIGH }
            type ExtraFieldError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}])
                @classifiedType(as: ErrorType) {
              path: [String!]! @classified(carrier: Source, intent: Fetch, mapping: Field, sourceShape: Record)
              message: String!
              severity: Severity!
            }
            type Query { x: String }
            """),

        /*
         * Nesting: a plain object child (no @table, no @record) on a @table parent inlines into the
         * parent's projection, inheriting the parent's table context (NestingField). Its scalars resolve
         * against the parent table, so the field maps to Table and its intent is Nesting (a distinct
         * structural operation, asserted, not derived from an absent join-path). Corpus-only; this adds
         * the NestingField leaf and the Nesting intent to the corpus's covered set.
         */
        new Example("nesting", """
            type FilmDetails @classifiedType(as: NestingType) { title: String description: String }
            type Film @table(name: "film") {
              details: FilmDetails @classified(carrier: Source, intent: Nesting, mapping: Table)
            }
            type Query { film: Film }
            """),

        // The former "constructor" example (a record-backed child type under a @table parent, Film.details
        // building the record-backed FilmDetails from the parent's row) left the classified corpus when
        // R290 dissolved ConstructorField as wrong-by-design: that table-and-service clash is now a
        // build-time rejection, asserted at the validator tier by ConstructorFieldValidationTest rather
        // than as a clean classification here. Mapping.Record stays exercised by ErrorsField,
        // ServiceRecordField, and the DML record carriers.

        /*
         * Polymorphic children and roots are catalog-bound over their participant tables: the mapping is
         * Table (the projection lands on participant @table rows), with the participant set carried as a
         * derived slot rather than a distinct mapping value, and the intent is Fetch. A plain-interface or
         * union child (InterfaceField / UnionField) and any polymorphic root (QueryInterfaceField /
         * QueryUnionField) share that Fetch / Table verdict; the new-query they open is derived, not an
         * axis. The exception's verdict is the same: a @table+@discriminate interface child
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
              address: Named @classified(carrier: Source, intent: Fetch, mapping: Table)
            }
            type Query {
              customer: Customer
              anyNamed: Named @classified(carrier: Query, intent: Fetch, mapping: Table)
            }
            """,
            "{ customer { address { name } } }"),

        new Example("union", """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
            union FilmOrActor @classifiedType(as: UnionType) = Film | Actor
            type FilmActor @table(name: "film_actor") {
              related: FilmOrActor @classified(carrier: Source, intent: Fetch, mapping: Table)
            }
            type Query {
              filmActor: FilmActor
              search: FilmOrActor @classified(carrier: Query, intent: Fetch, mapping: Table)
            }
            """,
            "{ filmActor { related { ... on Film { title } ... on Actor { firstName } } } }"),

        new Example("table-interface", """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") @classifiedType(as: TableInterfaceType) { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Inventory @table(name: "inventory") {
              media: MediaItem @classified(carrier: Source, intent: Fetch, mapping: Table)
            }
            type Query {
              inventory: Inventory
              topMedia: MediaItem @classified(carrier: Query, intent: Fetch, mapping: Table)
            }
            """),

        new Example("relay-node", """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") { id: ID! title: String }
            type Query {
              node(id: ID!): Node @classified(carrier: Query, intent: NodeResolve, mapping: Table)
              nodes(ids: [ID!]!): [Node] @classified(carrier: Query, intent: NodeResolve, mapping: Table)
              internalFilmNode(id: ID): Node @classified(carrier: Query, intent: NodeResolve, mapping: Table)
            }
            """),

        /*
         * Slice-3 coverage sweep. The fixtures from here to the end of the list are the swept long
         * tail: corpus entries authored to bring every output-field and (non-failure) type leaf under
         * the corpus as single source of truth (VariantCoverageTest), tested but not necessarily
         * prose-featured. Each ports the SDL shape of the GraphitronSchemaBuilderTest case that used to
         * own the leaf, annotated with its dimensional verdict (the field model's
         * {@code carrier()}/{@code intent()}/{@code mapping()} tuple) or its
         * @classifiedType verdict.
         */

        /*
         * Scalar @reference and @externalField on a @table parent: both are inline catalog-column
         * carriers (Source / Fetch / Column). `languageName` resolves a FK and projects the joined
         * column (ColumnReferenceField); `computedRating` inlines a developer-supplied jOOQ Field<X> into
         * the parent SELECT (ComputedField; its mapping stays Column under R299, the refined model
         * reclassifies it to a domain Field/Record in R290).
         */
        new Example("reference-and-computed", """
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
                @classified(carrier: Source, intent: Fetch, mapping: Column)
              computedRating: String
                @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
                @classified(carrier: Source, intent: Fetch, mapping: Column)
            }
            type Query { film: Film }
            """),

        /*
         * @lookupKey without @splitQuery, on a child and on a root. The child `FilmActor.actors` stays
         * an inline correlated subquery keyed by the lookup args (LookupTableField, Source / Lookup /
         * Table); the root `Query.filmById` is a new query keyed by the lookup args (QueryLookupTableField,
         * Query / Lookup / Table). @lookupKey makes the intent Lookup; the batch-key shape is a slot.
         */
        new Example("lookup", """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type FilmActor @table(name: "film_actor") {
              actors(actor_id: [Int!]! @lookupKey): [Actor!]!
                @classified(carrier: Source, intent: Lookup, mapping: Table)
            }
            type Query {
              filmActor: FilmActor
              filmById(film_id: [ID] @lookupKey): [Film!]!
                @classified(carrier: Query, intent: Lookup, mapping: Table)
            }
            """),

        /*
         * @tableMethod (a developer-supplied table source FK-correlatable from the parent) on a child
         * and on a root. The child `Film.language` is inline-correlatable (TableMethodField, Source /
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
                @classified(carrier: Source, intent: Fetch, mapping: Table)
            }
            type Query {
              film: Film
              filteredFilms: [Film!]!
                @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getFilmWithContext", contextArguments: ["tenantId"])
                @classified(carrier: Query, intent: Fetch, mapping: Table)
            }
            """),

        /*
         * @table children under a jOOQ-TableRecord-backed parent, reached by @lookupKey and by
         * @tableMethod. The record handoff has already opened a new keyed scope, so both re-query (the
         * new-query is derived): `FilmDetails.language` is a RecordLookupTableField (its @lookupKey makes
         * the intent Lookup, mapping Table) and `FilmDetails.inventories` a RecordTableMethodField (Fetch,
         * Table). FilmDetails is record-bound as getFilm's jOOQ-TableRecord return type, which supplies
         * the FK source key for both.
         */
        new Example("record-method", """
            type Language @table(name: "language") { name: String }
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              language(language_id: ID! @lookupKey): Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(carrier: Source, intent: Lookup, mapping: Table, sourceShape: Record)
              inventories: [Inventory!]!
                @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getInventory")
                @classified(carrier: Source, intent: Fetch, mapping: Table, sourceShape: Record)
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
         * different table: ParticipantColumnReferenceField (Source / Fetch / Column). It gets its own
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
                @classified(carrier: Source, intent: Fetch, mapping: Column)
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
         * (SingleRecordIdField, Source / Fetch / Column). The @nodeId(typeName: "Film") grounds the encode
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
              filmIds: [ID] @nodeId(typeName: "Film") @classified(carrier: Source, intent: Fetch, mapping: Column, sourceShape: Record)
              errors: [DeleteFilmsError]
            }
            type Query { x: String }
            type Mutation {
              deleteFilms: FilmIdsPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """),

        /*
         * DML payload-carrier mutations (UPDATE and its bulk sibling, plus the bulk INSERT carrier).
         * Each returns a plain object wrapping one @table data field and exposes the affected rows as a
         * record, so the mutation field is carrier Mutation, mapping Record, with the write verb as the
         * intent: UPDATE for MutationUpdatePayloadField / MutationBulkUpdatePayloadField, and Insert for
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
                @classified(carrier: Mutation, intent: Insert, mapping: Record)
              updateFilmPayload(in: FilmUpdateInput!): FilmUpdatePayload
                @mutation(typeName: UPDATE)
                @classified(carrier: Mutation, intent: Update, mapping: Record)
              updateFilmsPayload(in: [FilmUpdateInput!]!): FilmUpdateBulkPayload
                @mutation(typeName: UPDATE)
                @classified(carrier: Mutation, intent: Update, mapping: Record)
            }
            """),

        /*
         * DML side: an INSERT that writes then projects the inserted row. The write produces the row,
         * then a follow-up SELECT projects the @table return; the verdict is carrier Mutation, intent
         * Insert, mapping Table, and the follow-up re-fetch is derived (not a tuple axis). Doc example:
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
                @classified(carrier: Mutation, intent: Insert, mapping: Table)
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
         * MutationService / Table) or materializes a non-table record-backed type (MutationServiceRecordField,
         * Mutation / MutationService / Record). A DML payload carrier (a plain object wrapping one @table
         * data field) exposes the RETURNING rows as a record, so the carrier itself is Mutation / Insert /
         * Record (MutationDmlRecordField, DmlKind INSERT), the follow-up projection being the data field's
         * own concern (a Source / Fetch / Table SingleRecordTableField on the payload). Corpus-only: these
         * remaining root forms are additional leaves on the principles the `dml` and `dml-payloads`
         * examples teach (their input objects render fine since hardening item 3).
         */
        new Example("mutation-roots", """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
            type FilmDetails { title: String }
            type FilmPayload { film: Film @classified(carrier: Source, intent: Fetch, mapping: Table, sourceShape: Record) }
            input FilmKeyInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            input FilmUpdateInput @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            input FilmTitleInput @table(name: "film") { title: String @field(name: "title") }
            input FilmCreateInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
              updateFilm(in: FilmUpdateInput!): Film
                @mutation(typeName: UPDATE)
                @classified(carrier: Mutation, intent: Update, mapping: Table)
              deleteFilm(in: FilmKeyInput!): ID
                @mutation(typeName: DELETE)
                @classified(carrier: Mutation, intent: Delete, mapping: Column)
              deleteFilmsBroadcast(in: FilmTitleInput!): ID
                @mutation(typeName: DELETE, multiRow: true)
                @classified(carrier: Mutation, intent: Delete, mapping: Column)
              externalMutation: Film
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
                @classified(carrier: Mutation, intent: MutationService, mapping: Table)
              externalRecord: FilmDetails
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runDetails"})
                @classified(carrier: Mutation, intent: MutationService, mapping: Record)
              createFilmPayload(in: FilmCreateInput!): FilmPayload
                @mutation(typeName: INSERT)
                @classified(carrier: Mutation, intent: Insert, mapping: Record)
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
