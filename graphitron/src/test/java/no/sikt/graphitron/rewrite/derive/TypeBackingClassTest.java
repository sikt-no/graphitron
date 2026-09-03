package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CLASS;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_SEED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_type_backing_class}, the closure that answers
 * which Java class backs a graph's type.
 *
 * <p>The subject here is reach. The relation is materialized rather than derived on read, because
 * the closure runs over the SDL type graph and that graph is cyclic, so its rows are what a
 * capture-cadence derivation writer put there and only a run of that writer can state what it
 * produces. Each case therefore captures a schema for real and reads the table afterwards: which
 * groundings arrive, how much further than one step the frontier goes, that a cycle terminates at
 * one row rather than looping, and the one condition that stops a hop.
 *
 * <p>The SDL is captured and the census is hand-built. A directive application is a fact capture
 * produces, while a census row is a name, a descriptor and a decomposed declared type, which is all
 * these rules read. Hand-building it is also what lets one fixture hold the shapes a closure has to
 * get right side by side: a chain three types deep, a cycle, a coordinate two producers answer
 * differently, and a field whose own producer overrides what its parent's member would say.
 *
 * <p>What a producer's facts make of the groundings, and what coalescing this closure with the
 * table-bound population makes of its rows, are not asked here. Those are the algebra of
 * {@code intent_type_backing_seed}, {@code intent_type_backing} and
 * {@code intent_type_backing_conflict}, and they live in the module whose DDL declares them, in
 * {@code no.sikt.graphitron.model.intent.TypeBackingSeedTest} and
 * {@code no.sikt.graphitron.model.intent.TypeBackingTest}, against a store seeded row by row.
 *
 * <p>Several cases assert that a coordinate produces no row. Those are the closure's own claim
 * rather than gaps in it: the boundary of what a class can back is where the closure stops, and the
 * two cases under the departure heading pin behaviour that differs from the reflective walk this
 * derivation replaces, written as pins because the adjudication belongs with the shadow.
 */
@PipelineTier
class TypeBackingClassTest {

    @TempDir
    Path tmp;

    // ===== The seeds =====

    /**
     * The seed: a field with an authored Java reference backs the type it returns with what the
     * resolved method delivers, containers peeled. Nothing else grounds the closure.
     */
    @Test
    void aProducerBacksTheTypeItReturns() {
        withCapturedStore(dsl ->
            assertThat(backing(dsl, GRAPH, "Film")).containsExactly("app.FilmRecord"));
    }

    /**
     * A class can stand for an object or an input object, so an SDL name of any other kind is where
     * the closure stops. No reject list over Java classes states that: the walk this replaces
     * excludes String, Boolean, the java packages and the rest one at a time, and here a scalar
     * field simply names a type nothing can back.
     */
    @Test
    void onlyCompositeTypesAreBacked() {
        withCapturedStore(dsl -> {
            assertThat(backing(dsl, GRAPH, "Int")).isEmpty();
            assertThat(backing(dsl, GRAPH, "String")).isEmpty();
            assertThat(backing(dsl, GRAPH, "Node"))
                .as("an interface is not what a hop lands on either")
                .isEmpty();
        });
    }

    // ===== The closure =====

    /**
     * The closure follows the hops, and it follows them further than one step. Language is reached
     * off Film's member and Country off Language's, so the third type is bound by a pass that had
     * nothing to read when the first one ran.
     */
    @Test
    void theClosureFollowsHopsAsFarAsTheyGo() {
        withCapturedStore(dsl -> {
            assertThat(backing(dsl, GRAPH, "Language")).containsExactly("app.LanguageRecord");
            assertThat(backing(dsl, GRAPH, "Country")).containsExactly("app.CountryRecord");
            assertThat(backing(dsl, GRAPH, "Actor"))
                .as("and through a container, the hop having peeled it")
                .containsExactly("app.ActorRecord");
        });
    }

    /**
     * A cycle in the SDL type graph terminates. This is the shape that makes the relation a table
     * rather than a view: a field whose type is its own parent's is ordinary, and a recursive view
     * over it would not stop.
     */
    @Test
    void aCycleInTheTypeGraphTerminatesAtOneRow() {
        withCapturedStore(dsl ->
            assertThat(dsl.fetchCount(INTENT_TYPE_BACKING_CLASS,
                INTENT_TYPE_BACKING_CLASS.GRAPH_NAME.eq(GRAPH)
                    .and(INTENT_TYPE_BACKING_CLASS.TYPE_NAME.eq("Film"))))
                .isOne());
    }

    /**
     * The one closure condition, and it is not a property of any hop: a coordinate with a producer
     * of its own is not read off its parent, its value coming from that method. The parent's member
     * of the same name delivers a different class here, so the case pins both directions at once.
     */
    @Test
    void aFieldWithItsOwnProducerIsNotReadOffItsParent() {
        withCapturedStore(dsl ->
            assertThat(backing(dsl, GRAPH, "Review")).containsExactly("app.ReviewDto"));
    }

    /**
     * Two producers answering one type differently are two rows, where the walk suppresses the
     * second observation to protect the first and leaves the disagreement unobservable. The writer
     * records both and prefers neither; that a reader can then learn the type is contested is the
     * coalesce's own claim, stated where that view lives.
     */
    @Test
    void aTypeTwoProducersAnswerDifferentlyIsTwoRows() {
        withCapturedStore(dsl ->
            assertThat(backing(dsl, GRAPH, "Contested"))
                .containsExactly("app.Left", "app.Right"));
    }

    // ===== The input axis =====

    /**
     * The second seed. A producer's parameter backs the type of the argument it is fed from, which
     * by default is the argument sharing its name. Nothing about the result axis reaches an input
     * object, so without this seed the whole input surface is unbacked.
     */
    @Test
    void aParameterBacksTheTypeOfItsArgument() {
        withCapturedStore(dsl ->
            assertThat(backing(dsl, GRAPH, "FilmFilter"))
                .containsExactly("app.FilmFilterInput"));
    }

    /**
     * One closure, not two. An input object seeded from a parameter has its own fields read off
     * that class by the same frontier that reads an output type's, so the surface below it is
     * backed without the input axis owning a second expansion.
     */
    @Test
    void anInputObjectSeededFromAParameterHasItsFieldsRead() {
        withCapturedStore(dsl ->
            assertThat(backing(dsl, GRAPH, "NestedFilter"))
                .containsExactly("app.NestedFilterInput"));
    }

    // ===== What the closure does not reach =====

    /**
     * A type bound by {@code @table} seeds nothing here, that population being the table binding's
     * own. The classes it would seed are the generated jOOQ records the classpath census excludes by
     * design, so the answer comes from the catalog or from nowhere, and where the two populations
     * meet is the coalesce rather than this relation.
     */
    @Test
    void aTableBoundTypeIsNotSomethingTheClosureSeeds() {
        withCapturedStore(dsl -> assertThat(backing(dsl, GRAPH, "Tabled")).isEmpty());
    }

    // ===== Which rows a producer grounded =====

    /**
     * The seeds are the groundings and nothing else: a producer's return on one axis, the class
     * feeding an argument on the other. A type only a hop reaches has no row here even though the
     * closure backs it, which is the whole of what that relation adds over this one.
     */
    @Test
    void aSeedIsAGroundingAndAHopIsNot() {
        withCapturedStore(dsl -> {
            assertThat(seeds(dsl, GRAPH, "Film")).containsExactly("app.FilmRecord");
            assertThat(seeds(dsl, GRAPH, "FilmFilter")).containsExactly("app.FilmFilterInput");
            assertThat(backing(dsl, GRAPH, "Country")).containsExactly("app.CountryRecord");
            assertThat(seeds(dsl, GRAPH, "Country"))
                .as("two hops deep, so backed and not grounded").isEmpty();
        });
    }

    /**
     * The contest the grounding relation exists to let a reader settle. A type a producer grounds
     * and a member of another type also delivers is two rows in the closure, which cannot say which
     * of them a producer answered for. Here it can, and the difference matters rather than being a
     * tie-break: the hop reads the parent's member type without checking it against the child's own
     * grounding, so the class it lands on can be wrong and not merely second. The precedence stays
     * the reader's, which is why the closure keeps both rows.
     */
    @Test
    void aTypeAProducerGroundsIsToldApartFromWhatAHopReached() {
        withCapturedStore(dsl -> {
            assertThat(backing(dsl, GRAPH, "Grounded"))
                .containsExactly("app.GroundedDto", "app.GroundedRecord");
            assertThat(seeds(dsl, GRAPH, "Grounded")).containsExactly("app.GroundedDto");
        });
    }

    // ===== Departures from the walk, pinned =====

    /**
     * The walk declines to bind where the SDL field and the producer's return disagree on
     * cardinality, reading the field as a carrier whose collection feeds an inner list field. No
     * such guard here: the type is backed by what the method delivers. The cardinality reading is
     * its own fact rather than a clause of this one, and it is where that difference is adjudicated.
     */
    @Test
    void aCollectionReturnBacksASingleObjectFieldHere() {
        withCapturedStore(dsl ->
            assertThat(backing(dsl, GRAPH, "Carrier")).containsExactly("app.CarrierRecord"));
    }

    /**
     * A type nothing reaches has no row, which is the same answer the walk gives and worth pinning
     * beside the departures: the closure is grounded, so an object no producer returns and no
     * member delivers is simply not backed.
     */
    @Test
    void aTypeNoProducerReachesIsNotBacked() {
        withCapturedStore(dsl -> assertThat(backing(dsl, GRAPH, "Orphan")).isEmpty());
    }

    // ===== Partition =====

    /**
     * Two graphs in one store, each with its own classpath entry declaring the same service class.
     * The seeds resolve through the graph's own membership and the hops depart from that graph's
     * own classes, so neither graph's types are backed by the other's.
     */
    @Test
    void siblingGraphsCloseOverTheirOwnMembership() {
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, SDL, jooq(), census())
                 .andGraph(SIBLING, SIBLING_SDL, siblingCensus())) {
            var dsl = store.dsl();
            assertThat(backing(dsl, SIBLING, "Film")).containsExactly("lib.FilmDto");
            assertThat(backing(dsl, SIBLING, "Language")).containsExactly("lib.LangDto");
            assertThat(backing(dsl, GRAPH, "Film")).containsExactly("app.FilmRecord");
            assertThat(backing(dsl, GRAPH, "Language")).containsExactly("app.LanguageRecord");
        }
    }

    // ===== Helpers =====

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String SIBLING = "sibling";

    private static final String APP = "app/target/classes";
    private static final String OTHER = "other/target/classes";

    /**
     * One chain three types deep, one cycle, one coordinate two producers answer differently, one
     * field whose own producer overrides its parent's member, one scalar producer and one object
     * nothing reaches. One type carries {@code @table} against the test catalog, which is the
     * population the closure never seeds.
     */
    private static final String SDL = """
        type Query {
            films: [Film] @service(service: {className: "app.FilmService", method: "findAll"})
            count: Int @service(service: {className: "app.FilmService", method: "count"})
            node: Node @service(service: {className: "app.FilmService", method: "node"})
            contested: Contested @service(service: {className: "app.FilmService", method: "left"})
            also: Contested @service(service: {className: "app.FilmService", method: "right"})
            one: Carrier @service(service: {className: "app.FilmService", method: "one"})
            search(filter: FilmFilter): [Film] @service(
                service: {className: "app.FilmService", method: "search"})
            grounded: Grounded @service(
                service: {className: "app.FilmService", method: "grounded"})
        }
        type Film {
            title: String
            language: Language
            actors: [Actor]
            reviews: [Review] @service(service: {className: "app.ReviewService", method: "forFilm"})
            related: Film
            grounded: Grounded
        }
        type Language {
            name: String
            country: Country
        }
        type Tabled @table(name: "film") { title: String }
        type Country { code: String }
        type Actor { name: String }
        type Review { body: String }
        type Contested { id: ID }
        type Carrier { id: ID }
        type Orphan { id: ID }
        type Grounded { id: ID }
        interface Node { id: ID }
        input FilmFilter { title: String, nested: NestedFilter }
        input NestedFilter { code: String }
        """;

    /** The sibling graph's own schema, reaching one hop so the partition covers both directions. */
    private static final String SIBLING_SDL = """
        type Query {
            films: [Film] @service(service: {className: "app.FilmService", method: "findAll"})
        }
        type Film { title: String  language: Language }
        type Language { name: String }
        """;

    /**
     * Two service classes and the records their returns reach. {@code app.FilmRecord}'s
     * {@code reviews} component names a class no SDL coordinate should reach, which is what makes
     * the producer-override case an assertion rather than a coincidence.
     */
    private static List<CompletionData.ExternalReference> census() {
        return List.of(
            reference(APP, "app.FilmService",
                method("findAll", "()Ljava/util/List;",
                    ref("", "java.util.List"), ref("0", "app.FilmRecord")),
                method("count", "()Ljava/lang/Integer;", ref("", "java.lang.Integer")),
                method("node", "()Lapp/NodeRecord;", ref("", "app.NodeRecord")),
                method("left", "()Lapp/Left;", ref("", "app.Left")),
                method("right", "()Lapp/Right;", ref("", "app.Right")),
                method("one", "()Ljava/util/List;",
                    ref("", "java.util.List"), ref("0", "app.CarrierRecord")),
                producer("search", "(Lapp/FilmFilterInput;)Ljava/util/List;",
                    List.of(parameter("filter", ref("", "app.FilmFilterInput")))),
                method("grounded", "()Lapp/GroundedDto;", ref("", "app.GroundedDto"))),
            reference(APP, "app.ReviewService",
                method("forFilm", "()Ljava/util/List;",
                    ref("", "java.util.List"), ref("0", "app.ReviewDto"))),
            record(APP, "app.FilmRecord",
                component("title", ref("", "java.lang.String")),
                component("language", ref("", "app.LanguageRecord")),
                component("actors", ref("", "java.util.List"), ref("0", "app.ActorRecord")),
                component("reviews", ref("", "java.util.List"), ref("0", "app.WrongRecord")),
                component("related", ref("", "app.FilmRecord")),
                component("grounded", ref("", "app.GroundedRecord"))),
            record(APP, "app.LanguageRecord",
                component("name", ref("", "java.lang.String")),
                component("country", ref("", "app.CountryRecord"))),
            record(APP, "app.CountryRecord", component("code", ref("", "java.lang.String"))),
            record(APP, "app.ActorRecord", component("name", ref("", "java.lang.String"))),
            record(APP, "app.ReviewDto", component("body", ref("", "java.lang.String"))),
            record(APP, "app.CarrierRecord", component("id", ref("", "java.lang.String"))),
            record(APP, "app.Left", component("id", ref("", "java.lang.String"))),
            record(APP, "app.Right", component("id", ref("", "java.lang.String"))),
            record(APP, "app.NodeRecord", component("id", ref("", "java.lang.String"))),
            record(APP, "app.FilmFilterInput",
                component("title", ref("", "java.lang.String")),
                component("nested", ref("", "app.NestedFilterInput"))),
            record(APP, "app.NestedFilterInput", component("code", ref("", "java.lang.String"))),
            record(APP, "app.GroundedDto", component("id", ref("", "java.lang.String"))),
            record(APP, "app.GroundedRecord", component("id", ref("", "java.lang.String"))));
    }

    /** The same service class on a classpath entry of the sibling's own, delivering other records. */
    private static List<CompletionData.ExternalReference> siblingCensus() {
        return List.of(
            reference(OTHER, "app.FilmService",
                method("findAll", "()Ljava/util/List;",
                    ref("", "java.util.List"), ref("0", "lib.FilmDto"))),
            record(OTHER, "lib.FilmDto",
                component("title", ref("", "java.lang.String")),
                component("language", ref("", "lib.LangDto"))),
            record(OTHER, "lib.LangDto", component("name", ref("", "java.lang.String"))));
    }

    private static CompletionData.ExternalReference reference(
        String sourceName, String className, CompletionData.Method... methods) {
        return new CompletionData.ExternalReference(className, className, "",
            List.of(methods), List.of(), List.of(), "CLASS", sourceName, List.of());
    }

    private static CompletionData.ExternalReference record(
        String sourceName, String className, CompletionData.RecordComponent... components) {
        return new CompletionData.ExternalReference(className, className, "",
            List.of(), List.of(components), List.of(), "RECORD", sourceName, List.of());
    }

    private static CompletionData.Method method(
        String name, String descriptor, CompletionData.TypeRef... refs) {
        return new CompletionData.Method(name, "Object", "", List.of(), false, descriptor,
            "Object", List.of(refs));
    }

    /** A method taking parameters and handing back a list of films, the input axis's shape. */
    private static CompletionData.Method producer(
        String name, String descriptor, List<CompletionData.Parameter> parameters) {
        return new CompletionData.Method(name, "Object", "", parameters, false, descriptor,
            "Object", List.of(ref("", "java.util.List"), ref("0", "app.FilmRecord")));
    }

    private static CompletionData.Parameter parameter(
        String name, CompletionData.TypeRef... refs) {
        return new CompletionData.Parameter(name, "Object", "", "", "Object", List.of(refs));
    }

    private static CompletionData.RecordComponent component(
        String name, CompletionData.TypeRef... refs) {
        return new CompletionData.RecordComponent(name, "Object", "Object", List.of(refs));
    }

    private static CompletionData.TypeRef ref(String path, String referencedClass) {
        return new CompletionData.TypeRef(path, referencedClass, "NONE");
    }

    /** Every class backing the named type, in name order so a case can state the whole answer. */
    private static List<String> backing(DSLContext dsl, String graphName, String typeName) {
        return dsl.select(INTENT_TYPE_BACKING_CLASS.CLASS_NAME)
            .from(INTENT_TYPE_BACKING_CLASS)
            .where(INTENT_TYPE_BACKING_CLASS.GRAPH_NAME.eq(graphName)
                .and(INTENT_TYPE_BACKING_CLASS.TYPE_NAME.eq(typeName)))
            .orderBy(INTENT_TYPE_BACKING_CLASS.CLASS_NAME)
            .fetch(0, String.class);
    }

    /** The groundings of the named type, which is the subset a producer answered for. */
    private static List<String> seeds(DSLContext dsl, String graphName, String typeName) {
        var s = INTENT_TYPE_BACKING_SEED;
        return dsl.select(s.CLASS_NAME).from(s)
            .where(s.GRAPH_NAME.eq(graphName)).and(s.TYPE_NAME.eq(typeName))
            .orderBy(s.CLASS_NAME)
            .fetch(0, String.class);
    }

    private void withCapturedStore(Consumer<DSLContext> body) {
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, SDL, jooq(), census())) {
            body.accept(store.dsl());
        }
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
