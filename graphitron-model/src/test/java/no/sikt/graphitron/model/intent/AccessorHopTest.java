package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.tables.records.IntentFieldAccessorHopRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.CODE_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_SLOT;
import static no.sikt.graphitron.model.Tables.INTENT_DELIVERY_CONTAINER;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_ACCESSOR_HOP;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedConnectionCarrier;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedRecordComponent;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for the three relations an accessor hop is built from:
 * {@code intent_delivery_container}, the classes a declared type delivers through;
 * {@code code_type_slot}, the member names a class offers and the accessor each is read by; and
 * {@code intent_field_accessor_hop}, where a field coordinate standing on a class lands.
 *
 * <p>One peel now, where there were two. The reading peels a declaration as it reads it, keyed by
 * the type and bounded by nothing, and a slot reaches it through the accessor's own result; the
 * view that peeled the same declaration at its owner, unrolled to a fixed depth because SQL has no
 * loop, is gone rather than kept beside it. What that peel does is pinned where the reading is, in
 * {@code no.sikt.graphitron.model.capture.code.CodeCaptureTest}, against compiled bytes rather
 * than against stated positions.
 *
 * <p>Every input is stated as rows. A census position is a name, a path and a variance, which is
 * all these rules read of one, and the arrangements they have to get right are ones no compiled
 * fixture offers side by side: a two-level container, a map, a raw container, a generic class that
 * is not a container at all, an accessor overloaded with a parameterised twin, and one slot name
 * offered by two classes. The scan's own
 * production of these rows is pinned beside the scan, in
 * {@code no.sikt.graphitron.rewrite.catalog.ClasspathScannerTest}.
 *
 * <p>The classes are stated whole rather than trimmed to what each case reads, because three of
 * these rules are joins whose own key is what a case can get wrong. Two records offering a
 * component of one name, two classpath entries offering an accessor of one name, and one class
 * offering three accessors of one descriptor are what make a peel that dropped a key part answer
 * differently from one that kept it.
 *
 * <p>The two cases under the divergence heading pin behaviour that differs from the reflective walk
 * these relations replace, in both directions. They are written as pins rather than as expectations
 * because the disagreement is real and its adjudication belongs with the shadow, not here.
 */
class AccessorHopTest {

    // ===== The vocabulary the peel descends =====

    /**
     * The container set is a relation because the peel reads it twice, once to descend and once to
     * ask whether descending is possible at all. Pinned as a whole rather than by sampling: which
     * classes are containers is the rule the peel turns on, and a row silently added or dropped
     * changes what every hop lands on.
     *
     * <p>Asked of an empty store, the vocabulary being named data and not a function of any census.
     */
    @Test
    void theContainerVocabularyIsTheOneTheGeneratorMeets() {
        withSeededStore(dsl ->
            assertThat(dsl.select(INTENT_DELIVERY_CONTAINER.CONTAINER_CLASS,
                    INTENT_DELIVERY_CONTAINER.ELEMENT_INDEX)
                .from(INTENT_DELIVERY_CONTAINER)
                .fetch(r -> r.value1() + " at " + r.value2()))
                .containsExactlyInAnyOrder(
                    "java.util.List at 0",
                    "java.util.Set at 0",
                    "java.util.Collection at 0",
                    "java.util.Optional at 0",
                    "java.util.concurrent.CompletableFuture at 0",
                    "org.jooq.Result at 0",
                    "java.util.Map at 1"));
    }

    /**
     * A container either multiplies the delivery or is transparent to it, and the map is the case
     * worth pinning: a map from a key to one value delivers one, so the map itself decides nothing
     * and only what sits at its value position does.
     */
    @Test
    void aCollectionMultipliesTheDeliveryAndAWrapperDoesNot() {
        withSeededStore(dsl ->
            assertThat(dsl.select(INTENT_DELIVERY_CONTAINER.CONTAINER_CLASS)
                .from(INTENT_DELIVERY_CONTAINER)
                .where(INTENT_DELIVERY_CONTAINER.MULTIPLIES.isTrue())
                .fetch(0, String.class))
                .containsExactlyInAnyOrder("java.util.List", "java.util.Set",
                    "java.util.Collection", "org.jooq.Result"));
    }

    // ===== A declared type under the method that declares it =====

    /**
     * The descriptor holds an overload apart, which is the whole reason a method is keyed by one.
     * Two methods of one name resolve to two types rather than to one method's confused answer, and
     * a reader with only a name to go on would have had to pick.
     */
    @Test
    void overloadsAreToldApartByTheirDescriptor() {
        withCensus(dsl -> {
            assertThat(resultTypeOf(dsl, STORE, "getTitle", "()Ljava/lang/String;"))
                .isEqualTo("java.lang.String");
            assertThat(resultTypeOf(dsl, STORE, "getTitle", SPOKEN_TITLE))
                .isEqualTo("app.LanguageRecord");
        });
    }

    // ===== A method that is no slot =====

    /**
     * A method that offers no member slot is read like any other and offers nothing. The pair is
     * worth stating together: what it declares is on record, and the slot relation still says
     * nothing about it, so the absence downstream is the slot rule's and not the reading's.
     */
    @Test
    void aMethodThatIsNoSlotIsStillRead() {
        withCensus(dsl -> {
            assertThat(resultTypeOf(dsl, STORE, "getLookup", LOOKUP))
                .isEqualTo("app.FilmRecord");
            assertThat(delivered(dsl, STORE, "lookup"))
                .as("and it is still no slot, so the member view says nothing about it")
                .isEmpty();
        });
    }

    // ===== What the slot delivers =====

    /**
     * A slot carries its accessor's name and not its descriptor, so the member view has to pick
     * among same-named owners. It picks the one the slot rule itself picked, by the absence of
     * parameter rows, and the parameterised twin declared beside it lends the slot nothing: not its
     * return, and not the parameter that is what keeps it from being a slot, that parameter being a
     * declared type peeled under the twin's own name.
     */
    @Test
    void anOverloadedAccessorDoesNotLendItsReturnToTheSlot() {
        withCensus(dsl ->
            assertThat(delivered(dsl, STORE, "title"))
                .containsExactly("java.lang.String"));
    }

    /**
     * A slot naming a class directly delivers it, and the path says nothing was peeled. The record
     * declares an accessor of the component's own name beside it, as every record does, and the
     * slot resolves to the component rather than to both.
     */
    @Test
    void aSlotNamingAClassDeliversItAtTheRoot() {
        withCensus(dsl ->
            assertThat(delivered(dsl, FILM, "language"))
                .containsExactly("app.LanguageRecord"));
    }

    /** The ordinary peel: one container, one descent, the element. */
    @Test
    void aContainerSlotDeliversItsElement() {
        withCensus(dsl ->
            assertThat(delivered(dsl, STORE, "films"))
                .containsExactly("app.FilmRecord"));
    }

    /**
     * A class name is not an identity on its own: the census keys by classpath entry first, so one
     * name declared on two entries is two classes and each answers with its own element. A
     * workspace holding both a module's output and a jar built from an older copy of it is the
     * ordinary way this arises, and folding the two would answer a question about one entry's class
     * with the other's contents.
     *
     * <p>Both arms of the member view, because each states the entry key for itself.
     */
    @Test
    void oneClassNameOnTwoEntriesIsTwoClasses() {
        withCensus(dsl -> {
            assertThat(delivered(dsl, LEGACY, "cast"))
                .as("a component declared on both")
                .containsExactlyInAnyOrder("app.ActorRecord", "lib.CastDto");
            assertThat(delivered(dsl, LEGACY_STORE, "cast"))
                .as("an accessor declared on both")
                .containsExactlyInAnyOrder("app.ActorRecord", "lib.CastDto");
        });
    }

    /**
     * The descent does not stop after one step. An async wrapper around a list is two containers
     * and the spine walks both, which is the case a fixed one-level peel would answer wrongly and
     * silently.
     */
    @Test
    void nestedContainersPeelUntilTheyStop() {
        withCensus(dsl ->
            assertThat(delivered(dsl, STORE, "pending"))
                .containsExactly("app.FilmRecord"));
    }

    /** A map delivers its value, which is the one container whose element is not the first argument. */
    @Test
    void aMapDeliversItsValue() {
        withCensus(dsl ->
            assertThat(delivered(dsl, STORE, "byKey"))
                .containsExactly("app.FilmRecord"));
    }

    /**
     * Two ways a descent stops at the root, and the relation answers with the class that is there
     * rather than with nothing: a raw container names no element position to descend to, and a
     * generic class that is not a container is not descended into at all.
     */
    @Test
    void aTypeThatNamesNoElementDeliversItself() {
        withCensus(dsl -> {
            assertThat(delivered(dsl, STORE, "raw"))
                .containsExactly("java.util.List");
            assertThat(delivered(dsl, STORE, "boxed"))
                .containsExactly("app.Box");
        });
    }

    /**
     * A slot whose declared type names no class at its root has no spine and so delivers nothing.
     * No filter states that: the reading records no root for a primitive and for an array alike,
     * an array's component being the next step down and the peel never taking that step.
     */
    @Test
    void aSlotNamingNoClassAtItsRootDeliversNothing() {
        withCensus(dsl -> {
            assertThat(delivered(dsl, STORE, "count")).isEmpty();
            assertThat(delivered(dsl, STORE, "tags")).isEmpty();
            assertThat(resultTypeOf(dsl, STORE, "getTags", "()[Ljava/lang/String;"))
                .as("while the spelling still names the component, so the absence is the root's")
                .isEqualTo("java.lang.String[]");
        });
    }

    // ===== Where a coordinate lands =====

    /** The hop itself: a field whose parent stands on a class lands on what that class's slot delivers. */
    @Test
    void aCoordinateHopsToWhatItsSlotDelivers() {
        withCensus(dsl -> {
            var rows = hops(dsl, GRAPH, "Store", "films");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getFromClassName()).isEqualTo(STORE);
            assertThat(rows.getFirst().getSlotName()).isEqualTo("films");
            assertThat(rows.getFirst().getAccessorMethodName())
                .as("the declaration a jump to the member's own source lands on")
                .isEqualTo("getFilms");
            assertThat(rows.getFirst().getToClassName()).isEqualTo(FILM);
        });
    }

    /**
     * The coordinate resolves through the authored name where one is written, which is the
     * resolution the emission side makes. The SDL name resolves to nothing on its own.
     */
    @Test
    void anAuthoredNameRedirectsTheSlot() {
        withCensus(dsl -> {
            var rows = hops(dsl, GRAPH, "Store", "named");
            assertThat(rows).extracting(r -> r.getSlotName() + " " + r.getToClassName())
                .containsExactly("language app.LanguageRecord");
        });
    }

    /**
     * An input-object field resolves against what a class can be filled with, not against what it
     * can be read for. A record is where the two coincide: a component is passed to make one and
     * read off one, so both axes reach it, and the two records offering the slot are both standing
     * classes here exactly as they are on the output side.
     */
    @Test
    void anInputObjectFieldHopsThroughWhatFillsTheMember() {
        withCensus(dsl ->
            assertThat(hops(dsl, GRAPH, "FilmInput", "actors"))
                .extracting(r -> r.getFromClassName() + " " + r.getToClassName())
                .containsExactlyInAnyOrder(
                    "app.FilmRecord app.ActorRecord",
                    "app.ShowRecord app.CastRecord"));
    }

    /**
     * And where they do not coincide, the two axes part. A class offering an accessor and no way to
     * be filled answers an output coordinate of that name and no input one, which is the whole
     * reason the two are separate arms: the emitter writes a member through a constructor argument
     * or a setter, so a store that answered an input coordinate from an accessor would promise a
     * binding the emitter then refuses.
     */
    @Test
    void aClassThatCanOnlyBeReadAnswersNoInputCoordinate() {
        withCensus(dsl -> {
            assertThat(hops(dsl, GRAPH, "Store", "films"))
                .as("the accessor answers the output coordinate")
                .extracting(IntentFieldAccessorHopRecord::getFromClassName)
                .contains(STORE);
            assertThat(hops(dsl, GRAPH, "FilmInput", "films"))
                .as("and nothing fills a member of that name on it, so the input coordinate is"
                    + " unanswered rather than answered from the accessor")
                .extracting(IntentFieldAccessorHopRecord::getFromClassName)
                .doesNotContain(STORE);
        });
    }

    /**
     * The relation is total over standing classes: it says nothing about which class a parent is
     * on, so a coordinate pairs with every class offering a slot of that name. That is what makes
     * it an edge rather than a second copy of the binding, and what the closure over it narrows.
     */
    @Test
    void oneCoordinateStandsOnEveryClassOfferingTheSlot() {
        withCensus(dsl ->
            assertThat(hops(dsl, GRAPH, "Film", "title"))
                .extracting(IntentFieldAccessorHopRecord::getFromClassName)
                .containsExactlyInAnyOrder(FILM, STORE));
    }

    // ===== Divergence from the walk, pinned in both directions =====

    /**
     * An SDL field's arguments are not read, so an argument-taking field hops through a
     * no-argument accessor of the same name. The walk probes for an accessor whose parameters
     * match the arguments and finds none here, so this row is one the walk does not produce.
     */
    @Test
    void anArgumentTakingFieldStillHopsThroughTheNoArgumentSlot() {
        withCensus(dsl ->
            assertThat(hops(dsl, GRAPH, "Store", "byKey"))
                .extracting(IntentFieldAccessorHopRecord::getToClassName)
                .containsExactly(FILM));
    }

    /**
     * The same difference the other way. A parameterised accessor is no slot, so the coordinate
     * whose arguments it takes lands nowhere, where the walk's probe would match it.
     */
    @Test
    void aParameterisedAccessorIsNoSlotAndSoNoHop() {
        withCensus(dsl -> assertThat(hops(dsl, GRAPH, "Store", "lookup")).isEmpty());
    }

    // ===== Partition =====

    /**
     * The hop reaches the census through store_graph_source, so a sibling graph that read a
     * different classpath entry lands on that entry's classes and never on this one's, even where
     * both entries offer the same slot name under the same accessor.
     */
    @Test
    void siblingGraphsHopThroughTheirOwnMembership() {
        withCensus(dsl -> {
            assertThat(hops(dsl, SIBLING, "Store", "films"))
                .extracting(r -> r.getFromClassName() + " " + r.getToClassName())
                .containsExactly("lib.Catalog lib.FilmDto");
            assertThat(hops(dsl, GRAPH, "Store", "films"))
                .extracting(IntentFieldAccessorHopRecord::getToClassName)
                .containsExactly(FILM);
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String SIBLING = "sibling";

    private static final String APP = "app/target/classes";
    private static final String LIB = "lib.jar";

    private static final String STORE = "app.Store";
    private static final String FILM = "app.FilmRecord";
    private static final String SHOW = "app.ShowRecord";
    private static final String LEGACY = "app.Legacy";
    private static final String LEGACY_STORE = "app.LegacyStore";

    private static final String LIST = "()Ljava/util/List;";
    private static final String MAP = "()Ljava/util/Map;";
    private static final String FUTURE = "()Ljava/util/concurrent/CompletableFuture;";
    private static final String LOOKUP = "(Ljava/lang/String;)Lapp/FilmRecord;";
    private static final String SPOKEN_TITLE = "(Lapp/LanguageRecord;)Lapp/LanguageRecord;";
    private static final String SEARCH =
        "(Ljava/util/List;Lapp/LanguageRecord;Ljava/util/List;)Lapp/FilmRecord;";

    // ===== The expanded population =====

    /**
     * A minted type's field is a field coordinate, and this relation is total over coordinates. The
     * expansion mints {@code <Carrier>Edge} with a {@code node} field naming the element type, and
     * whichever class carries a {@code node} slot is a class that coordinate might stand on, on the
     * same terms as any authored coordinate. Nothing about a coordinate's provenance is a condition
     * this relation states, so a minted one having no rows would be an exception it does not
     * declare.
     *
     * <p>Written against its own store rather than the census above, because the subject is which
     * population the rule reads and not which class wins: one authored coordinate and one minted
     * coordinate, both naming a slot the one class offers, and the two have to answer alike.
     *
     * <p>The authored half is not scenery. It is what separates a rule that reads the expanded
     * population from a store that simply has no rows to find: if both halves come back empty the
     * case is broken rather than passing, and the assertion says so by naming both.
     */
    @Test
    void aMintedTypesFieldHopsOnTheSameTermsAsAnAuthoredOne() {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, APP, "DIRECTORY");
            seedGraphSource(dsl, GRAPH, APP);
            seedClass(dsl, APP, FILM, "RECORD");
            seedRecordComponent(dsl, APP, FILM, "node", Map.of("", "app.LanguageRecord"));
            seedMethod(dsl, APP, FILM, "node", "()Lapp/LanguageRecord;",
                Map.of("", "app.LanguageRecord"));

            seedType(dsl, GRAPH, "Film", "OBJECT");
            seedField(dsl, GRAPH, "Store", "node", "Film", false);
            // The carrier the mint is coined under. It has to be a field the author wrote, the
            // minted rows keying into the transcription, which is what says minting is single level.
            seedField(dsl, GRAPH, "Store", "films", "Film", true);

            // One application, and FilmEdge with its node field follow from it: the edge is the
            // connection's name and a suffix, and its node is what the carrier pages over.
            seedConnectionCarrier(dsl, GRAPH, "Store", "films", "Film");

            assertThat(hops(dsl, GRAPH, "Store", "node"))
                .as("the authored coordinate, which is the control: empty here means the case is"
                    + " broken rather than that the minted half is fine")
                .extracting(r -> r.getFromClassName() + " " + r.getToClassName())
                .containsExactly(FILM + " app.LanguageRecord");

            assertThat(hops(dsl, GRAPH, "FilmEdge", "node"))
                .as("the minted coordinate, on the same slot and the same class")
                .extracting(r -> r.getFromClassName() + " " + r.getToClassName())
                .containsExactly(FILM + " app.LanguageRecord");
        });
    }

    /**
     * Two classpath entries, one per graph, and the coordinates the hop cases depart from. The
     * delivery rules are asked of the census directly, being facts about a class rather than about
     * any graph.
     */
    private static void withCensus(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, APP, "DIRECTORY");
            seedGraphSource(dsl, GRAPH, APP);
            seedRecords(dsl);
            seedStoreClass(dsl);
            seedSiblingEntry(dsl);
            seedCoordinates(dsl);
            body.accept(dsl);
        });
    }

    /**
     * Two records, each offering a component the other does not and one they share. The shared name
     * is what a peel or a member join that lost its class key answers wrongly: without it one
     * record's component descends into the other's element and both slots deliver both classes.
     *
     * <p>Each record also declares the accessors a record declares for its own components, under
     * the component's own name. They are the same names under a different owner kind, which is what
     * the member view's record arm has to tell apart, and no bean slot comes of them.
     */
    private static void seedRecords(DSLContext dsl) {
        seedClass(dsl, APP, FILM, "RECORD");
        seedRecordComponent(dsl, APP, FILM, "title", Map.of("", "java.lang.String"));
        seedRecordComponent(dsl, APP, FILM, "language", Map.of("", "app.LanguageRecord"));
        seedRecordComponent(dsl, APP, FILM, "actors",
            Map.of("", "java.util.List", "0", "app.ActorRecord"));
        seedMethod(dsl, APP, FILM, "title", "()Ljava/lang/String;", Map.of("", "java.lang.String"));
        seedMethod(dsl, APP, FILM, "language", "()Lapp/LanguageRecord;",
            Map.of("", "app.LanguageRecord"));
        seedMethod(dsl, APP, FILM, "actors", LIST,
            Map.of("", "java.util.List", "0", "app.ActorRecord"));

        seedClass(dsl, APP, SHOW, "RECORD");
        seedRecordComponent(dsl, APP, SHOW, "actors",
            Map.of("", "java.util.List", "0", "app.CastRecord"));
        seedMethod(dsl, APP, SHOW, "actors", LIST,
            Map.of("", "java.util.List", "0", "app.CastRecord"));

        // This entry's copies of two classes the sibling entry also declares, under the same names
        // and offering the same slot, one per arm of the member view.
        seedClass(dsl, APP, LEGACY, "RECORD");
        seedRecordComponent(dsl, APP, LEGACY, "cast",
            Map.of("", "java.util.List", "0", "app.ActorRecord"));
        seedMethod(dsl, APP, LEGACY, "cast", LIST,
            Map.of("", "java.util.List", "0", "app.ActorRecord"));
        seedClass(dsl, APP, LEGACY_STORE, "CLASS");
        seedMethod(dsl, APP, LEGACY_STORE, "getCast", LIST,
            Map.of("", "java.util.List", "0", "app.ActorRecord"));
    }

    /**
     * One accessor per delivery shape, plus the overloaded pair and the parameterised accessor the
     * divergence cases stand on. The shapes are still one each because a hop lands on what the
     * accessor's result delivers, so a collection, a wrapper, a map and a raw container are four
     * different landings. Three of them share the one list descriptor, which is what an owner key
     * reduced to a name and a descriptor would confuse.
     */
    private static void seedStoreClass(DSLContext dsl) {
        seedClass(dsl, APP, STORE, "CLASS");
        seedMethod(dsl, APP, STORE, "getFilms", LIST, Map.of("", "java.util.List", "0", FILM));
        seedMethod(dsl, APP, STORE, "getPending", FUTURE,
            Map.of("", "java.util.concurrent.CompletableFuture", "0", "java.util.List", "0.0", FILM));
        seedMethod(dsl, APP, STORE, "getByKey", MAP,
            Map.of("", "java.util.Map", "0", "java.lang.String", "1", FILM));
        seedMethod(dsl, APP, STORE, "getRaw", LIST, Map.of("", "java.util.List"));
        seedMethod(dsl, APP, STORE, "getBoxed", "()Lapp/Box;", Map.of("", "app.Box", "0", FILM));
        seedMethod(dsl, APP, STORE, "getCount", "()I");
        seedMethod(dsl, APP, STORE, "getTags", "()[Ljava/lang/String;",
            Map.of("[]", "java.lang.String"));

        seedMethod(dsl, APP, STORE, "getTitle", "()Ljava/lang/String;",
            Map.of("", "java.lang.String"));
        seedMethod(dsl, APP, STORE, "getTitle", SPOKEN_TITLE, Map.of("", "app.LanguageRecord"));
        seedMethodParameter(dsl, APP, STORE, "getTitle", SPOKEN_TITLE, 0,
            Map.of("", "app.LanguageRecord"));
        seedMethod(dsl, APP, STORE, "getLookup", LOOKUP, Map.of("", FILM));
        seedMethodParameter(dsl, APP, STORE, "getLookup", LOOKUP, 0, Map.of("", "java.lang.String"));

        seedMethod(dsl, APP, STORE, "search", SEARCH, Map.of("", FILM));
        seedMethodParameter(dsl, APP, STORE, "search", SEARCH, 0,
            Map.of("", "java.util.List", "0", "java.lang.String"));
        seedMethodParameter(dsl, APP, STORE, "search", SEARCH, 1,
            Map.of("", "app.LanguageRecord"));
        seedMethodParameter(dsl, APP, STORE, "search", SEARCH, 2,
            Map.of("", "java.util.List", "0", "app.LanguageRecord"));
    }

    /**
     * A second graph over a second entry, whose one class offers the same slot under the same
     * accessor name and descriptor as {@code app.Store} does, and whose other class is the first
     * entry's under that entry's own name. Seeded for every case rather than for the partition one
     * alone: a peel or a member join that lost its entry key would fold the two entries' answers
     * together, and a fixture holding one entry could not tell.
     */
    private static void seedSiblingEntry(DSLContext dsl) {
        seedGraph(dsl, SIBLING);
        seedSource(dsl, LIB, "JAR");
        seedGraphSource(dsl, SIBLING, LIB);
        seedClass(dsl, LIB, "lib.Catalog", "CLASS");
        seedMethod(dsl, LIB, "lib.Catalog", "getFilms", LIST,
            Map.of("", "java.util.List", "0", "lib.FilmDto"));
        seedClass(dsl, LIB, LEGACY, "RECORD");
        seedRecordComponent(dsl, LIB, LEGACY, "cast",
            Map.of("", "java.util.List", "0", "lib.CastDto"));
        seedMethod(dsl, LIB, LEGACY, "cast", LIST,
            Map.of("", "java.util.List", "0", "lib.CastDto"));
        seedClass(dsl, LIB, LEGACY_STORE, "CLASS");
        seedMethod(dsl, LIB, LEGACY_STORE, "getCast", LIST,
            Map.of("", "java.util.List", "0", "lib.CastDto"));
        seedType(dsl, SIBLING, "Store", "OBJECT");
        seedType(dsl, SIBLING, "Film", "OBJECT");
        seedField(dsl, SIBLING, "Store", "films", "Film", true);
    }

    /** Only the coordinates the hop cases need, output and input axis alike. */
    private static void seedCoordinates(DSLContext dsl) {
        seedType(dsl, GRAPH, "Store", "OBJECT");
        seedType(dsl, GRAPH, "Film", "OBJECT");
        seedType(dsl, GRAPH, "String", "SCALAR");
        seedField(dsl, GRAPH, "Store", "films", "Film", true);
        seedField(dsl, GRAPH, "Store", "named", "Film", false);
        seedFieldBinding(dsl, GRAPH, "Store", "named", "language");
        seedField(dsl, GRAPH, "Store", "byKey", "Film", false);
        seedArgument(dsl, GRAPH, "Store", "byKey", "key", "String");
        seedField(dsl, GRAPH, "Store", "lookup", "Film", false);
        seedArgument(dsl, GRAPH, "Store", "lookup", "id", "String");
        seedField(dsl, GRAPH, "Film", "title", "String", false);
        seedDeclaredType(dsl, GRAPH, "FilmInput", "INPUT_OBJECT");
        seedField(dsl, GRAPH, "FilmInput", "actors", "ActorInput", true);
        seedField(dsl, GRAPH, "FilmInput", "films", "Film", true);
    }

    /** What one method's result type resolves to, which is where a declared type now lives. */
    private static String resultTypeOf(DSLContext dsl, String className, String methodName,
                                       String descriptor) {
        return dsl.select(CODE_METHOD.RESULT_TYPE)
            .from(CODE_METHOD)
            .where(CODE_METHOD.CLASS_NAME.eq(className)
                .and(CODE_METHOD.METHOD_NAME.eq(methodName))
                .and(CODE_METHOD.DESCRIPTOR.eq(descriptor)))
            .fetchOne(0, String.class);
    }

    /**
     * What the named slot delivers: the type the slot yields, peeled. One key join and no owner
     * kind, the slot naming what reading it hands back.
     */
    private static List<String> delivered(DSLContext dsl, String className, String slotName) {
        return dsl.select(CODE_TYPE_ELEMENT.ELEMENT_CLASS)
            .from(CODE_TYPE_SLOT)
            .join(CODE_TYPE_ELEMENT)
            .on(CODE_TYPE_ELEMENT.SOURCE_NAME.eq(CODE_TYPE_SLOT.SOURCE_NAME)
                .and(CODE_TYPE_ELEMENT.TYPE_NAME.eq(CODE_TYPE_SLOT.SLOT_TYPE)))
            .where(CODE_TYPE_SLOT.CLASS_NAME.eq(className)
                .and(CODE_TYPE_SLOT.SLOT_NAME.eq(slotName)))
            .fetch(0, String.class);
    }

    private static List<IntentFieldAccessorHopRecord> hops(DSLContext dsl, String graphName,
                                                           String typeName, String fieldName) {
        // This rule reads the emitted field anchor, which is a table a derivation fills rather than
        // the union view it used to read, so a case that only seeded is not yet readable here.
        derive(dsl);
        return dsl.selectFrom(INTENT_FIELD_ACCESSOR_HOP)
            .where(INTENT_FIELD_ACCESSOR_HOP.GRAPH_NAME.eq(graphName)
                .and(INTENT_FIELD_ACCESSOR_HOP.TYPE_NAME.eq(typeName))
                .and(INTENT_FIELD_ACCESSOR_HOP.FIELD_NAME.eq(fieldName)))
            .fetch();
    }
}
