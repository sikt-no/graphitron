package no.sikt.graphitron.rewrite.classifieddsl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The spec-by-example corpus: the annotated fixture schemas that, between them, demonstrate the
 * classifier's dimensional verdicts. It is the single source of truth shared by everything that reads
 * the corpus, the DSL assertions ({@link ClassifiedDslTest}), the leaf-coverage bridge
 * ({@code VariantCoverageTest}), and the query-as-view documentation renderer.
 *
 * <p>Each {@code @classified} coordinate asserts the {@code (source, operations, target)}
 * verdict, the operation axis as the coordinate's member-row arm tokens; between them the
 * fixtures exercise every {@link no.sikt.graphitron.rewrite.model.Source} wrapper arm, every
 * {@link no.sikt.graphitron.rewrite.model.Target} wrapper and {@code TargetShape} arm, and every
 * populated {@link no.sikt.graphitron.rewrite.model.OperationMember} leaf arm, with the
 * modeled-but-unpopulated arms tracked as known gaps in {@code ExemptionRegistry}. The set grows
 * example by example as the {@code code-generation-triggers} documentation pulls each one in
 * (see {@link #coveredLeaves()}).
 *
 * <p>Each coordinate whose example produces a launcher command row also carries a
 * {@code @commits(source:, result:)} declaration, agreement-checked against the produced row;
 * between them those declarations reach every {@code LaunchSource} and {@code ResultShape} arm
 * (the launcher-commitment obligation in {@code ExemptionRegistry}).
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
              film: Film @classified(source: Query, operations: [Select], target: Single, targetShape: Table)
                @commits(source: AnchorTable, result: SingleRecord)
              films: [Film!]! @asConnection @classified(source: Query, operations: [OrderBy, Paginate, Select], target: Single, targetShape: Connection)
                @commits(source: AnchorTable, result: Connection)
            }

            type Film @table(name: "film") @classifiedType(as: TableType) {
              title: String @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
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
         * column on the @table parent, so it classifies exactly like any other inline scalar. The
         * enum-ness lives in the GraphQL-to-Java conversion, not the classification. It lands on the
         * already-taught Child / Select / Column coordinate, pinning the "enum returns are columns"
         * edge, which is what the page's worked example shows.
         */
        new Example("enum-column", """
            enum Rating @classifiedType(as: EnumType) { G PG PG13 R NC17 }
            type Film @table(name: "film") {
              rating: Rating @classified(source: OnlyChild, operations: [Select], target: Single, targetShape: Column)
            }
            type Query { film: Film @commits(source: AnchorTable, result: SingleRecord) }
            """,
            """
            {
              film {
                # A GraphQL enum return, still a plain column read.
                rating
              }
            }
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
              name: String @field(name: "country") @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
            }

            type City @table(name: "city") @classifiedType(as: TableType) {
              country: Country @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Table)
              countrySplit: Country @splitQuery @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Table)
                @commits(source: CorrelatedChain, result: SingleRecord)
            }

            type Query {
              city: City @classified(source: Query, operations: [Select], target: Single, targetShape: Table)
                @commits(source: AnchorTable, result: SingleRecord)
            }
            """,
            "{ city { country { name } countrySplit { name } } }"),

        /*
         * Keyed split lookup: a list child whose @lookupKey argument establishes a positional
         * input-list <-> output-list correspondence, fetched by a @splitQuery keyed batch
         * (a lookup-keyed batched read). The @lookupKey makes its operation Lookup; it lands on participant @table
         * rows (target Table); the new-query batch shape is derived, not a tuple axis. Corpus-only: it
         * is another Child / Lookup / Table leaf.
         */
        new Example("split-lookup", """
            type Customer @table(name: "customer") { firstName: String @field(name: "FIRST_NAME") }
            type Store @table(name: "store") {
              customers(customer_id: ID! @lookupKey): [Customer!]! @splitQuery
                @classified(source: OnlyChild, operations: [Join, Lookup, OrderBy, Select], target: List, targetShape: Table)
                @commits(source: CorrelatedLookupChain, result: RecordList)
            }
            type Query { store: Store @commits(source: AnchorTable, result: SingleRecord) }
            """),

        /*
         * Target-shape minimal pair: Column vs Field. A scalar under the @table parent Film projects a
         * Column (`title` is a real DB column); a scalar under a record-backed parent projects a Field
         * (`FilmStats.count` is a POJO property, the record having no @table). The non-table object
         * field `FilmDetails.stats` is the object flavor of the same record-read leaf
         * (RecordReadField). All three are inline
         * Fetch; only the parent's table-ness moves the source shape (Table vs Record) and with it the
         * target shape. The two parents are record-bound by being service producers' return types
         * (`makeFilmDetailsRecord` -> FilmDetailsRecord, whose sole component is `stats`;
         * `makeFilmStatsRecord` -> FilmStatsRecord, whose sole component is `count`).
         */
        new Example("mapping", """
            type FilmStats {
              count: Int @classified(source: Child, operations: [], target: Single, targetShape: Field, sourceShape: Record)
            }

            type FilmDetails {
              stats: FilmStats @classified(source: Child, operations: [], target: Single, targetShape: Field, sourceShape: Record)
            }

            type Film @table(name: "film") {
              title: String @classified(source: OnlyChild, operations: [Select], target: Single, targetShape: Column)
              details: FilmDetails
            }

            type Query {
              film: Film @commits(source: AnchorTable, result: SingleRecord)
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
         * but becomes a keyed re-query under the record-backed parent FilmDetails (BatchedTableField),
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
                @classified(source: Child, operations: [Join, Reentry, Select], target: Single, targetShape: Table, sourceShape: Record)
                @commits(source: CorrelatedChain, result: SingleRecord)
            }

            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Table)
              details: FilmDetails
            }

            type Query {
              film: Film @commits(source: AnchorTable, result: SingleRecord)
              prodFilmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            "{ film { language { name } details { language { name } } } }"),

        /*
         * Service side: a terminal record, a service re-query into a @table, and a pojo field. Both
         * child @service methods take the batch keys and return them keyed: a child @service resolves
         * through a DataLoader, so the signature is the batched one and a per-parent call has no
         * emission (nor a classification: the coordinate is rejected without a Sources parameter).
         */
        new Example("service", """
            type Language @table(name: "language") { name: String }

            type FilmDetails {
              title: String @classified(source: Child, operations: [], target: Single, targetShape: Field, sourceShape: Record)
            }

            type Film @table(name: "film") {
              details: FilmDetails
              rating: String
                @service(service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRatingMapped"})
                @classified(source: Child, operations: [ServiceCall], target: Single, targetShape: Record)
                @commits(source: ServiceCall, result: LoaderDelegated)
              language: Language
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguageByKey"})
                @classified(source: Child, operations: [Reentry, ServiceCall], target: Single, targetShape: Table)
                @commits(source: ServiceTableLift, result: LoaderDelegated)
            }

            type Query {
              film: Film
              prodFilmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDetailsProps"})
              externalFilm: Film
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
                @classified(source: Query, operations: [ServiceCall], target: Single, targetShape: Table)
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
                @classified(source: Query, operations: [ServiceCall], target: Single, targetShape: Record)
            }
            """),

        /*
         * A table-returning @service child (ServiceTableField): the developer's DataLoader-shaped
         * method (a Map from parent key rows to lists of FilmRecord) produces real table records,
         * and the emitted rows method lifts them back through a by-PK re-projection so multiset
         * sub-fields resolve off the projected row. The verdict is Child-side ServiceCall over a
         * @table target at list cardinality; the launcher row it commits is the service table
         * lift with the loader-delegated payload (the service arms' result slot is typed vacuity,
         * pinned to the source arm by the command's biconditional).
         */
        new Example("service-table-child", """
            type Film @table(name: "film") { title: String }
            type Language @table(name: "language") {
              name: String
              filmsViaService: [Film!]! @service(
                service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMapped"})
                @classified(source: OnlyChild, operations: [Reentry, ServiceCall], target: List, targetShape: Table)
                @commits(source: ServiceTableLift, result: LoaderDelegated)
            }
            type Query { language: Language @commits(source: AnchorTable, result: SingleRecord) }
            """,
            """
            {
              language {
                name
                # Films for this language, produced by a service and re-queried as table rows.
                filmsViaService { title }
              }
            }
            """),

        /*
         * A scalar @service child (ServiceRecordField): pure delegation, the developer method's
         * declared return shape IS the rows method's return shape (here a Map from parent key
         * rows to Int, so the loader passes both service-record production guards: a Sources key
         * exists and the scalar return skips no equality check). The verdict is Child-side
         * ServiceCall with the record-shaped target; the launcher row it commits is the outright
         * service call with the loader-delegated payload.
         */
        new Example("service-scalar-child", """
            type Language @table(name: "language") {
              name: String
              rank: Int @service(
                service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMapped"})
                @classified(source: OnlyChild, operations: [ServiceCall], target: Single, targetShape: Record)
                @commits(source: ServiceCall, result: LoaderDelegated)
            }
            type Query { language: Language @commits(source: AnchorTable, result: SingleRecord) }
            """,
            """
            {
              language {
                name
                # A scalar a service produces; no SQL of its own.
                rank
              }
            }
            """),

        /*
         * A batched child @service on a class-backed parent. The Sources element type names the table
         * the batch keys on, and the parent produces a record of it through the sole zero-arg accessor
         * on its backing class, so a type aggregated in Java hosts a batched child without becoming a
         * database view. The lesson is the source shape: both service leaves are minted on both parent
         * kinds, so neither leaf's identity answers what arrives at env.getSource(), and the stored key
         * source is what does. This example is the corpus arm that makes the derivation load-bearing:
         * without a service leaf on a class-backed parent, the source-shape mirror never sees the
         * Record answer on either service leaf.
         */
        new Example("service-child-class-backed-parent", """
            type Film @table(name: "film") { title: String }
            type Aggregated {
              rank: Int
                @service(service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMappedByRecord"})
                @classified(source: OnlyChild, operations: [ServiceCall], target: Single, targetShape: Record, sourceShape: Record)
                @commits(source: ServiceCall, result: LoaderDelegated)
              filmsViaService: [Film!]!
                @service(service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMappedByRecord"})
                @classified(source: OnlyChild, operations: [Reentry, ServiceCall], target: List, targetShape: Table, sourceShape: Record)
                @commits(source: ServiceTableLift, result: LoaderDelegated)
            }
            type Query {
              aggregated: Aggregated
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeLanguageKeyed"})
                @classified(source: Query, operations: [ServiceCall], target: Single, targetShape: Record)
            }
            """),

        /*
         * Result-type backing (a type-verdict cluster). A non-@table result type acquires its backing
         * class by reflection on the @service producer's return type, never from a directive, and
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
              name: String @classified(source: OnlyChild, operations: [], target: Single, targetShape: Field, sourceShape: Record)
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
         * class via graphql-java's default PropertyDataFetcher, so both classify as RecordReadField
         * with the DefaultRead locator (Child / Fetch / Field). The leaf carries the field's own
         * return wrapper, so the list-shaped `path` obeys the SDL-list mirror (target: List).
         * Corpus-only: it lands on the already-taught Child / Fetch / Field coordinate, and the
         * @error type itself is not a documentation-query selection shape.
         */
        new Example("error-field", """
            type MyError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
              path: [String!]! @classified(source: OnlyChild, operations: [], target: List, targetShape: Field, sourceShape: Record)
              message: String! @classified(source: OnlyChild, operations: [], target: Single, targetShape: Field, sourceShape: Record)
            }
            type Query { err: MyError }
            """),

        /*
         * @error type-verdict admission nuance. An @error type classifies as ErrorType (the GraphQL
         * type whose @error contract carries the handler set). A field beyond the mandatory
         * path/message (`severity`) does not break the verdict: the per-handler accessor check fires
         * on the carrier, not the @error type, so the type stays ErrorType. Corpus-only: the
         * @classifiedType axis is asserted directly; `path` doubles as the fixture's required field
         * coordinate (Child / Fetch / Field). (The @error-over-@record precedence verdict, @record
         * silently ignored, is covered by RecordDirectiveIgnoredWarningTest.)
         */
        new Example("error-type", """
            enum Severity { LOW HIGH }
            type ExtraFieldError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}])
                @classifiedType(as: ErrorType) {
              path: [String!]! @classified(source: OnlyChild, operations: [], target: List, targetShape: Field, sourceShape: Record)
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
              details: FilmDetails @classified(source: OnlyChild, operations: [], target: Single, targetShape: Table)
            }
            type Query { film: Film @commits(source: AnchorTable, result: SingleRecord) }
            """,
            """
            {
              film {
                # A grouping type with no table of its own.
                details {
                  title
                  description
                }
              }
            }
            """),

        /*
         * @pivot: a discriminator-keyed aggregate projection. The field pivots the narrow
         * film_translation (film_id, lang_code, title_txt) attribute table into one record per
         * parent, one filtered aggregate per selected slot; the return type is a plain output
         * type registered as an ordinary NestingType (nothing on the type says "pivot"; every
         * pivot fact lives on the consuming field's PivotSpec). The verdict is a new operation
         * (Pivot, the row-to-column verb) with target Single(Record) (the graphitron-built jOOQ
         * record the slot fetchers read by name). Delivery is not a tuple axis: the @splitQuery
         * sibling classifies the batched leaf with the identical verdict, exactly as child-table
         * inline/split pairs do. The vocabulary enum maps slot names to discriminator tokens at
         * @field(name:)'s canonical ENUM_VALUE site; the identity case (slot names = tokens)
         * omits it. The vocabulary enum itself carries no @classifiedType: @pivot(vocabulary:)
         * references it by name only, never on a type coordinate, so the classify-and-emit walk
         * never reaches it and it is pruned (the pivot classifier reads its value mapping straight
         * off the SDL). The EnumType verdict is pinned by the enum-column example above.
         */
        new Example("pivot", """
            enum Sprak {
              nn @field(name: "nno")
              nb @field(name: "nob")
            }
            type TranslatedTexts @classifiedType(as: NestingType) {
              nn: String
              nb: String
            }
            type Film @table(name: "film") {
              titleTexts: TranslatedTexts
                @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt", vocabulary: "Sprak")
                @classified(source: OnlyChild, operations: [Join, Pivot], target: Single, targetShape: Record)
              titleTextsSplit: TranslatedTexts @splitQuery
                @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt", vocabulary: "Sprak")
                @classified(source: OnlyChild, operations: [Join, Pivot], target: Single, targetShape: Record)
                @commits(source: PivotAggregate, result: SingleRecord)
            }
            type Query { film: Film @commits(source: AnchorTable, result: SingleRecord) }
            """,
            """
            {
              film {
                # One row of translations, pivoted out of a per-language table.
                titleTexts { nn nb }
              }
            }
            """),

        // The mixed-source reach (a type projected as a NestingField off a @table parent and also read
        // through a record producer) has no corpus entry: its per-edge field classifications are ordinary
        // and covered by the "nesting" example and the record-backed cases, and the cross-edge
        // reachable-source-shape union it adds is a type-level fact outside the @classified per-field
        // dimensions, pinned by MixedSourceNestedTypeReadsTest (positive) and
        // MixedSourceNestingReachValidationTest (negatives).

        /*
         * Polymorphic children and roots are catalog-bound over their participant tables: the target shape
         * is Interface / Union (the projection lands on participant @table rows), with the participant set
         * carried as a derived slot rather than a distinct shape value, and the operation is Fetch. A
         * plain-interface or union child (InterfaceField / UnionField) and any polymorphic root
         * (QueryInterfaceField / QueryUnionField) share that Fetch verdict; the new-query they open is
         * derived, not an axis. The exception's verdict is the same: a @table+@discriminate interface child
         * (TableInterfaceField / BatchedTableInterfaceField) is FK-correlatable from the parent and
         * classifies as a plain Fetch, its target shape being Table rather than Interface (one base
         * table, discriminated, not a participant fan-in). Delivery is leaf identity, not a tuple axis:
         * at list (or connection)
         * cardinality with at least one table-bound participant the child batches through a DataLoader
         * (BatchedInterfaceField / BatchedUnionField) with the same Fetch verdict its single-cardinality
         * inline sibling holds, exactly as the child-table inline/split pairs do; `namedPlaces`
         * (child-holds-FK: address.city_id points at the parent) and `relatedList` (parent-holds-FK
         * with the FK columns inside film_actor's primary key) pin the batched halves. The
         * discriminated child follows the same cardinality rule with its participant conjunct holding
         * structurally, and `mediaList` pins its batched half. A parent-holds-FK
         * participant whose FK columns sit outside the parent's primary key is single-valued and
         * rejects at list cardinality, so the customer/address pair stays single. Of the four shapes
         * below (plain interface, union,
         * table-interface, Relay Node) the interface and the union render doc examples, the interface
         * over its shared interface-level field and the union through inline fragments on its
         * participants; table-interface and Relay Node stay corpus-only.
         */
        new Example("interface", """
            interface Named @classifiedType(as: InterfaceType) { name: String }
            type Address implements Named @table(name: "address") { name: String @field(name: "ADDRESS") }
            type Customer @table(name: "customer") {
              address: Named @classified(source: OnlyChild, operations: [Select], target: Single, targetShape: Interface)
            }
            type City @table(name: "city") {
              namedPlaces: [Named!]! @classified(source: OnlyChild, operations: [Select], target: List, targetShape: Interface)
            }
            type Query {
              customer: Customer @commits(source: AnchorTable, result: SingleRecord)
              city: City @commits(source: AnchorTable, result: SingleRecord)
              anyNamed: Named @classified(source: Query, operations: [Select], target: Single, targetShape: Interface)
            }
            """,
            "{ customer { address { name } } }"),

        new Example("union", """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
            union FilmOrActor @classifiedType(as: UnionType) = Film | Actor
            type FilmActor @table(name: "film_actor") {
              related: FilmOrActor @classified(source: OnlyChild, operations: [Select], target: Single, targetShape: Union)
              relatedList: [FilmOrActor!]! @classified(source: OnlyChild, operations: [Select], target: List, targetShape: Union)
            }
            type Query {
              filmActor: FilmActor @commits(source: AnchorTable, result: SingleRecord)
              search: FilmOrActor @classified(source: Query, operations: [Select], target: Single, targetShape: Union)
            }
            """,
            "{ filmActor { related { ... on Film { title } ... on Actor { firstName } } } }"),

        /*
         * A multi-table polymorphic root with a @field-mapped filter argument. The argument
         * lowers once per table-bound participant against the participant's own table (customer
         * and staff both carry first_name), so the coordinate mints one condition member per
         * filtered participant: the per-participant filter surface the retired one-arm summary
         * could not hold, and the corpus's only two-condition-row coordinate (the member-list
         * assertion is a multiset, so the row count is voiced). Corpus-only.
         */
        new Example("polymorphic-filter", """
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            union Person @classifiedType(as: UnionType) = Customer | Staff
            type Query {
              people(firstName: [String!] @field(name: "first_name")): [Person!]!
                @classified(source: Query, operations: [OnParticipant, OnParticipant, Select], target: List, targetShape: Union)
            }
            """),

        /*
         * A root @service field returning a multitable interface
         * (QueryServicePolymorphicField, single cardinality). The service hands back a PK-populated
         * TableRecord per branch; the verdict is source Query, operation ServiceCall (the developer method
         * replaces the catalog read), and target Single, target shape Interface. Distinct-table
         * participants (film, actor) so record-class dispatch is well-defined. Interface only: a @service
         * returning a union is unsupported (rejected at classify). Corpus-only: it adds the
         * QueryServicePolymorphicField leaf and lands on the Query / ServiceCall coordinate.
         */
        new Example("query-service-polymorphic", """
            interface Searchable @classifiedType(as: InterfaceType) { name: String }
            type Film implements Searchable @table(name: "film") { name: String @field(name: "TITLE") }
            type Actor implements Searchable @table(name: "actor") { name: String @field(name: "FIRST_NAME") }
            type Query {
              searchService: Searchable
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
                @classified(source: Query, operations: [ServiceCall], target: Single, targetShape: Interface)
            }
            """),

        /*
         * Mutation analogue of the multitable interface @service root
         * (MutationServicePolymorphicField), list cardinality. The
         * service returns a Result<FilmRecord>; the fetcher dispatches each returned record on its runtime
         * class. Corpus-only: adds the
         * MutationServicePolymorphicField leaf and lands on the Mutation / ServiceCall coordinate.
         */
        new Example("mutation-service-polymorphic", """
            interface Searchable @classifiedType(as: InterfaceType) { name: String }
            type Film implements Searchable @table(name: "film") { name: String @field(name: "TITLE") }
            type Actor implements Searchable @table(name: "actor") { name: String @field(name: "FIRST_NAME") }
            type Query { film: Film @commits(source: AnchorTable, result: SingleRecord) }
            type Mutation {
              doSearch: [Searchable]
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilms"})
                @classified(source: Mutation, operations: [ServiceCall], target: List, targetShape: Interface)
            }
            """),

        /*
         * A root @service field returning a single-table discriminated interface
         * (QueryServiceTableInterfaceField, single cardinality). Unlike the multitable form above, all
         * implementers share one @table @discriminate table, so the service hands back records of that
         * one table; the emitted fetcher collects their PKs and re-fetches by PK, routing each row off
         * the live discriminator via the TableInterfaceType TypeResolver. Same wiring shape as the
         * multitable form (requiresReFetch() stays false). Corpus-only: adds the
         * QueryServiceTableInterfaceField leaf.
         */
        new Example("query-service-table-interface", """
            interface MediaItem @table(name: "film") @discriminate(on: "text_rating") @classifiedType(as: TableInterfaceType) { title: String }
            type FilmItem implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Query {
              mediaService: MediaItem
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
                @classified(source: Query, operations: [ServiceCall], target: Single, targetShape: Interface)
            }
            """),

        /*
         * Mutation analogue (MutationServiceTableInterfaceField), list cardinality. Same
         * single-table by-PK re-fetch as the query arm. Corpus-only: adds the
         * MutationServiceTableInterfaceField leaf.
         */
        new Example("mutation-service-table-interface", """
            interface MediaItem @table(name: "film") @discriminate(on: "text_rating") @classifiedType(as: TableInterfaceType) { title: String }
            type FilmItem implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Query { film: FilmItem @commits(source: AnchorTable, result: SingleRecord) }
            type Mutation {
              mediaSearch: [MediaItem]
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilms"})
                @classified(source: Mutation, operations: [ServiceCall], target: List, targetShape: Interface)
            }
            """),

        /*
         * The discriminated interface child at all three cardinalities. `media` is the inline half
         * (one per-parent SELECT over the re-projection, no launcher row); `mediaList` is the
         * batched half, whose launcher row carries the discriminated select list over the plain
         * batched child's correlated topology; `mediaConnection` is the batched half paginated,
         * the same launcher row with the windowed per-parent page tail. The list/single pair is
         * the delivery split's own witness (the tuple is identical apart from the cardinality
         * that decides it), and the connection coordinate is what makes the delivery pin a gate
         * over the leaf-versus-relation answer at that cardinality.
         */
        new Example("table-interface", """
            interface MediaItem @table(name: "film") @discriminate(on: "text_rating") @classifiedType(as: TableInterfaceType) { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Inventory @table(name: "inventory") {
              media: MediaItem @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Table)
            }
            type Language @table(name: "language") {
              mediaList: [MediaItem!]! @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: OnlyChild, operations: [Join, OrderBy, Select], target: List, targetShape: Table)
                @commits(source: DiscriminatedCorrelatedChain, result: RecordList)
              mediaConnection: [MediaItem!]! @asConnection @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: OnlyChild, operations: [Join, OrderBy, Paginate, Select], target: Single, targetShape: Connection)
                @commits(source: DiscriminatedCorrelatedChain, result: Connection)
            }
            type Query {
              inventory: Inventory @commits(source: AnchorTable, result: SingleRecord)
              language: Language @commits(source: AnchorTable, result: SingleRecord)
              topMedia: MediaItem @classified(source: Query, operations: [Select], target: Single, targetShape: Table)
                @commits(source: DiscriminatedTable, result: SingleRecord)
            }
            """,
            """
            {
              language {
                # Discriminated participants, read from one table.
                mediaList { title }
              }
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
              allParties: [Party!]! @classified(source: Query, operations: [OrderBy, Select], target: List, targetShape: Table)
                @commits(source: DiscriminatedTable, result: RecordList)
            }
            """),

        /*
         * The discriminated interface root, paginated: the same coordinate as
         * joined-table-interface's allParties with @asConnection on it. The pair is what the
         * classifier used to defer; the verdict is the plain connection root's, with the
         * discriminated launch source underneath.
         */
        new Example("paginated-joined-table-interface", """
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
              parties: [Party!]! @asConnection
                @classified(source: Query, operations: [OrderBy, Paginate, Select], target: Single, targetShape: Connection)
                @commits(source: DiscriminatedTable, result: Connection)
            }
            """,
            """
            {
              # A page of parties, each routed to its concrete type by the discriminator.
              parties(first: 2) {
                edges {
                  node {
                    displayName
                    ... on Individual {
                      birthDate
                    }
                  }
                }
              }
            }
            """),

        new Example("relay-node", """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") { id: ID! title: String }
            type Query {
              node(id: ID!): Node @classified(source: Query, operations: [NodeResolve], target: Single, targetShape: Interface)
              nodes(ids: [ID!]!): [Node] @classified(source: Query, operations: [NodeResolve], target: List, targetShape: Interface)
              internalFilmNode(id: ID): Node @classified(source: Query, operations: [NodeResolve], target: Single, targetShape: Interface)
            }
            """),

        /*
         * Coverage sweep. The fixtures from here to the end of the list are the long tail that brings
         * every output-field and (non-failure) type leaf under the corpus as single source of truth
         * (VariantCoverageTest), tested but not necessarily prose-featured. Each is annotated with its
         * dimensional verdict or its @classifiedType verdict.
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
                @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Column)
              computedRating: String
                @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
                @classified(source: OnlyChild, operations: [Select], target: Single, targetShape: Column)
            }
            type Query { film: Film @commits(source: AnchorTable, result: SingleRecord) }
            """,
            """
            {
              film {
                # Reached over a named foreign key.
                languageName
                # Computed in Java, not read from a column.
                computedRating
              }
            }
            """),

        /*
         * @lookupKey without @splitQuery, on a child and on a root. The child `FilmActor.actors` stays
         * an inline correlated subquery keyed by the lookup args (Child / Lookup /
         * Table); the root `Query.filmById` is a new query keyed by the lookup args (Root / Lookup /
         * Query / Lookup / Table). @lookupKey makes the operation Lookup; the batch-key shape is a slot.
         */
        new Example("lookup", """
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type FilmActor @table(name: "film_actor") {
              actors(actor_id: [Int!]! @lookupKey): [Actor!]!
                @classified(source: OnlyChild, operations: [Join, Lookup, OrderBy, Select], target: List, targetShape: Table)
            }
            type Query {
              filmActor: FilmActor @commits(source: AnchorTable, result: SingleRecord)
              filmById(film_id: [ID] @lookupKey): [Film]!
                @classified(source: Query, operations: [Lookup, OrderBy, Select], target: List, targetShape: Table)
                @commits(source: KeyedLookup, result: RecordList)
            }
            """,
            """
            {
              # Films fetched by a caller-supplied list of ids, one row back per id.
              filmById(film_id: ["1", "2"]) {
                filmId
              }
            }
            """),

        /*
         * @routine: a table-valued read function backing a root list field. jOOQ models the
         * function as a catalog Table<R>, so the verdict is the same shape as a plain catalog read
         * (a routine-sourced QueryTableField, Query / Fetch / List(Table)); only the FROM source differs (the
         * generated Routines convenience method, with IN params bound from GraphQL arguments). The
         * routine resolves against the sakila-db fixture catalog.
         *
         * @defaultOrder is not decoration here, it is the only ordering spelling the shape has.
         * A list result is ordered by the terminus primary key when there is one and by an
         * authored @defaultOrder when there is not; a function result has no primary key, so the
         * fallback finds nothing and the deterministic-order rule requires the columns be named.
         * That is why the example mints an OrderBy operation the plain-catalog reads get for free.
         */
        new Example("routine-table-valued-read", """
            type Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr") {
              organisasjonskode: Int
              rollekode: String
            }
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
                @defaultOrder(fields: [{name: "organisasjonskode"}, {name: "rollekode"}])
                @classified(source: Query, operations: [OrderBy, Select], target: List, targetShape: Table)
                @commits(source: RoutineChain, result: RecordList)
            }
            """,
            """
            {
              # A table-valued database routine, read like a table.
              tilganger(env: "test", serviceId: "svc", feideId: "user@example.org") {
                organisasjonskode
                rollekode
              }
            }
            """),

        /*
         * @routine on Mutation: the routine call IS the write and commits before the
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
            type Query { rental: Rental @commits(source: AnchorTable, result: SingleRecord) }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
                @classified(source: Mutation, operations: [RoutineWrite], target: List, targetShape: Table)
            }
            """),

        /*
         * @routine on Mutation without a @reference hop, returning a payload carrier: the
         * hop-less form (MutationRoutineWriteRecordField, Mutation / RoutineWrite /
         * Single(Record)). The routine call is the whole write transaction — step 1 captures
         * the target table's key columns off the routine's own result rows — and the payload's
         * data field owns the post-commit re-read, exactly as the DML record carriers' data
         * fields do. The data field's path is the implicit single name-matched hop: rent_film's
         * result exposes rental's primary key (rental_id) by name.
         */
        new Example("routine-mutation-carrier", """
            type Rental @table(name: "rental") {
              rentalId: Int! @field(name: "rental_id")
            }
            type RentFilmPayload {
              rental: Rental @commits(source: CorrelatedChain, result: SingleRecord)
            }
            type Query { rental: Rental @commits(source: AnchorTable, result: SingleRecord) }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @classified(source: Mutation, operations: [RoutineWrite], target: Single, targetShape: Record)
            }
            """),

        /*
         * A @table child under a jOOQ-TableRecord-backed parent, reached by @lookupKey. The record
         * handoff has already opened a new keyed scope, so the child re-queries (the new-query is
         * derived): `FilmDetails.language` is a lookup-keyed batched read (its @lookupKey makes the
         * operation Lookup, target Table). FilmDetails is record-bound as getFilm's jOOQ-TableRecord
         * return type, which supplies the FK source key.
         */
        new Example("record-method", """
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language(language_id: ID! @lookupKey): Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: Child, operations: [Join, Lookup, Reentry, Select], target: Single, targetShape: Table, sourceShape: Record)
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
         * leaf so the interface fetcher projects the gated cross-table subselect and the
         * per-field DataFetcher reads it back by alias.
         */
        new Example("participant-reference", """
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              contentId: Int! @field(name: "CONTENT_ID")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              contentId: Int! @field(name: "CONTENT_ID")
              rating: String @reference(path: [{key: "content_film_id_fkey"}]) @field(name: "RATING")
                @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Column)
            }
            type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
              contentId: Int! @field(name: "CONTENT_ID")
            }
            type Query { content: Content @commits(source: DiscriminatedTable, result: SingleRecord) }
            """,
            """
            {
              content {
                contentId
                ... on FilmContent {
                  # Reached over a foreign key from one participant only.
                  rating
                }
              }
            }
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
            type Query { films: FilmsConnection @commits(source: AnchorTable, result: Connection) }
            """),

        /*
         * Faceted directive-driven connection: an @asConnection carrier on a bare list whose
         * filter input marks an @asFacet field. The synthesised facet surface (the per-connection
         * FacetsType container and the reusable FacetValueType pool entry) has no SDL declaration
         * to carry @classifiedType, so the expectation is declared at the coordinate that causes
         * the synthesis: @synthesises names every type the carrier mints, and coverage counts an
         * arm only when the declaration agrees with the connection-synthesis relation's produced
         * row. The carrier uses the derived connection name (the recommended authoring); the
         * deprecated connectionName: override composing with @asFacet is pinned at pipeline tier
         * (FacetedConnectionPipelineTest), not taught here.
         */
        new Example("faceted-connection", """
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                title: [String!] @field(name: "title") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
                    @classified(source: Query, operations: [OnReturnTable, OrderBy, Paginate, Select], target: Single, targetShape: Connection)
                    @commits(source: AnchorTable, result: Connection)
                    @synthesises(mints: [
                        {name: "QueryFilmsConnection", as: ConnectionType},
                        {name: "QueryFilmsEdge", as: EdgeType},
                        {name: "QueryFilmsConnectionFacets", as: FacetsType},
                        {name: "StringFacetValue", as: FacetValueType},
                        {name: "PageInfo", as: PageInfoType}])
            }
            """,
            """
            {
              # A paginated read whose filter input also yields facet counts.
              films(filter: {title: ["A"]}) {
                edges { node { title } }
              }
            }
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
         * Plain jOOQ Record backing, both axes. A backing class assignable to org.jooq.Record but not
         * to org.jooq.TableRecord (the PlainJooqRecord test fixture) classifies the result type as
         * JooqRecordType and the input type as JooqRecordInputType, completing the reflection-driven
         * backing clusters of `result-backing` and `input-backing`. @classifiedType asserts the
         * verdicts directly; there is no field-side dimensional lesson.
         */
        new Example("plain-jooq-record-backing", """
            type PlainJooqRecordBacked @classifiedType(as: JooqRecordType) { id: ID }
            input PlainJooqRecordBackedInput @classifiedType(as: JooqRecordInputType) { id: ID }
            type Query {
              plainRecord: PlainJooqRecordBacked
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makePlainJooqRecord"})
              consumePlainRecord(in: PlainJooqRecordBackedInput): String
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "consumePlainJooqRecord"})
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
            type Query { film: Film @commits(source: AnchorTable, result: SingleRecord) }
            """,
            """
            {
              film {
                # The Relay global id, encoded from the node key columns.
                id
              }
            }
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
              filmIds: [ID] @nodeId(typeName: "Film") @classified(source: OnlyChild, operations: [], target: List, targetShape: Column, sourceShape: Record)
              errors: [DeleteFilmsError]
            }
            type Query { film: Film }
            type Mutation {
              deleteFilms: FilmIdsPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """),

        /*
         * The @service record-composite carrier: a mutation whose @service producer returns a
         * list of a consumer-authored composite (one FilmRecord plus a List<ActorRecord>). The payload
         * is a two-level carrier: a data field that is a list of an intermediate result type
         * (CreateFilmsResult, reflection-bound to the composite class, hence JavaRecordType), whose
         * @field-mapped @table children read off the composite through the record-backed accessor path
         * (BatchedTableField). The data field itself is a source-passthrough projection of the producer's
         * in-memory composite list, no re-fetch and no DataLoader (RecordCompositeField, source Child(Record),
         * operation Fetch, target List(Record)). The errors field rides the Outcome WrapperArm. The payload
         * classifies as JavaRecordType naming the per-element composite class, with the arrival
         * cardinality on the data field (the element-naming convention the bulk @table carrier also uses).
         */
        new Example("service-record-composite-carrier", """
            type Film @table(name: "film") @classifiedType(as: TableType) {
              title: String @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
            }
            type Actor @table(name: "actor") @classifiedType(as: TableType) {
              firstName: String @field(name: "first_name") @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
            }
            type CreateFilmsError @error(handlers: [{handler: DATABASE}]) {
              path: [String!]!
              message: String!
            }
            union CreateFilmsErr = CreateFilmsError
            type CreateFilmsResult @classifiedType(as: JavaRecordType) {
              film: Film! @field(name: "filmRecord")
                @classified(source: Child, operations: [Reentry, Select], target: Single, targetShape: Table, sourceShape: Record)
                @commits(source: CorrelatedChain, result: SingleRecord)
              actors: [Actor] @field(name: "actorRecords")
                @classified(source: Child, operations: [OrderBy, Reentry, Select], target: List, targetShape: Table, sourceShape: Record)
                @commits(source: CorrelatedChain, result: SingleRecord)
            }
            type CreateFilmsPayload @classifiedType(as: JavaRecordType) {
              results: [CreateFilmsResult]
                @classified(source: OnlyChild, operations: [], target: List, targetShape: Record, sourceShape: Record)
              errors: [CreateFilmsErr]
            }
            type Query { film: Film }
            type Mutation {
              createFilms: CreateFilmsPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmsWithActors"})
                @classified(source: Mutation, operations: [ServiceCall], target: Single, targetShape: Record)
            }
            """),

        /*
         * DML payload-carrier mutations (UPDATE and its bulk sibling, plus the bulk INSERT carrier).
         * Each returns a plain object wrapping one @table data field and exposes the affected rows as a
         * record, so the mutation field is source Mutation, target Record, with the write verb as the
         * operation: an Update write arm on MutationDmlRecordField / MutationBulkDmlRecordField, and an Insert arm on
         * the bulk INSERT carrier (the same MutationBulkDmlRecordField leaf). Distinct
         * payload types keep the per-kind carrier scans isolated. The DELETE payload cases
         * (the same carriers with a Delete write arm) live in the
         * `dml-delete-payload` example: their only admissible data field is an ID-element (a
         * @table-element projection off a deleted row is impossible), grounded on film_actor's
         * synthesised node metadata.
         */
        new Example("dml-payloads", """
            type Film @table(name: "film") { title: String }
            type FilmInsertBulkPayload { films: [Film!] @commits(source: CorrelatedChain, result: SingleRecord) }
            type FilmUpdatePayload { film: Film @commits(source: CorrelatedChain, result: SingleRecord) }
            type FilmUpdateBulkPayload { films: [Film!] @commits(source: CorrelatedChain, result: SingleRecord) }
            input FilmCreateInput { title: String }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id") title: String }
            type Query { film: Film }
            type Mutation {
              createFilmsPayload(in: [FilmCreateInput!]!): FilmInsertBulkPayload
                @mutation(typeName: INSERT)
                @classified(source: Mutation, operations: [Insert], target: Single, targetShape: Record)
              updateFilmPayload(in: FilmUpdateInput!): FilmUpdatePayload
                @mutation(typeName: UPDATE)
                @classified(source: Mutation, operations: [Update], target: Single, targetShape: Record)
              updateFilmsPayload(in: [FilmUpdateInput!]!): FilmUpdateBulkPayload
                @mutation(typeName: UPDATE)
                @classified(source: Mutation, operations: [Update], target: Single, targetShape: Record)
            }
            """),

        /*
         * DML side: an INSERT that writes then projects the inserted row. The write produces the row,
         * then a follow-up SELECT projects the @table return; the verdict is source Mutation, operation
         * Insert, target Table, and the follow-up re-fetch is derived (not a tuple axis). Doc example:
         * the projection query pulls in the FilmInput argument's input-object closure, so the rendered
         * excerpt shows the input the mutation consumes rather than dangling.
         */
        new Example("dml", """
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type Query { film: Film }
            type Mutation {
              createFilm(in: FilmInput!): Film
                @mutation(typeName: INSERT)
                @classified(source: Mutation, operations: [Insert, Reentry], target: Single, targetShape: Table)
                @commits(source: ProjectedReentry, result: SingleRecord)
            }
            """,
            "mutation { createFilm { title } }"),

        /*
         * DML returning a single-table discriminated interface: the INSERT writes the shared
         * @table+@discriminate base table, then the follow-up SELECT is the participant-driven
         * discriminated composition restricted to the RETURNING-captured keys. The mutation
         * classifies exactly like the plain projected INSERT (Mutation / Insert / Table; the
         * interface-ness rides the return type, not the verdict), but the launcher row it
         * commits is the discriminated reentry companion rather than the projected one: the
         * return-expression arm decides the source arm, which is why launcher membership is
         * return-arm-conditioned on the DML leaf. The root read over the same interface commits
         * the discriminated table launcher, the reentry arm's borrowed-whole payload.
         */
        new Example("dml-discriminated", """
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") @classifiedType(as: TableInterfaceType) {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            input ContentInput {
              title: String! @field(name: "TITLE")
              contentType: String! @field(name: "CONTENT_TYPE")
            }
            type Query { content: Content @commits(source: DiscriminatedTable, result: SingleRecord) }
            type Mutation {
              createContent(in: ContentInput!): Content
                @mutation(typeName: INSERT, table: "content")
                @classified(source: Mutation, operations: [Insert, Reentry], target: Single, targetShape: Table)
                @commits(source: DiscriminatedReentry, result: SingleRecord)
            }
            """),

        /*
         * The remaining root mutation forms (INSERT is the `dml` example above). UPDATE is a DML write
         * that projects the affected @table row back, so it is Mutation / Update / Table
         * (DmlTableField with an Update write arm; the projection re-fetch is derived). DELETE cannot project a
         * @table (the row is gone; RETURNING carries only the PK), so it tops out at an encoded-ID return:
         * Mutation / Delete / Column (DmlTableField with a Delete write arm and an Encoded* return-expression arm).
         * DELETE admits two ways onto the same verdict: a PK-covering filter input (`deleteFilm`) or an
         * explicit `multiRow: true` broadcast over a non-PK filter (`deleteFilmsBroadcast`). An @service
         * mutation re-queries the catalog for its @table return (MutationServiceTableField, Mutation /
         * ServiceCall / Table) or materializes a non-table record-backed type (MutationServiceRecordField,
         * Mutation / ServiceCall / Record). A DML payload carrier (a plain object wrapping one @table
         * data field) exposes the RETURNING rows as a record, so the carrier itself is Mutation / Insert /
         * Record (MutationDmlRecordField, Insert write arm), the follow-up projection being the data field's
         * own concern (a Child / Fetch / Table BatchedTableField on the payload). Corpus-only: these
         * remaining root forms are additional leaves on the principles the `dml` and `dml-payloads`
         * examples teach.
         */
        new Example("mutation-roots", """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
            type FilmDetails { title: String }
            type FilmPayload { film: Film @classified(source: OnlyChild, operations: [Reentry, Select], target: Single, targetShape: Table, sourceShape: Record)
                @commits(source: CorrelatedChain, result: SingleRecord) }
            input FilmKeyInput { filmId: Int! @field(name: "film_id") }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id") title: String }
            input FilmTitleInput { title: String @field(name: "title") }
            input FilmCreateInput { title: String }
            type Query { film: Film }
            type Mutation {
              updateFilm(in: FilmUpdateInput!): Film
                @mutation(typeName: UPDATE)
                @classified(source: Mutation, operations: [Reentry, Update], target: Single, targetShape: Table)
                @commits(source: ProjectedReentry, result: SingleRecord)
              deleteFilm(in: FilmKeyInput!): ID
                @mutation(typeName: DELETE, table: "film")
                @classified(source: Mutation, operations: [Delete], target: Single, targetShape: Column)
              deleteFilmsBroadcast(in: FilmTitleInput!): ID
                @mutation(typeName: DELETE, multiRow: true, table: "film")
                @classified(source: Mutation, operations: [Delete], target: Single, targetShape: Column)
              externalMutation: Film
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
                @classified(source: Mutation, operations: [ServiceCall], target: Single, targetShape: Table)
              externalRecord: FilmDetails
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runDetails"})
                @classified(source: Mutation, operations: [ServiceCall], target: Single, targetShape: Record)
              createFilmPayload(in: FilmCreateInput!): FilmPayload
                @mutation(typeName: INSERT)
                @classified(source: Mutation, operations: [Insert], target: Single, targetShape: Record)
            }
            """,
            """
            mutation {
              # Writes the row, then projects it back through the catalog.
              updateFilm { title }
              # Cannot project a row that is gone; hands back the deleted row's id.
              deleteFilm
            }
            """),

        /*
         * Composite node key (arity is a column count on the leaf, not a leaf dimension). film_actor
         * carries a two-column primary key with synthesised node metadata (__NODE_TYPE_ID "FilmActor",
         * __NODE_KEY_COLUMNS (actor_id, film_id)), so the @nodeId output carrier is the same
         * ColumnBackedField leaf the arity-1 `id` fields land on, now spanning two columns (a RowN
         * projection through the NodeIdEncodeKeys compaction). `filmActorId` is the FK-mirror read:
         * film_actor_note's composite FK targets film_actor's key columns positionally, so the
         * @nodeId(typeName:) reference collapses to the parent's own FK source columns, again a
         * composite ColumnBackedField with no join. The composite output *reference* flavour
         * (ColumnBackedReferenceField at arity > 1) exists only on the rooted-at-parent non-mirror
         * path, which is a validate-time deferred rejection, so the corpus pins its arity-1 form
         * (the `reference-and-computed` example) and the composite column form here.
         */
        new Example("composite-node-key", """
            interface Node { id: ID! }
            type FilmActor implements Node @table(name: "film_actor") @node @classifiedType(as: NodeType) {
              id: ID! @nodeId @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
            }
            type FilmActorNote @table(name: "film_actor_note") @classifiedType(as: TableType) {
              note: String @field(name: "note_txt") @classified(source: OnlyChild, operations: [Select], target: Single, targetShape: Column)
              filmActorId: ID @nodeId(typeName: "FilmActor") @classified(source: OnlyChild, operations: [Select], target: Single, targetShape: Column)
            }
            type Query {
              filmActor: FilmActor @commits(source: AnchorTable, result: SingleRecord)
              filmActorNote: FilmActorNote @commits(source: AnchorTable, result: SingleRecord)
            }
            """,
            """
            {
              filmActor {
                # A global id over a two-column key.
                id
              }
              filmActorNote {
                note
                # The same key, encoded from another table that names the target type.
                filmActorId
              }
            }
            """),

        /*
         * Payload-returning DELETE, both cardinalities. A DELETE's only admissible data field is an
         * ID-element encoded off RETURNING (the row is gone; a @table-element projection is rejected),
         * so the carrier classifies as MutationDmlRecordField / MutationBulkDmlRecordField with a
         * Delete write arm (Mutation / Delete / Single(Record), the bulk-ness riding the input cardinality and the data
         * field's list wrapper) and the data field as SingleRecordIdFieldFromReturning (an encoded-PK
         * column read off the RETURNING record: Fetch / Column with sourceShape Record). film_actor's
         * synthesised node metadata grounds the encode; the @nodeId input filter covers the composite
         * PK, satisfying DELETE's key-coverage admission.
         */
        new Example("dml-delete-payload", """
            interface Node { id: ID! }
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! @nodeId }
            input FilmActorRef { id: ID! @nodeId }
            type DeletedFilmActorPayload {
              deletedId: ID @classified(source: OnlyChild, operations: [], target: Single, targetShape: Column, sourceShape: Record)
            }
            type DeletedFilmActorsPayload {
              deletedIds: [ID!] @classified(source: OnlyChild, operations: [], target: List, targetShape: Column, sourceShape: Record)
            }
            type Query { filmActor: FilmActor }
            type Mutation {
              deleteFilmActor(in: FilmActorRef!): DeletedFilmActorPayload
                @mutation(typeName: DELETE, table: "film_actor")
                @classified(source: Mutation, operations: [Delete], target: Single, targetShape: Record)
              deleteFilmActors(in: [FilmActorRef!]!): DeletedFilmActorsPayload
                @mutation(typeName: DELETE, table: "film_actor")
                @classified(source: Mutation, operations: [Delete], target: Single, targetShape: Record)
            }
            """),

        /*
         * Arrival-fold edge cases. The fixtures below pin the ancestor-product arrival fold's
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
              name: String @classified(source: OnlyChild, operations: [Select], target: Single, targetShape: Column)
            }
            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Table)
            }
            type Query {
              film: Film @classified(source: Query, operations: [Select], target: Single, targetShape: Table)
                @commits(source: AnchorTable, result: SingleRecord)
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
              name: String @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
            }
            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: Child, operations: [Join, Select], target: Single, targetShape: Table)
            }
            type Query {
              films: [Film!]! @classified(source: Query, operations: [OrderBy, Select], target: List, targetShape: Table)
                @commits(source: AnchorTable, result: RecordList)
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
              name: String @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
            }
            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Table)
              originalLanguage: Language @reference(path: [{key: "film_original_language_id_fkey"}])
                @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Table)
            }
            type Query {
              film: Film @classified(source: Query, operations: [Select], target: Single, targetShape: Table)
                @commits(source: AnchorTable, result: SingleRecord)
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
                @classified(source: Child, operations: [Join, Select], target: Single, targetShape: Table)
            }
            type Store @table(name: "store") {
              manager: Staff @reference(path: [{key: "store_manager_staff_id_fkey"}])
                @classified(source: Child, operations: [Join, Select], target: Single, targetShape: Table)
            }
            type Query {
              store: Store @classified(source: Query, operations: [Select], target: Single, targetShape: Table)
                @commits(source: AnchorTable, result: SingleRecord)
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
              title: String @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
            }
            type Query {
              film: Film @classified(source: Query, operations: [Select], target: Single, targetShape: Table)
                @commits(source: AnchorTable, result: SingleRecord)
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
              title: String @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
            }
            type FilmsConnection {
              edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo!
            }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo {
              hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String
            }
            type Query { films: FilmsConnection @commits(source: AnchorTable, result: Connection) }
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
                @classified(source: OnlyChild, operations: [Reentry, Select], target: Single, targetShape: Table, sourceShape: Record)
                @commits(source: CorrelatedChain, result: SingleRecord)
            }
            type Query { film: Film }
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
     * / {@code @classifiedType} coordinate landed on, descending the ridden lists a classified leaf
     * carries ({@code NestingField.nestedFields()}, {@code PivotSpec.slots()}); a pivot slot or a
     * nesting child has no top-level coordinate of its own, so the descent is what lets the corpus
     * walk observe it. This set alone carries the output-field and type side of the variant-coverage
     * obligation ({@code ExemptionRegistry}): a leaf absent here fails coverage even when an enum
     * case still asserts it.
     *
     * <p>Synthesised type leaves join through {@code @synthesises} on a carrier coordinate: an
     * arm counts only when a declared mint agrees with the connection-synthesis relation's
     * produced row (same name, same arm, registry entry matching), never from the producer's
     * output alone, so the coverage stays author-checkable.
     */
    public static Set<Class<?>> coveredLeaves() {
        var leaves = new HashSet<Class<?>>();
        var mintedArmsBySimpleName = new java.util.HashMap<String, Class<?>>();
        for (var arm : no.sikt.graphitron.rewrite.model.ConnectionSynthesis.MINTED_ARM_VOCABULARY) {
            mintedArmsBySimpleName.put(arm.getSimpleName(), arm);
        }
        for (Example example : EXAMPLES) {
            var result = ClassifiedHarness.classify(example.sdl());
            for (var fc : result.fields()) {
                var field = result.schema().field(fc.parentType(), fc.fieldName());
                ClassifiedHarness.forEachWithRiddenFields(field, f -> leaves.add(f.getClass()));
            }
            for (var tc : result.types()) {
                if (tc.leaf() != null) {
                    leaves.add(tc.leaf());
                }
            }
            for (var sc : result.synthesises()) {
                for (var declared : sc.declared()) {
                    if (sc.produced().contains(declared)) {
                        leaves.add(mintedArmsBySimpleName.get(declared.arm()));
                    }
                }
            }
        }
        return leaves;
    }
}
