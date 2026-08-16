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
import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_ELEMENT;
import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_TYPE_REF;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_ACCESSOR_HOP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for the three relations an accessor hop is built from:
 * {@code intent_class_member_type_ref}, the classes a member slot's declared type names;
 * {@code intent_class_member_element}, the one it delivers once the containers are peeled; and
 * {@code intent_field_accessor_hop}, where a field coordinate standing on a class lands.
 *
 * <p>The census is built reference by reference, which is the choice {@link ClassAssignableTest}
 * makes and for the same reason. Every position these rules read is a name, a path and a variance,
 * which is all a hand-built reference states, and the arrangements the rules have to get right are
 * ones no compiled fixture offers side by side: a two-level container, a map, a raw container, a
 * generic class that is not a container at all, an accessor overloaded with a parameterised twin,
 * and one slot name offered by two classes. The scan's own production of these rows is pinned in
 * {@code ClasspathScannerTest}.
 *
 * <p>The two cases under the divergence heading pin behaviour that differs from the reflective walk
 * these relations replace, in both directions. They are written as pins rather than as expectations
 * because the disagreement is real and its adjudication belongs with the shadow, not here.
 */
@PipelineTier
class AccessorHopTest {

    @TempDir
    Path tmp;

    // ===== A slot's declared type, position by position =====

    /**
     * Both arms answer under one key. A record component and a bean accessor decompose into the
     * same path grammar, and a reader standing on a slot does not have to know which produced it.
     */
    @Test
    void bothMemberArmsNameTheirPositionsUnderOneKey() {
        withCapturedStore(dsl -> {
            assertThat(positions(dsl, "app.FilmRecord", "actors"))
                .containsExactlyInAnyOrder(" java.util.List", "0 app.ActorRecord");
            assertThat(positions(dsl, "app.Store", "films"))
                .containsExactlyInAnyOrder(" java.util.List", "0 app.FilmRecord");
        });
    }

    /**
     * A slot carries its accessor's name and not its descriptor, so the bean arm has to pick among
     * same-named methods. It picks the one the slot rule itself picked, and the parameterised twin
     * declared beside it lends the slot nothing.
     */
    @Test
    void anOverloadedAccessorDoesNotLendItsPositionsToTheSlot() {
        withCapturedStore(dsl ->
            assertThat(positions(dsl, "app.Store", "title"))
                .containsExactly(" java.lang.String"));
    }

    // ===== What the slot delivers =====

    /** A slot naming a class directly delivers it, and the path says nothing was peeled. */
    @Test
    void aSlotNamingAClassDeliversItAtTheRoot() {
        withCapturedStore(dsl ->
            assertThat(delivered(dsl, "app.FilmRecord", "language"))
                .containsExactly("app.LanguageRecord at "));
    }

    /** The ordinary peel: one container, one descent, the element. */
    @Test
    void aContainerSlotDeliversItsElement() {
        withCapturedStore(dsl ->
            assertThat(delivered(dsl, "app.Store", "films"))
                .containsExactly("app.FilmRecord at 0"));
    }

    /**
     * The descent does not stop after one step. An async wrapper around a list is two containers
     * and the spine walks both, which is the case a fixed one-level peel would answer wrongly and
     * silently.
     */
    @Test
    void nestedContainersPeelUntilTheyStop() {
        withCapturedStore(dsl ->
            assertThat(delivered(dsl, "app.Store", "pending"))
                .containsExactly("app.FilmRecord at 0.0"));
    }

    /** A map delivers its value, which is the one container whose element is not the first argument. */
    @Test
    void aMapDeliversItsValue() {
        withCapturedStore(dsl ->
            assertThat(delivered(dsl, "app.Store", "byKey"))
                .containsExactly("app.FilmRecord at 1"));
    }

    /**
     * Two ways a descent stops at the root, and the relation answers with the class that is there
     * rather than with nothing: a raw container names no element position to descend to, and a
     * generic class that is not a container is not descended into at all.
     */
    @Test
    void aTypeThatNamesNoElementDeliversItself() {
        withCapturedStore(dsl -> {
            assertThat(delivered(dsl, "app.Store", "raw"))
                .containsExactly("java.util.List at ");
            assertThat(delivered(dsl, "app.Store", "boxed"))
                .containsExactly("app.Box at ");
        });
    }

    /**
     * The peel lands on a position, so it lands on that position's variance too. A list of
     * something extending Film delivers Film, and a reader that needs to know which direction the
     * values flow can still tell.
     */
    @Test
    void varianceSurvivesThePeel() {
        withCapturedStore(dsl ->
            assertThat(dsl.select(INTENT_CLASS_MEMBER_ELEMENT.ELEMENT_CLASS,
                    INTENT_CLASS_MEMBER_ELEMENT.VARIANCE)
                .from(INTENT_CLASS_MEMBER_ELEMENT)
                .where(INTENT_CLASS_MEMBER_ELEMENT.CLASS_NAME.eq("app.Store")
                    .and(INTENT_CLASS_MEMBER_ELEMENT.SLOT_NAME.eq("subset")))
                .fetch())
                .extracting(r -> r.value1() + " " + r.value2())
                .containsExactly("app.FilmRecord EXTENDS"));
    }

    /**
     * A slot whose declared type names no class at its root has no spine and so delivers nothing.
     * No filter states that: the census omits the root position of a primitive and of an array
     * alike, an array's component being the next step down and this walk never taking that step.
     */
    @Test
    void aSlotNamingNoClassAtItsRootDeliversNothing() {
        withCapturedStore(dsl -> {
            assertThat(delivered(dsl, "app.Store", "count")).isEmpty();
            assertThat(delivered(dsl, "app.Store", "tags")).isEmpty();
            assertThat(positions(dsl, "app.Store", "tags"))
                .as("the array's component is still a position, so the absence is the root's")
                .containsExactly("[] java.lang.String");
        });
    }

    // ===== Where a coordinate lands =====

    /** The hop itself: a field whose parent stands on a class lands on what that class's slot delivers. */
    @Test
    void aCoordinateHopsToWhatItsSlotDelivers() {
        withCapturedStore(dsl -> {
            var rows = hops(dsl, GRAPH, "Store", "films");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getFromClassName()).isEqualTo("app.Store");
            assertThat(rows.getFirst().getSlotName()).isEqualTo("films");
            assertThat(rows.getFirst().getToClassName()).isEqualTo("app.FilmRecord");
            assertThat(rows.getFirst().getElementPath()).isEqualTo("0");
            assertThat(rows.getFirst().getOrigin()).isEqualTo("BEAN_ACCESSOR");
        });
    }

    /**
     * The coordinate resolves through the authored name where one is written, which is the
     * resolution the emission side makes. The SDL name resolves to nothing on its own.
     */
    @Test
    void anAuthoredNameRedirectsTheSlot() {
        withCapturedStore(dsl -> {
            var rows = hops(dsl, GRAPH, "Store", "named");
            assertThat(rows).extracting(r -> r.getSlotName() + " " + r.getToClassName())
                .containsExactly("language app.LanguageRecord");
        });
    }

    /** An input-object field is the same question about the same members, so it is one population. */
    @Test
    void anInputObjectFieldHopsOnTheSameTerms() {
        withCapturedStore(dsl ->
            assertThat(hops(dsl, GRAPH, "FilmInput", "actors"))
                .extracting(r -> r.getFromClassName() + " " + r.getToClassName())
                .containsExactly("app.FilmRecord app.ActorRecord"));
    }

    /**
     * The relation is total over standing classes: it says nothing about which class a parent is
     * on, so a coordinate pairs with every class offering a slot of that name. That is what makes
     * it an edge rather than a second copy of the binding, and what the closure over it narrows.
     */
    @Test
    void oneCoordinateStandsOnEveryClassOfferingTheSlot() {
        withCapturedStore(dsl ->
            assertThat(hops(dsl, GRAPH, "Film", "title"))
                .extracting(r -> r.getFromClassName())
                .containsExactlyInAnyOrder("app.FilmRecord", "app.Store"));
    }

    // ===== Divergence from the walk, pinned in both directions =====

    /**
     * An SDL field's arguments are not read, so an argument-taking field hops through a
     * no-argument accessor of the same name. The walk probes for an accessor whose parameters
     * match the arguments and finds none here, so this row is one the walk does not produce.
     */
    @Test
    void anArgumentTakingFieldStillHopsThroughTheNoArgumentSlot() {
        withCapturedStore(dsl ->
            assertThat(hops(dsl, GRAPH, "Store", "byKey"))
                .extracting(r -> r.getToClassName())
                .containsExactly("app.FilmRecord"));
    }

    /**
     * The same difference the other way. A parameterised accessor is no slot, so the coordinate
     * whose arguments it takes lands nowhere, where the walk's probe would match it.
     */
    @Test
    void aParameterisedAccessorIsNoSlotAndSoNoHop() {
        withCapturedStore(dsl -> assertThat(hops(dsl, GRAPH, "Store", "lookup")).isEmpty());
    }

    // ===== Partition =====

    /**
     * The hop reaches the census through store_graph_source, so a sibling graph that read a
     * different classpath entry lands on that entry's classes and never on this one's, even where
     * both entries offer the same slot name.
     */
    @Test
    void siblingGraphsHopThroughTheirOwnMembership() {
        withCapturedStore(dsl -> {
            capture(dsl, SIBLING, tmp.resolve("sibling"),
                List.of(reference(LIB, "lib.Catalog",
                    method("getFilms", "()Ljava/util/List;",
                        ref("", "java.util.List"), ref("0", "lib.FilmDto")))));

            assertThat(hops(dsl, SIBLING, "Store", "films"))
                .extracting(r -> r.getFromClassName() + " " + r.getToClassName())
                .containsExactly("lib.Catalog lib.FilmDto");
            assertThat(hops(dsl, GRAPH, "Store", "films"))
                .extracting(r -> r.getToClassName())
                .containsExactly("app.FilmRecord");
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "AccessorHopTest";
    private static final String SIBLING = "AccessorHopTestSibling";

    private static final String APP = "app/target/classes";
    private static final String LIB = "lib.jar";

    /**
     * Only the coordinates the hop cases need. The delivery rules are asked of the census
     * directly, being facts about a class rather than about any graph.
     */
    private static final String SDL = """
        type Query {
            store: Store
            save(input: FilmInput): Film
        }
        type Store {
            films: [Film]
            count: Int
            tags: [String]
            named: Film @field(name: "language")
            byKey(key: String): Film
            lookup(id: ID): Film
        }
        type Film {
            title: String
        }
        input FilmInput {
            actors: [ActorInput]
        }
        input ActorInput {
            name: String
        }
        """;

    /**
     * One record and one class on one entry. The record carries the components the input axis and
     * the authored-name case read; the class carries one accessor per delivery shape, plus the
     * overloaded pair and the parameterised accessor the divergence cases stand on.
     */
    private static List<CompletionData.ExternalReference> census() {
        return List.of(
            record(APP, "app.FilmRecord",
                component("title", ref("", "java.lang.String")),
                component("language", ref("", "app.LanguageRecord")),
                component("actors", ref("", "java.util.List"), ref("0", "app.ActorRecord"))),
            reference(APP, "app.Store",
                method("getFilms", "()Ljava/util/List;",
                    ref("", "java.util.List"), ref("0", "app.FilmRecord")),
                method("getPending", "()Ljava/util/concurrent/CompletableFuture;",
                    ref("", "java.util.concurrent.CompletableFuture"),
                    ref("0", "java.util.List"), ref("0.0", "app.FilmRecord")),
                method("getByKey", "()Ljava/util/Map;",
                    ref("", "java.util.Map"), ref("0", "java.lang.String"),
                    ref("1", "app.FilmRecord")),
                method("getRaw", "()Ljava/util/List;", ref("", "java.util.List")),
                method("getBoxed", "()Lapp/Box;",
                    ref("", "app.Box"), ref("0", "app.FilmRecord")),
                method("getSubset", "()Ljava/util/List;",
                    ref("", "java.util.List"),
                    new CompletionData.TypeRef("0", "app.FilmRecord", "EXTENDS")),
                method("getCount", "()I"),
                method("getTags", "()[Ljava/lang/String;", ref("[]", "java.lang.String")),
                method("getTitle", "()Ljava/lang/String;", ref("", "java.lang.String")),
                parameterised("getTitle", "(I)Lapp/LanguageRecord;", "int",
                    ref("", "app.LanguageRecord")),
                parameterised("getLookup", "(Ljava/lang/String;)Lapp/FilmRecord;", "String",
                    ref("", "app.FilmRecord"))));
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

    /** A no-argument accessor, which is the only shape a member slot is read from. */
    private static CompletionData.Method method(
        String name, String descriptor, CompletionData.TypeRef... refs) {
        return new CompletionData.Method(name, "Object", "", List.of(), false, descriptor,
            "Object", List.of(refs));
    }

    /** An accessor that takes something, which is exactly what keeps it from being a slot. */
    private static CompletionData.Method parameterised(
        String name, String descriptor, String paramType, CompletionData.TypeRef... refs) {
        return new CompletionData.Method(name, "Object", "",
            List.of(new CompletionData.Parameter("arg", paramType, "Arg", "")),
            false, descriptor, "Object", List.of(refs));
    }

    private static CompletionData.RecordComponent component(
        String name, CompletionData.TypeRef... refs) {
        return new CompletionData.RecordComponent(name, "Object", "Object", List.of(refs));
    }

    private static CompletionData.TypeRef ref(String path, String referencedClass) {
        return new CompletionData.TypeRef(path, referencedClass, "NONE");
    }

    /** Each position as its path and the class named there, so a case states the whole decomposition. */
    private static List<String> positions(DSLContext dsl, String className, String slotName) {
        return dsl.select(INTENT_CLASS_MEMBER_TYPE_REF.TYPE_PATH,
                INTENT_CLASS_MEMBER_TYPE_REF.REFERENCED_CLASS)
            .from(INTENT_CLASS_MEMBER_TYPE_REF)
            .where(INTENT_CLASS_MEMBER_TYPE_REF.CLASS_NAME.eq(className)
                .and(INTENT_CLASS_MEMBER_TYPE_REF.SLOT_NAME.eq(slotName)))
            .fetch(r -> r.value1() + " " + r.value2());
    }

    /** The delivered class and the position it was read at, the answer and its evidence together. */
    private static List<String> delivered(DSLContext dsl, String className, String slotName) {
        return dsl.select(INTENT_CLASS_MEMBER_ELEMENT.ELEMENT_CLASS,
                INTENT_CLASS_MEMBER_ELEMENT.ELEMENT_PATH)
            .from(INTENT_CLASS_MEMBER_ELEMENT)
            .where(INTENT_CLASS_MEMBER_ELEMENT.CLASS_NAME.eq(className)
                .and(INTENT_CLASS_MEMBER_ELEMENT.SLOT_NAME.eq(slotName)))
            .fetch(r -> r.value1() + " at " + r.value2());
    }

    private static List<no.sikt.graphitron.model.tables.records.IntentFieldAccessorHopRecord>
        hops(DSLContext dsl, String graphName, String typeName, String fieldName) {
        return dsl.selectFrom(INTENT_FIELD_ACCESSOR_HOP)
            .where(INTENT_FIELD_ACCESSOR_HOP.GRAPH_NAME.eq(graphName)
                .and(INTENT_FIELD_ACCESSOR_HOP.TYPE_NAME.eq(typeName))
                .and(INTENT_FIELD_ACCESSOR_HOP.FIELD_NAME.eq(fieldName)))
            .fetch();
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
