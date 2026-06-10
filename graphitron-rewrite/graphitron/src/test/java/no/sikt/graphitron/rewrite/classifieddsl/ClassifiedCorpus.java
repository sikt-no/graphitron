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
 * <p>Slice 1 seeded this with a small value-covering set (every {@link ProducerStep} / {@link Mapping}
 * value exercised at least once). Slice 2 grows it example by example as the {@code code-generation-triggers}
 * documentation pulls each one in, retiring the matching {@code GraphitronSchemaBuilderTest} enum row as
 * the corpus picks up its leaf (see {@link #coveredLeaves()}).
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
            type Query {
              film: Film @classified(producer: [Query], mapping: Table)
              films: [Film!]! @asConnection @classified(producer: [Query], mapping: TableConnection)
            }

            type Film @table(name: "film") @classifiedType(as: TableType) {
              title: String @classified(producer: [], mapping: Column)
            }
            """,
            "{ film { title } }"),

        /*
         * Child table fields: the producer minimal pair. Both fields return the same @table type over
         * the same city -> country FK and hold mapping = Table; they differ only on producer.
         * `country` inlines (producer []), a correlated subquery folded into city's SELECT; `@splitQuery`
         * flips `countrySplit` to a new keyed query (producer [Query]).
         */
        new Example("child-table", """
            type Country @table(name: "country") @classifiedType(as: TableType) {
              name: String @field(name: "country") @classified(producer: [], mapping: Column)
            }

            type City @table(name: "city") @classifiedType(as: TableType) {
              country: Country @classified(producer: [], mapping: Table)
              countrySplit: Country @splitQuery @classified(producer: [Query], mapping: Table)
            }

            type Query {
              city: City @classified(producer: [Query], mapping: Table)
            }
            """,
            "{ city { country { name } countrySplit { name } } }"),

        /*
         * Keyed split lookup: a list child whose @lookupKey argument establishes a positional
         * input-list <-> output-list correspondence, fetched by a @splitQuery keyed batch
         * (SplitLookupTableField). Like the @splitQuery split above it opens a new keyed query
         * (producer [Query]) and lands on participant @table rows (mapping Table); the @lookupKey
         * shape only changes how the batch is keyed, not the dimensional verdict. Corpus-only: it is
         * another leaf on the already-taught [Query] / Table coordinate, and its @lookupKey argument
         * needs the argument rendering the QueryViewRenderer does not yet support (hardening item 3).
         */
        new Example("split-lookup", """
            type Customer @table(name: "customer") { firstName: String @field(name: "FIRST_NAME") }
            type Store @table(name: "store") {
              customers(customer_id: ID! @lookupKey): [Customer!]! @splitQuery
                @classified(producer: [Query], mapping: Table)
            }
            type Query { store: Store }
            """),

        /*
         * Mapping minimal pair: Column vs Field. A scalar under the @table parent Film maps to Column
         * (`title` is a real DB column); a scalar under a record-backed parent maps to Field
         * (`FilmStats.count` is a POJO property, the record having no @table). The non-table object
         * field `FilmDetails.stats` is the object flavor of Field (RecordField). All three are inline
         * (producer []); only the parent's table-ness moves the mapping axis. The two parents are
         * record-bound by being service producers' return types (`makeFilmDetailsRecord` ->
         * FilmDetailsRecord, whose sole component is `stats`; `makeFilmStatsRecord` -> FilmStatsRecord,
         * whose sole component is `count`).
         */
        new Example("mapping", """
            type FilmStats {
              count: Int @classified(producer: [], mapping: Field)
            }

            type FilmDetails {
              stats: FilmStats @classified(producer: [], mapping: Field)
            }

            type Film @table(name: "film") {
              title: String @classified(producer: [], mapping: Column)
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
         * Producer minimal pair across the record-handoff boundary. The same FK-reached @table child
         * (`language` via film_language_id_fkey) inlines into the parent SELECT under the @table parent
         * Film (producer [], a correlated subquery, TableField) but becomes a keyed re-query under the
         * record-backed parent FilmDetails (producer [Query], RecordTableField), because the record
         * handoff has already opened a new DataLoader-backed scope; it cannot fold back into the parent
         * SELECT. Both hold mapping = Table. FilmDetails is record-bound as makeDummyRecord's return
         * type; the explicit @reference disambiguates film's two FKs to language.
         */
        new Example("record-table", """
            type Language @table(name: "language") { name: String }

            type FilmDetails {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(producer: [Query], mapping: Table)
            }

            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
                @classified(producer: [], mapping: Table)
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
              title: String @classified(producer: [], mapping: Field)
            }

            type Film @table(name: "film") {
              details: FilmDetails
              rating: String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
                @classified(producer: [Service], mapping: Record)
              language: Language
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
                @classified(producer: [Service, Query], mapping: Table)
            }

            type Query {
              film: Film
              prodFilmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDetailsProps"})
            }
            """),

        /*
         * Nesting: a plain object child (no @table, no @record) on a @table parent inlines into the
         * parent's projection, inheriting the parent's table context (NestingField). Its scalars resolve
         * against the parent table, so the field is producer [] (no new query) and maps to Table.
         * Corpus-only (the inline-Table verdict is already taught by the producer minimal pair); this
         * adds the NestingField leaf to the corpus's covered set.
         */
        new Example("nesting", """
            type FilmDetails { title: String description: String }
            type Film @table(name: "film") {
              details: FilmDetails @classified(producer: [], mapping: Table)
            }
            type Query { film: Film }
            """),

        /*
         * Constructor passthrough: a @record child type under a @table parent. Film.details builds the
         * record-backed FilmDetails from the parent's row in the parent SELECT (ConstructorField), so it
         * is producer [] (no new query) and maps to Record (it materializes a record, not a catalog
         * projection). FilmDetails is record-bound as makeFilmDetailsRating's return type. Corpus-only.
         */
        new Example("constructor", """
            type FilmDetails { rating: String }
            type Film @table(name: "film") {
              details: FilmDetails @classified(producer: [], mapping: Record)
            }
            type Query {
              film: Film
              prodFilmDetails: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmDetailsRating"})
            }
            """),

        /*
         * Polymorphic children and roots are catalog-bound over their participant tables: the mapping is
         * Table (the projection lands on participant @table rows), with the participant set carried as a
         * derived slot rather than a distinct mapping value. A plain-interface or union child opens a new
         * keyed query (producer [Query], InterfaceField / UnionField), as does any polymorphic root
         * (QueryInterfaceField / QueryUnionField). The exception is a @table+@discriminate interface child
         * (TableInterfaceField): it is FK-correlatable from the parent and classifies inline (producer
         * []), though the generator currently emits a per-parent query (the R288 defect; the corpus
         * asserts the correct verdict). The four shapes below (plain interface, union, table-interface,
         * Relay Node) are corpus-only except the interface, which renders a doc example over the shared
         * interface-level field; union and Relay selections need fragment / argument rendering the
         * QueryViewRenderer does not yet support (R281 pre-migration-hardening item 3).
         */
        new Example("interface", """
            interface Named { name: String }
            type Address implements Named @table(name: "address") { name: String @field(name: "ADDRESS") }
            type Customer @table(name: "customer") {
              address: Named @classified(producer: [Query], mapping: Table)
            }
            type Query {
              customer: Customer
              anyNamed: Named @classified(producer: [Query], mapping: Table)
            }
            """,
            "{ customer { address { name } } }"),

        new Example("union", """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
            union FilmOrActor = Film | Actor
            type FilmActor @table(name: "film_actor") {
              related: FilmOrActor @classified(producer: [Query], mapping: Table)
            }
            type Query {
              filmActor: FilmActor
              search: FilmOrActor @classified(producer: [Query], mapping: Table)
            }
            """),

        new Example("table-interface", """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Inventory @table(name: "inventory") {
              media: MediaItem @classified(producer: [], mapping: Table)
            }
            type Query {
              inventory: Inventory
              topMedia: MediaItem @classified(producer: [Query], mapping: Table)
            }
            """),

        new Example("relay-node", """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") { id: ID! title: String }
            type Query {
              node(id: ID!): Node @classified(producer: [Query], mapping: Table)
              nodes(ids: [ID!]!): [Node] @classified(producer: [Query], mapping: Table)
              internalFilmNode(id: ID): Node @classified(producer: [Query], mapping: Table)
            }
            """),

        /* DML side: an INSERT that writes then projects the inserted row (a [Dml, Query] pipeline). */
        new Example("dml", """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
              createFilm(in: FilmInput!): Film
                @mutation(typeName: INSERT)
                @classified(producer: [Dml, Query], mapping: Table)
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
