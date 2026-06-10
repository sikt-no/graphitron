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
