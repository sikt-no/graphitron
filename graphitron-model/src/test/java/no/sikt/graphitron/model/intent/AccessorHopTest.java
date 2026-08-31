package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.tables.records.IntentFieldAccessorHopRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_ELEMENT;
import static no.sikt.graphitron.model.Tables.INTENT_DECLARED_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.JVM_DECLARED_TYPE_REF;
import static no.sikt.graphitron.model.Tables.INTENT_DELIVERY_CONTAINER;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_ACCESSOR_HOP;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedMintedField;
import static no.sikt.graphitron.model.test.SeededStore.seedMintedType;
import static no.sikt.graphitron.model.test.SeededStore.seedRecordComponent;
import static no.sikt.graphitron.model.test.SeededStore.seedReturnTypeRef;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for the five relations an accessor hop is built from:
 * {@code intent_delivery_container}, the classes a declared type delivers through;
 * {@code jvm_declared_type_ref}, the census's declared types under one owner key;
 * {@code intent_declared_type_element}, the class a declared type delivers once the containers are
 * peeled; {@code intent_class_member_element}, that peel read at a member slot's own owner; and
 * {@code intent_field_accessor_hop}, where a field coordinate standing on a class lands.
 *
 * <p>Every input is stated as rows. A census position is a name, a path and a variance, which is
 * all these rules read of one, and the arrangements they have to get right are ones no compiled
 * fixture offers side by side: a two-level container, a map, a raw container, a generic class that
 * is not a container at all, an accessor overloaded with a parameterised twin, one slot name
 * offered by two classes, and a nesting one step deeper than the descent goes. The scan's own
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

    /**
     * How many a declared type delivers, read off the descent rather than off the class it landed
     * on. The four cases are the ones that differ: a collection multiplies, a wrapper around one
     * does not, a wrapper around a collection does, and a map follows its value.
     */
    @Test
    void theDescentSaysHowManyTheTypeDelivers() {
        withCensus(dsl -> {
            assertThat(deliversMany(dsl, STORE, "getFilms", LIST)).isTrue();
            assertThat(deliversMany(dsl, STORE, "getPending", FUTURE))
                .as("a wrapper around a collection still delivers many")
                .isTrue();
            assertThat(deliversMany(dsl, STORE, "getByKey", MAP))
                .as("a map to one value delivers one")
                .isFalse();
            assertThat(deliversMany(dsl, STORE, "getTitle", "()Ljava/lang/String;")).isFalse();
        });
    }

    /**
     * A raw container delivers itself and delivers one of it. The descent never happened, so there
     * is nothing to multiply, which is the reading the reflective walk reaches by requiring a
     * parameterised type before it looks at all.
     */
    @Test
    void aRawContainerDeliversOne() {
        withCensus(dsl -> assertThat(deliversMany(dsl, STORE, "getRaw", LIST)).isFalse());
    }

    // ===== A declared type, position by position, under its owner =====

    /**
     * Every owner kind answers under one key. A record component, a method return and a method
     * parameter decompose into the same path grammar, and a reader that holds an owner does not
     * have to know which census relation stated it.
     */
    @Test
    void everyOwnerKindNamesItsPositionsUnderOneKey() {
        withCensus(dsl -> {
            assertThat(positions(dsl, FILM, "actors", null))
                .containsExactlyInAnyOrder(" java.util.List", "0 app.ActorRecord");
            assertThat(positions(dsl, STORE, "getFilms", LIST))
                .containsExactlyInAnyOrder(" java.util.List", "0 app.FilmRecord");
            assertThat(parameterDelivers(dsl, STORE, "search", SEARCH, 0))
                .as("a parameter is peeled by the same rule as a return")
                .containsExactly("java.lang.String at 0");
        });
    }

    /**
     * The ordinal is what tells one parameter from its neighbour, under a key they otherwise share
     * entirely. Without it the peel would join every position of one parameter's type against every
     * other's and answer with a cross product, which is what the third parameter is declared a
     * container for: two parameters descending makes the cross product visible where one cannot.
     */
    @Test
    void parametersAreToldApartByTheirOrdinal() {
        withCensus(dsl -> {
            assertThat(parameterDelivers(dsl, STORE, "search", SEARCH, 1))
                .containsExactly("app.LanguageRecord at ");
            assertThat(parameterDelivers(dsl, STORE, "search", SEARCH, 2))
                .containsExactly("app.LanguageRecord at 0");
            assertThat(positions(dsl, STORE, "search", SEARCH))
                .as("and the return is still the return, unmixed with any of them")
                .containsExactly(" app.FilmRecord");
        });
    }

    /**
     * The owner key holds an overload apart, which is the whole reason it carries a descriptor. Two
     * methods of one name decompose into two owners rather than into one owner's confused positions.
     */
    @Test
    void overloadsAreToldApartByTheirDescriptor() {
        withCensus(dsl -> {
            assertThat(positions(dsl, STORE, "getTitle", "()Ljava/lang/String;"))
                .containsExactly(" java.lang.String");
            assertThat(positions(dsl, STORE, "getTitle", SPOKEN_TITLE))
                .containsExactly(" app.LanguageRecord");
        });
    }

    // ===== What a declared type delivers =====

    /**
     * The peel is keyed on the declared type's owner and not on any reader's subject, so a method
     * that is no member slot at all is peeled on the same terms. This is the case that moved the
     * rule down a level: a producer method's return is the second reader, and it arrives under a
     * key no slot relation can hold.
     */
    @Test
    void aMethodThatIsNoSlotIsPeeledOnTheSameTerms() {
        withCensus(dsl -> {
            assertThat(deliveredBy(dsl, STORE, "getLookup", LOOKUP))
                .containsExactly("app.FilmRecord at ");
            assertThat(delivered(dsl, STORE, "lookup"))
                .as("and it is still no slot, so the member view says nothing about it")
                .isEmpty();
        });
    }

    /**
     * The descent is four steps and stops, which is the bound the view spells out rather than a
     * recursion, and the depth the walk it replaces also descends. A type nested one step deeper
     * delivers the container the fourth step reached, so the reader sees a container where it
     * expected a class and can say so, rather than being handed a wrong class it cannot detect.
     */
    @Test
    void theDescentIsFourStepsDeepAndSaysWhereItStopped() {
        withCensus(dsl -> {
            assertThat(deliveredBy(dsl, STORE, "getDeep", FUTURE))
                .containsExactly("app.FilmRecord at 0.0.1.0");
            assertThat(deliversMany(dsl, STORE, "getDeep", FUTURE))
                .as("and the list crossed at the fourth step is what made it many")
                .isTrue();
            assertThat(deliveredBy(dsl, STORE, "getDeeper", FUTURE))
                .as("one position deeper than the descent goes is the container itself")
                .containsExactly("java.util.List at 0.0.1.0");
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
                .containsExactly("java.lang.String at "));
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
                .containsExactly("app.LanguageRecord at "));
    }

    /** The ordinary peel: one container, one descent, the element. */
    @Test
    void aContainerSlotDeliversItsElement() {
        withCensus(dsl ->
            assertThat(delivered(dsl, STORE, "films"))
                .containsExactly("app.FilmRecord at 0"));
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
                .containsExactlyInAnyOrder("app.ActorRecord at 0", "lib.CastDto at 0");
            assertThat(delivered(dsl, LEGACY_STORE, "cast"))
                .as("an accessor declared on both")
                .containsExactlyInAnyOrder("app.ActorRecord at 0", "lib.CastDto at 0");
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
                .containsExactly("app.FilmRecord at 0.0"));
    }

    /** A map delivers its value, which is the one container whose element is not the first argument. */
    @Test
    void aMapDeliversItsValue() {
        withCensus(dsl ->
            assertThat(delivered(dsl, STORE, "byKey"))
                .containsExactly("app.FilmRecord at 1"));
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
                .containsExactly("java.util.List at ");
            assertThat(delivered(dsl, STORE, "boxed"))
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
        withCensus(dsl ->
            assertThat(dsl.select(INTENT_CLASS_MEMBER_ELEMENT.ELEMENT_CLASS,
                    INTENT_CLASS_MEMBER_ELEMENT.VARIANCE)
                .from(INTENT_CLASS_MEMBER_ELEMENT)
                .where(INTENT_CLASS_MEMBER_ELEMENT.CLASS_NAME.eq(STORE)
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
        withCensus(dsl -> {
            assertThat(delivered(dsl, STORE, "count")).isEmpty();
            assertThat(delivered(dsl, STORE, "tags")).isEmpty();
            assertThat(positions(dsl, STORE, "getTags", "()[Ljava/lang/String;"))
                .as("the array's component is still a position, so the absence is the root's")
                .containsExactly("[] java.lang.String");
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
        withCensus(dsl -> {
            var rows = hops(dsl, GRAPH, "Store", "named");
            assertThat(rows).extracting(r -> r.getSlotName() + " " + r.getToClassName())
                .containsExactly("language app.LanguageRecord");
        });
    }

    /**
     * An input-object field is the same question about the same members, so it is one population.
     * Two records offer the slot the field names and the coordinate stands on both, this relation
     * saying which class a parent is on no more here than anywhere else.
     */
    @Test
    void anInputObjectFieldHopsOnTheSameTerms() {
        withCensus(dsl ->
            assertThat(hops(dsl, GRAPH, "FilmInput", "actors"))
                .extracting(r -> r.getFromClassName() + " " + r.getOrigin() + " " + r.getToClassName())
                .containsExactlyInAnyOrder(
                    "app.FilmRecord RECORD_COMPONENT app.ActorRecord",
                    "app.ShowRecord RECORD_COMPONENT app.CastRecord"));
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

            seedMintedType(dsl, GRAPH, "FilmEdge", "Store", "films");
            seedMintedField(dsl, GRAPH, "FilmEdge", "node", "Film", 0, false, false, null);

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
        seedClass(dsl, APP, LEGACY_STORE, "CLASS");
        seedMethod(dsl, APP, LEGACY_STORE, "getCast", LIST,
            Map.of("", "java.util.List", "0", "app.ActorRecord"));
    }

    /**
     * One accessor per delivery shape, plus the overloaded pair and the parameterised accessor the
     * divergence cases stand on, plus the two nestings the descent's own bound turns on. Three of
     * them share the one list descriptor, which is what an owner key reduced to a name and a
     * descriptor would confuse.
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
        seedMethod(dsl, APP, STORE, "getSubset", LIST, Map.of("", "java.util.List"));
        seedReturnTypeRef(dsl, APP, STORE, "getSubset", LIST, "0", FILM, "EXTENDS");
        seedMethod(dsl, APP, STORE, "getCount", "()I");
        seedMethod(dsl, APP, STORE, "getTags", "()[Ljava/lang/String;",
            Map.of("[]", "java.lang.String"));

        // A future of an optional of a map to a list of films, and the same one position deeper.
        seedMethod(dsl, APP, STORE, "getDeep", FUTURE,
            Map.of("", "java.util.concurrent.CompletableFuture", "0", "java.util.Optional",
                "0.0", "java.util.Map", "0.0.0", "java.lang.String", "0.0.1", "java.util.List",
                "0.0.1.0", FILM));
        seedMethod(dsl, APP, STORE, "getDeeper", FUTURE,
            Map.of("", "java.util.concurrent.CompletableFuture", "0", "java.util.Optional",
                "0.0", "java.util.Map", "0.0.0", "java.lang.String", "0.0.1", "java.util.List",
                "0.0.1.0", "java.util.List", "0.0.1.0.0", FILM));

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
        seedClass(dsl, LIB, LEGACY_STORE, "CLASS");
        seedMethod(dsl, LIB, LEGACY_STORE, "getCast", LIST,
            Map.of("", "java.util.List", "0", "lib.CastDto"));
        seedType(dsl, SIBLING, "Film", "OBJECT");
        seedField(dsl, SIBLING, "Store", "films", "Film", true);
    }

    /** Only the coordinates the hop cases need, output and input axis alike. */
    private static void seedCoordinates(DSLContext dsl) {
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
    }

    /**
     * Each position as its path and the class named there, so a case states the whole
     * decomposition. A null descriptor names the record arm, where an owner has none and the relation spells that as the empty string.
     */
    private static List<String> positions(DSLContext dsl, String className, String ownerName,
                                          String descriptor) {
        var t = JVM_DECLARED_TYPE_REF;
        return dsl.select(t.TYPE_PATH, t.REFERENCED_CLASS)
            .from(t)
            .where(t.CLASS_NAME.eq(className)
                .and(t.OWNER_NAME.eq(ownerName))
                .and(t.OWNER_POSITION.eq(-1))
                .and(t.OWNER_DESCRIPTOR.eq(descriptor == null ? "" : descriptor)))
            .fetch(r -> r.value1() + " " + r.value2());
    }

    /** How many the named owner's declared type delivers. */
    private static boolean deliversMany(DSLContext dsl, String className, String ownerName,
                                        String descriptor) {
        var e = INTENT_DECLARED_TYPE_ELEMENT;
        return dsl.select(e.DELIVERS_MANY)
            .from(e)
            .where(e.CLASS_NAME.eq(className)
                .and(e.OWNER_NAME.eq(ownerName))
                .and(e.OWNER_POSITION.eq(-1))
                .and(e.OWNER_DESCRIPTOR.eq(descriptor)))
            .fetchSingle(0, Boolean.class);
    }

    /** The delivered class of an owner named directly, for the readers that hold no slot. */
    private static List<String> deliveredBy(DSLContext dsl, String className, String ownerName,
                                            String descriptor) {
        var e = INTENT_DECLARED_TYPE_ELEMENT;
        return dsl.select(e.ELEMENT_CLASS, e.ELEMENT_PATH)
            .from(e)
            .where(e.CLASS_NAME.eq(className)
                .and(e.OWNER_NAME.eq(ownerName))
                .and(e.OWNER_POSITION.eq(-1))
                .and(e.OWNER_DESCRIPTOR.eq(descriptor)))
            .fetch(r -> r.value1() + " at " + r.value2());
    }

    /**
     * One parameter's delivered class, addressed by its ordinal. The three owner-keyed helpers
     * above ask for a null ordinal rather than naming an owner kind, which is the same selection
     * said in the key's own terms: the two arms they read identify their owner without one.
     */
    private static List<String> parameterDelivers(DSLContext dsl, String className,
                                                  String methodName, String descriptor,
                                                  int position) {
        var e = INTENT_DECLARED_TYPE_ELEMENT;
        return dsl.select(e.ELEMENT_CLASS, e.ELEMENT_PATH)
            .from(e)
            .where(e.CLASS_NAME.eq(className)
                .and(e.OWNER_KIND.eq("METHOD_PARAMETER"))
                .and(e.OWNER_NAME.eq(methodName))
                .and(e.OWNER_DESCRIPTOR.eq(descriptor))
                .and(e.OWNER_POSITION.eq(position)))
            .fetch(r -> r.value1() + " at " + r.value2());
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

    private static List<IntentFieldAccessorHopRecord> hops(DSLContext dsl, String graphName,
                                                           String typeName, String fieldName) {
        return dsl.selectFrom(INTENT_FIELD_ACCESSOR_HOP)
            .where(INTENT_FIELD_ACCESSOR_HOP.GRAPH_NAME.eq(graphName)
                .and(INTENT_FIELD_ACCESSOR_HOP.TYPE_NAME.eq(typeName))
                .and(INTENT_FIELD_ACCESSOR_HOP.FIELD_NAME.eq(fieldName)))
            .fetch();
    }
}
