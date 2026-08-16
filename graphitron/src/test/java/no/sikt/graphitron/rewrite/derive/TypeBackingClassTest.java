package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CLASS;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_type_backing_class}, the closure that answers
 * which Java class backs a graph's type, and for {@code intent_type_backing_conflict}, the types it
 * answers more than one way.
 *
 * <p>The SDL is captured for real and the census is hand-built, the split
 * {@link FieldProducerMethodTest} argues for and the same one: a directive application is a fact
 * capture produces, while a census row is a name, a descriptor and a decomposed declared type,
 * which is all these rules read. Hand-building it is also what lets one fixture hold the shapes a
 * closure has to get right side by side, a chain three types deep, a cycle, a coordinate two
 * producers answer differently, and a field whose own producer overrides what its parent's member
 * would say.
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

    // ===== Disagreement is rows =====

    /**
     * Two producers answering one type differently are two rows and a conflict, where the walk
     * suppresses the second observation to protect the first and leaves the disagreement
     * unobservable. This is the population the decomposition surfaces rather than preserves.
     */
    @Test
    void aTypeTwoProducersAnswerDifferentlyIsTwoRowsAndAConflict() {
        withCapturedStore(dsl -> {
            assertThat(backing(dsl, GRAPH, "Contested"))
                .containsExactly("app.Left", "app.Right");
            assertThat(conflict(dsl, GRAPH, "Contested"))
                .containsExactly("app.Left, app.Right 2");
        });
    }

    /** A type one answer suffices for is no conflict, the view naming the contested population only. */
    @Test
    void aTypeWithOneAnswerIsNotContested() {
        withCapturedStore(dsl -> assertThat(conflict(dsl, GRAPH, "Film")).isEmpty());
    }

    // ===== Where the two populations meet =====

    /**
     * The other arm. A type bound by {@code @table} is backed by the record class of the table it
     * resolves to, which the closure never seeds and could not reach: the census excludes the
     * generated jOOQ package by design, so this answer comes from the catalog or from nowhere.
     */
    @Test
    void aTableBoundTypeIsBackedByItsTablesRecord() {
        withCapturedStore(dsl -> {
            assertThat(coalesced(dsl, GRAPH, "Tabled"))
                .containsExactly(FILM_RECORD + " BOUND_TABLE");
            assertThat(backing(dsl, GRAPH, "Tabled"))
                .as("and the closure said nothing about it")
                .isEmpty();
        });
    }

    /** The closure's own answers arrive through the coalesce marked as the closure's. */
    @Test
    void aClosureAnswerKeepsItsProvenance() {
        withCapturedStore(dsl ->
            assertThat(coalesced(dsl, GRAPH, "Film"))
                .containsExactly("app.FilmRecord BACKING_CLOSURE"));
    }

    /**
     * A type its {@code @table} binding and the closure answer differently is two rows and a
     * conflict, not a precedence. The walk reads the table and never consults the class, which is a
     * defensible reading and still a choice; folding it in here would have recorded agreement where
     * there is none.
     */
    @Test
    void aTypeItsTableAndItsClosureAnswerDifferentlyIsContested() {
        withCapturedStore(dsl -> {
            assertThat(coalesced(dsl, GRAPH, "Language"))
                .containsExactly("app.LanguageRecord BACKING_CLOSURE",
                    LANGUAGE_RECORD + " BOUND_TABLE");
            assertThat(conflict(dsl, GRAPH, "Language"))
                .containsExactly("app.LanguageRecord, " + LANGUAGE_RECORD + " 2");
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
     * The seeds resolve through store_graph_source and the hops depart from that graph's own
     * classes, so neither graph's types are backed by the other's.
     */
    @Test
    void siblingGraphsCloseOverTheirOwnMembership() {
        withCapturedStore(dsl -> {
            capture(dsl, SIBLING, tmp.resolve("sibling"), List.of(
                reference(OTHER, "app.FilmService",
                    method("findAll", "()Ljava/util/List;",
                        ref("", "java.util.List"), ref("0", "lib.FilmDto"))),
                record(OTHER, "lib.FilmDto",
                    component("language", ref("", "lib.LangDto")))));

            assertThat(backing(dsl, SIBLING, "Film")).containsExactly("lib.FilmDto");
            assertThat(backing(dsl, SIBLING, "Language")).containsExactly("lib.LangDto");
            assertThat(backing(dsl, GRAPH, "Film")).containsExactly("app.FilmRecord");
            assertThat(backing(dsl, GRAPH, "Language")).containsExactly("app.LanguageRecord");
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "TypeBackingClassTest";
    private static final String SIBLING = "TypeBackingClassTestSibling";

    private static final String APP = "app/target/classes";
    private static final String OTHER = "other/target/classes";

    /**
     * The test catalog's own record classes, spelled out rather than read back off
     * {@code sql_table}: the table arm's claim is that it carries the table's record class, and an
     * expectation that fetched the same column would agree with any value capture happened to put
     * there.
     */
    private static final String RECORDS = "no.sikt.graphitron.rewrite.test.jooq.tables.records.";
    private static final String FILM_RECORD = RECORDS + "FilmRecord";
    private static final String LANGUAGE_RECORD = RECORDS + "LanguageRecord";

    /**
     * One chain three types deep, one cycle, one coordinate two producers answer differently, one
     * field whose own producer overrides its parent's member, one scalar producer and one object
     * nothing reaches. Two types carry {@code @table} against the test catalog: one the closure
     * never reaches, so the table arm stands alone, and one the closure reaches with a different
     * class, so the two arms meet and disagree.
     */
    private static final String SDL = """
        type Query {
            films: [Film] @service(service: {className: "app.FilmService", method: "findAll"})
            count: Int @service(service: {className: "app.FilmService", method: "count"})
            node: Node @service(service: {className: "app.FilmService", method: "node"})
            contested: Contested @service(service: {className: "app.FilmService", method: "left"})
            also: Contested @service(service: {className: "app.FilmService", method: "right"})
            one: Carrier @service(service: {className: "app.FilmService", method: "one"})
        }
        type Film {
            title: String
            language: Language
            actors: [Actor]
            reviews: [Review] @service(service: {className: "app.ReviewService", method: "forFilm"})
            related: Film
        }
        type Language @table(name: "language") {
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
        interface Node { id: ID }
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
                    ref("", "java.util.List"), ref("0", "app.CarrierRecord"))),
            reference(APP, "app.ReviewService",
                method("forFilm", "()Ljava/util/List;",
                    ref("", "java.util.List"), ref("0", "app.ReviewDto"))),
            record(APP, "app.FilmRecord",
                component("title", ref("", "java.lang.String")),
                component("language", ref("", "app.LanguageRecord")),
                component("actors", ref("", "java.util.List"), ref("0", "app.ActorRecord")),
                component("reviews", ref("", "java.util.List"), ref("0", "app.WrongRecord")),
                component("related", ref("", "app.FilmRecord"))),
            record(APP, "app.LanguageRecord",
                component("name", ref("", "java.lang.String")),
                component("country", ref("", "app.CountryRecord"))),
            record(APP, "app.CountryRecord", component("code", ref("", "java.lang.String"))),
            record(APP, "app.ActorRecord", component("name", ref("", "java.lang.String"))),
            record(APP, "app.ReviewDto", component("body", ref("", "java.lang.String"))),
            record(APP, "app.CarrierRecord", component("id", ref("", "java.lang.String"))),
            record(APP, "app.Left", component("id", ref("", "java.lang.String"))),
            record(APP, "app.Right", component("id", ref("", "java.lang.String"))),
            record(APP, "app.NodeRecord", component("id", ref("", "java.lang.String"))));
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

    /** Every backing the coalesce holds, each with the population that answered. */
    private static List<String> coalesced(DSLContext dsl, String graphName, String typeName) {
        return dsl.select(INTENT_TYPE_BACKING.CLASS_NAME, INTENT_TYPE_BACKING.DECLARED_VIA)
            .from(INTENT_TYPE_BACKING)
            .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(graphName)
                .and(INTENT_TYPE_BACKING.TYPE_NAME.eq(typeName)))
            .orderBy(INTENT_TYPE_BACKING.CLASS_NAME)
            .fetch(r -> r.value1() + " " + r.value2());
    }

    /** The canonical render and the arity together, which is the whole of what the view adds. */
    private static List<String> conflict(DSLContext dsl, String graphName, String typeName) {
        return dsl.select(INTENT_TYPE_BACKING_CONFLICT.CLASS_NAMES,
                INTENT_TYPE_BACKING_CONFLICT.CANDIDATES)
            .from(INTENT_TYPE_BACKING_CONFLICT)
            .where(INTENT_TYPE_BACKING_CONFLICT.GRAPH_NAME.eq(graphName)
                .and(INTENT_TYPE_BACKING_CONFLICT.TYPE_NAME.eq(typeName)))
            .fetch(r -> r.value1() + " " + r.value2());
    }

    private void withCapturedStore(Consumer<DSLContext> body) {
        try (var store = GraphitronModelStore.open()) {
            capture(store.dsl(), GRAPH, tmp, census());
            body.accept(store.dsl());
        }
    }

    private static void capture(DSLContext dsl, String graphName, Path root,
                                List<CompletionData.ExternalReference> census) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var schemaFile = write(root, SDL);
        var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
        FactCapture.capture(dsl, new FactCapture.GraphIdentity(graphName, root),
            FactCapture.SubjectConfig.none(), registry, TestSchemaHelper.attribution(schemaFile),
            jooq, census, new NodeDeclaration(null));
    }

    private static Path write(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
