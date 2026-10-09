package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for the reach of {@code graphitron_type_backing}: the closure
 * over producers and accessor hops that answers which Java class backs a graph's type.
 *
 * <p>The subject here is reach. The relation is anchored rather than derived on read, because
 * the closure runs over the SDL type graph and that graph is cyclic, so its rows are what the
 * graphitron gatherer's anchoring put there and only a run of it can state what it produces.
 * Each case therefore captures a schema for real and reads the table afterwards: which
 * groundings arrive, how much further than one step the frontier goes, that a cycle terminates at
 * one row rather than looping, and the one condition that stops a hop.
 *
 * <p>The SDL is captured and so is the classpath: the service corpus, whose
 * {@code no.sikt.graphitron.rewrite.test.backing} package holds the shapes a closure has to get
 * right side by side. A chain three types deep, a cycle, a coordinate two producers answer
 * differently, and a field whose own producer overrides what its parent's member would say.
 *
 * <p>The table-bound population, and what a contest between populations makes of a type, are not
 * asked here. Those are stated in the module whose DDL declares them, by the fact documents
 * {@code type-backing*.graphqls} and by {@code no.sikt.graphitron.model.TypeBackingPartitionTest},
 * which also carries the partition: a graph closes over its own classpath entries only.
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
            assertThat(backing(dsl, GRAPH, "Film")).containsExactly(PKG + "Film"));
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
            assertThat(backing(dsl, GRAPH, "Language")).containsExactly(PKG + "Language");
            assertThat(backing(dsl, GRAPH, "Country")).containsExactly(PKG + "Country");
            assertThat(backing(dsl, GRAPH, "Actor"))
                .as("and through a container, the hop having peeled it")
                .containsExactly(PKG + "Actor");
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
            assertThat(dsl.fetchCount(GRAPHITRON_TYPE_BACKING,
                GRAPHITRON_TYPE_BACKING.GRAPH_NAME.eq(GRAPH)
                    .and(GRAPHITRON_TYPE_BACKING.TYPE_NAME.eq("Film"))))
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
            assertThat(backing(dsl, GRAPH, "Review")).containsExactly(PKG + "ReviewDto"));
    }

    /**
     * The condition is the applied directive and not its resolution. {@code Film.rating} names a
     * service class no classpath entry declares, so no reference of it resolves and the reading
     * has nothing to say; the author still said the value comes from that method. Reading the resolution
     * would have backed {@code Rating} off the parent record's same-named member, a class the author
     * never named, so the type is unbacked here and that is the answer the walk gives too.
     */
    @Test
    void aProducerReferenceTheCensusNeverReachedStillStopsTheHop() {
        withCapturedStore(dsl -> assertThat(backing(dsl, GRAPH, "Rating")).isEmpty());
    }

    /**
     * The case that separates reading the entries from reading their decode. {@code Film.score}
     * carries a {@code @service} written with a class and no method, which the reference relation
     * drops for want of a method name even though the class was read. The application is
     * still there, so the hop is still not an edge, and the parent record's {@code score} member
     * does not back the type.
     */
    @Test
    void aProducerApplicationMissingItsMethodStillStopsTheHop() {
        withCapturedStore(dsl -> assertThat(backing(dsl, GRAPH, "Score")).isEmpty());
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
                .containsExactly(PKG + "Left", PKG + "Right"));
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
                .containsExactly(PKG + "FilmFilter"));
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
                .containsExactly(PKG + "NestedFilter"));
    }

    // ===== What the closure does not reach =====

    /**
     * A type bound by {@code @table} seeds nothing here, that population being the table binding's
     * own. The classes it would seed are the generated jOOQ records the classpath reading skips by
     * design, so the answer comes from the catalog or from nowhere, and is a {@code BOUND_TABLE}
     * row beside the closure's rather than one of them.
     */
    @Test
    void aTableBoundTypeIsNotSomethingTheClosureSeeds() {
        withCapturedStore(dsl -> assertThat(backing(dsl, GRAPH, "Tabled")).isEmpty());
    }

    // ===== Which rows a producer grounded =====

    /**
     * The {@code PRODUCER} rows are the groundings and nothing else: a producer's return on one
     * axis, the class feeding an argument on the other. A type only a hop reaches has none even
     * though the closure backs it, its row saying {@code ACCESSOR}.
     */
    @Test
    void aSeedIsAGroundingAndAHopIsNot() {
        withCapturedStore(dsl -> {
            assertThat(seeds(dsl, GRAPH, "Film")).containsExactly(PKG + "Film");
            assertThat(seeds(dsl, GRAPH, "FilmFilter")).containsExactly(PKG + "FilmFilter");
            assertThat(backing(dsl, GRAPH, "Country")).containsExactly(PKG + "Country");
            assertThat(seeds(dsl, GRAPH, "Country"))
                .as("two hops deep, so backed and not grounded").isEmpty();
        });
    }

    /**
     * The contest the provenance column exists to let a reader settle. A type a producer grounds
     * and a member of another type also delivers is two rows, and the column says which of them a
     * producer answered for. That matters rather than being a tie-break: the hop reads the parent's
     * member type without checking it against the child's own grounding, so the class it lands on
     * can be wrong and not merely second. The precedence stays the reader's, which is why the
     * relation keeps both rows.
     */
    @Test
    void aTypeAProducerGroundsIsToldApartFromWhatAHopReached() {
        withCapturedStore(dsl -> {
            assertThat(backing(dsl, GRAPH, "Grounded"))
                .containsExactly(PKG + "GroundedDto", PKG + "GroundedRecord");
            assertThat(seeds(dsl, GRAPH, "Grounded")).containsExactly(PKG + "GroundedDto");
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
            assertThat(backing(dsl, GRAPH, "Carrier")).containsExactly(PKG + "Carrier"));
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

    // ===== Helpers =====

    private static final String GRAPH = CapturedStore.GRAPH;

    /** The corpus package the classes below live in. */
    private static final String PKG = "no.sikt.graphitron.rewrite.test.backing.";

    /**
     * One chain three types deep, one cycle, one coordinate two producers answer differently, one
     * field whose own producer overrides its parent's member, one scalar producer and one object
     * nothing reaches. One type carries {@code @table} against the test catalog, which is the
     * population the closure never seeds. Two more fields carry a producer the derived relations
     * above the entries drop, one naming a class no reading found and one written without a method,
     * and the parent record delivers a different class at each of their names.
     */
    private static final String SDL = """
        type Query {
            films: [Film] @service(service: {className: "%1$sFilmService", method: "findAll"})
            count: Int @service(service: {className: "%1$sFilmService", method: "count"})
            node: Node @service(service: {className: "%1$sFilmService", method: "node"})
            contested: Contested @service(service: {className: "%1$sFilmService", method: "left"})
            also: Contested @service(service: {className: "%1$sFilmService", method: "right"})
            one: Carrier @service(service: {className: "%1$sFilmService", method: "one"})
            search(filter: FilmFilter): [Film] @service(
                service: {className: "%1$sFilmService", method: "search"})
            grounded: Grounded @service(
                service: {className: "%1$sFilmService", method: "grounded"})
        }
        type Film {
            title: String
            language: Language
            actors: [Actor]
            reviews: [Review] @service(service: {className: "%1$sReviewService", method: "forFilm"})
            rating: Rating @service(service: {className: "%1$sRatingService", method: "forFilm"})
            score: Score @service(service: {className: "%1$sScoreService"})
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
        type Rating { stars: Int }
        type Score { value: Int }
        type Carrier { id: ID }
        type Orphan { id: ID }
        type Grounded { id: ID }
        interface Node { id: ID }
        input FilmFilter { title: String, nested: NestedFilter }
        input NestedFilter { code: String }
        """.formatted(PKG);

    /**
     * Every class the closure backs the named type with, in name order so a case can state the
     * whole answer.
     */
    private static List<String> backing(DSLContext dsl, String graphName, String typeName) {
        var b = GRAPHITRON_TYPE_BACKING;
        return dsl.selectDistinct(b.CLASS_NAME)
            .from(b)
            .where(b.GRAPH_NAME.eq(graphName))
            .and(b.TYPE_NAME.eq(typeName))
            .and(b.DECLARED_VIA.in("PRODUCER", "ACCESSOR"))
            .orderBy(b.CLASS_NAME)
            .fetch(0, String.class);
    }

    /** The groundings of the named type, which is the subset a producer answered for. */
    private static List<String> seeds(DSLContext dsl, String graphName, String typeName) {
        var b = GRAPHITRON_TYPE_BACKING;
        return dsl.select(b.CLASS_NAME).from(b)
            .where(b.GRAPH_NAME.eq(graphName)).and(b.TYPE_NAME.eq(typeName))
            .and(b.DECLARED_VIA.eq("PRODUCER"))
            .orderBy(b.CLASS_NAME)
            .fetch(0, String.class);
    }

    private void withCapturedStore(Consumer<DSLContext> body) {
        try (var store = CapturedStore.ofCatalogWith(tmp, GRAPH, SDL, jooq(),
                ClasspathCorpus.entries())) {
            body.accept(store.dsl());
        }
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
