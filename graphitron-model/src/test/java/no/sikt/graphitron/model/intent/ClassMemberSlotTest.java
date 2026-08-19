package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.tables.records.IntentClassMemberSlotRecord;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_SLOT;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedComponentForm;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedRecordComponent;
import static no.sikt.graphitron.model.test.SeededStore.seedReturnForm;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_class_member_slot} returns: which member names a class offers an SDL author,
 * in the author's vocabulary rather than the JVM's. A class takes exactly one arm, chosen by its
 * declared form, so a record answers with its components and everything else answers with its bean
 * accessors.
 *
 * <p>Every input is stated as rows, which is what puts the arrangements the rule decides between in
 * one fixture. Two classpath entries declaring one class name is the arrangement both arms' own
 * owner key turns on, and no compiler produces it from one source tree. A bean accessor overloaded
 * with a parameterised twin of its own name is what makes the parameter anti-join's descriptor
 * observable: without the twin the anti-join could drop the descriptor and answer the same. A
 * record component under a class the census calls a {@code CLASS} is a state no compiler emits at
 * all, and it is the only thing that makes the record arm's own gate visible, the bean arm's gate
 * being the half a real record already shows.
 *
 * <p>Two of the rule's clauses are unobservable from any census a scanner can produce, and neither
 * is a gap these cases could close. The bean arm's length gate is subsumed by the case gate that
 * follows it: a name no longer than its prefix has no character to lower, and nothing that is not a
 * character differs from its own lowering, so the clause guards a dialect's substring behaviour
 * rather than a state. The parameter anti-join keys on the method's whole primary key, and the
 * descriptor part of that key already spells the parameter list, so dropping any other part can
 * only match a method whose descriptor says no parameters while a parameter row stands beside it,
 * which is a census contradicting itself rather than a census. Dropping the descriptor is the part
 * that changes an answer, and the overloaded pair below is what says so.
 *
 * <p>That a compiler's own output lands in these relations in the shape the rule reads is the other
 * question, and it is asked once, over a real classfile scan of three fixture classes, in
 * {@code no.sikt.graphitron.rewrite.derive.ClassMemberSlotScanTest}.
 */
class ClassMemberSlotTest {

    // ===== A record answers with its components =====

    /**
     * The record arm whole: the components in the author's vocabulary, each carrying its own
     * declared type and resolving to its own accessor, and nothing else from a class that also
     * declares four methods.
     */
    @Test
    void aRecordAnswersWithItsComponents() {
        withCensus(dsl ->
            assertThat(described(dsl, APP, FILM)).containsExactlyInAnyOrder(
                "filmId RECORD_COMPONENT Integer filmId",
                "title RECORD_COMPONENT String title",
                "tags RECORD_COMPONENT List<String> tags"));
    }

    /**
     * A record's declared bean accessor contributes nothing. Without the arm gate the class would
     * offer {@code title} twice, once as a component and once as an accessor, and a reader taking
     * the first match would land on whichever the union happened to order first.
     */
    @Test
    void aRecordIgnoresItsOwnBeanAccessors() {
        withCensus(dsl -> {
            var titles = rows(dsl, APP, FILM, "title");
            assertThat(titles).hasSize(1);
            assertThat(titles.getFirst().getOrigin()).isEqualTo("RECORD_COMPONENT");
            assertThat(titles.getFirst().getAccessorMethodName()).isEqualTo("title");
        });
    }

    /**
     * A component row under a class the census did not call a record offers nothing, and the gate
     * names {@code RECORD} rather than excluding one other form: the component is stated twice, once
     * under a class and once under an interface. The class carries no methods either, so the whole
     * of what it could contribute is the component.
     *
     * <p>No compiler emits either pairing, which is the reason they are stated here rather than
     * compiled: a fixture that reaches the census through a scan can only show the gate from the
     * bean side, where a real record already stands.
     */
    @Test
    void aComponentUnderANonRecordOffersNothing() {
        withCensus(dsl -> {
            assertThat(slots(dsl, APP, PLAIN)).isEmpty();
            assertThat(slotNames(dsl, APP, LOOKUP)).doesNotContain("ghost");
        });
    }

    // ===== Anything else answers with its bean accessors =====

    /** The two prefixes the rule accepts, each yielding the remainder of its own name. */
    @Test
    void aClassAnswersWithItsBeanAccessors() {
        withCensus(dsl -> {
            assertThat(described(dsl, APP, STORE))
                .contains("title BEAN_ACCESSOR String getTitle")
                .contains("restricted BEAN_ACCESSOR boolean isRestricted");
        });
    }

    /** Only the first letter is lowered, so an acronym keeps the rest of its case. */
    @Test
    void anAccessorLowersItsFirstLetterAndLeavesTheRest() {
        withCensus(dsl ->
            assertThat(slotNames(dsl, APP, STORE)).contains("uRL").doesNotContain("url", "URL"));
    }

    /**
     * The near-misses: no prefix at all, the prefix with nothing after it, a lower-case letter where
     * the rule requires an upper-case one, a character with no case of its own, and a name carrying
     * a prefix without beginning with one, which is what says the match is anchored at the start
     * rather than anywhere in the name. All five are asserted on the accessor rather than on the
     * slot name, {@code title} being a name the class does offer under two prefixed spellings and
     * the rest being names nothing would confuse with a slot.
     */
    @Test
    void aMethodThatIsNotBeanShapedOffersNothing() {
        withCensus(dsl ->
            assertThat(accessors(dsl, APP, STORE))
                .doesNotContain("title", "get", "getlower", "get_id", "toList"));
    }

    /**
     * A bean-shaped method taking an argument offers nothing: no member name can resolve to a call
     * the generator would have to supply a value for. The class declares both overloads of the one
     * name, so what must not have contributed is one row of a pair the two arms of the anti-join's
     * key have to tell apart, rather than the only method of its name.
     */
    @Test
    void aBeanShapedMethodTakingAnArgumentOffersNothing() {
        withCensus(dsl -> {
            var rated = rows(dsl, APP, STORE, "rated");
            assertThat(rated).hasSize(1);
            assertThat(rated.getFirst().getDisplayType())
                .as("the no-argument overload's own return type, the parameterised twin returning "
                    + "something else")
                .isEqualTo("Rating");
        });
    }

    /**
     * The rule reads the name and not the return type, so {@code isTitle()} returning a String is a
     * slot. It is also the second spelling of a property the class already offers, and both rows
     * stand: the projection's list held both, and a reader that offers candidates should offer both.
     */
    @Test
    void twoSpellingsOfOnePropertyAreTwoSlots() {
        withCensus(dsl ->
            assertThat(rows(dsl, APP, STORE, "title"))
                .extracting(IntentClassMemberSlotRecord::getAccessorMethodName)
                .containsExactlyInAnyOrder("getTitle", "isTitle"));
    }

    /** The arm is chosen by "not a record", so an interface answers with accessors like any class. */
    @Test
    void anInterfaceAnswersLikeAnyNonRecord() {
        withCensus(dsl ->
            assertThat(described(dsl, APP, LOOKUP))
                .containsExactly("name BEAN_ACCESSOR String getName"));
    }

    // ===== The rendered type is the declared one =====

    /**
     * The slot's type is what the source declared, not what the descriptor erases to, and both arms
     * agree on that. An author reading {@code List} learns nothing about what the member holds;
     * {@code List<String>} is the answer they are looking at in their own source. This is also the
     * fact an accessor walk needs, a container's element type being exactly what erasure drops.
     *
     * <p>The two rows carry both forms, so the assertion discriminates: a reader that took the
     * erasure would answer {@code List} rather than falling silent.
     */
    @Test
    void aSlotRendersTheDeclaredTypeRatherThanTheErasure() {
        withCensus(dsl -> {
            assertThat(slot(dsl, APP, FILM, "tags").getDisplayType())
                .as("the record arm reads the component's own declared form")
                .isEqualTo("List<String>");
            assertThat(slot(dsl, APP, STORE, "tags").getDisplayType())
                .as("the bean arm reads the accessor's")
                .isEqualTo("List<String>");
        });
    }

    // ===== The owner key =====

    /**
     * One class name under two classpath entries is two classes, on both arms. Each entry answers
     * with its own members and neither doubles the other's: an owner key that lost the entry would
     * hand every reader of one workspace's module the other's answers, and would hand them twice.
     */
    @Test
    void oneClassNameInTwoEntriesIsTwoClasses() {
        withCensus(dsl -> {
            assertThat(described(dsl, LIB, STORE)).containsExactly("region BEAN_ACCESSOR String getRegion");
            assertThat(described(dsl, LIB, FILM)).containsExactly("edition RECORD_COMPONENT String edition");
            assertThat(slotNames(dsl, APP, STORE)).doesNotContain("region");
            assertThat(slotNames(dsl, APP, FILM)).doesNotContain("edition");
        });
    }

    // ===== Partition =====

    /**
     * The relation carries no graph column, so the partition is the reader's semi-join through
     * {@code store_graph_source}. Each graph sees the entry it read and only that one, which is the
     * whole of what keeps one workspace's modules out of each other's answers. Asserted in both
     * directions over two entries declaring the same two class names, so a partition that answered
     * everything and one that answered nothing fail differently.
     */
    @Test
    void aGraphSeesOnlyTheEntriesItRead() {
        withCensus(dsl -> {
            assertThat(dsl.select(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME)
                .from(INTENT_CLASS_MEMBER_SLOT)
                .where(new StoreHandle(dsl, GRAPH).reads(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME))
                .fetchSet(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME))
                .containsExactly(APP);
            assertThat(dsl.select(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME)
                .from(INTENT_CLASS_MEMBER_SLOT)
                .where(new StoreHandle(dsl, OTHER_GRAPH).reads(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME))
                .fetchSet(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME))
                .containsExactly(LIB);
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String OTHER_GRAPH = "g2";

    private static final String APP = "app/target/classes";
    private static final String LIB = "lib.jar";

    private static final String FILM = "app.FilmRecord";
    private static final String STORE = "app.Store";
    private static final String LOOKUP = "app.Lookup";
    private static final String PLAIN = "app.NotARecord";

    private static final String STRING = "()Ljava/lang/String;";
    private static final String BOOLEAN = "()Z";
    private static final String LIST = "()Ljava/util/List;";
    private static final String RATING = "()Lapp/Rating;";
    private static final String SCALED = "(I)Ljava/lang/String;";

    /**
     * Two classpath entries, one per graph, declaring the same two class names between them. The
     * duplication is the point twice over: it is what an owner key that dropped the entry answers
     * wrongly, and it is what makes the partition assertable in both directions.
     */
    private static void withCensus(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, APP, "DIRECTORY");
            seedGraphSource(dsl, GRAPH, APP);
            seedRecord(dsl);
            seedPlainClass(dsl);
            seedInterface(dsl);
            seedSiblingEntry(dsl);
            body.accept(dsl);
        });
    }

    /**
     * A record with the three components the arm's cases read, plus the four methods a record of
     * this shape declares: one accessor per component, under the component's own name, and one
     * bean-shaped accessor the author wrote by hand.
     */
    private static void seedRecord(DSLContext dsl) {
        seedClass(dsl, APP, FILM, "RECORD");
        seedRecordComponent(dsl, APP, FILM, "filmId", Map.of("", "java.lang.Integer"));
        seedComponentForm(dsl, APP, FILM, "filmId", "Integer", "Integer");
        seedRecordComponent(dsl, APP, FILM, "title", Map.of("", "java.lang.String"));
        seedComponentForm(dsl, APP, FILM, "title", "String", "String");
        seedRecordComponent(dsl, APP, FILM, "tags",
            Map.of("", "java.util.List", "0", "java.lang.String"));
        seedComponentForm(dsl, APP, FILM, "tags", "List", "List<String>");
        seedMethod(dsl, APP, FILM, "filmId", "()Ljava/lang/Integer;");
        seedMethod(dsl, APP, FILM, "title", STRING);
        seedMethod(dsl, APP, FILM, "tags", LIST);
        seedMethod(dsl, APP, FILM, "getTitle", STRING);
    }

    /**
     * A class whose methods span the bean rule's accept and reject sides: the two prefixes, the
     * first-letter lowering, the four near-misses, the overloaded pair, and the two spellings of one
     * property. Beside it, a class the census calls a {@code CLASS} carrying a record component and
     * nothing else.
     */
    private static void seedPlainClass(DSLContext dsl) {
        seedClass(dsl, APP, STORE, "CLASS");
        seedMethod(dsl, APP, STORE, "getTitle", STRING);
        seedReturnForm(dsl, APP, STORE, "getTitle", STRING, "String", "String");
        seedMethod(dsl, APP, STORE, "isRestricted", BOOLEAN);
        seedReturnForm(dsl, APP, STORE, "isRestricted", BOOLEAN, "boolean", "boolean");
        seedMethod(dsl, APP, STORE, "getURL", STRING);
        seedReturnForm(dsl, APP, STORE, "getURL", STRING, "String", "String");
        seedMethod(dsl, APP, STORE, "getTags", LIST, Map.of("", "java.util.List", "0", "java.lang.String"));
        seedReturnForm(dsl, APP, STORE, "getTags", LIST, "List", "List<String>");
        seedMethod(dsl, APP, STORE, "isTitle", STRING);
        seedReturnForm(dsl, APP, STORE, "isTitle", STRING, "String", "String");

        // The near-misses, each rejected by a different clause. toList carries "is" at its fourth
        // character with an upper-case letter at the third, so a match that looked for a prefix
        // anywhere in the name rather than at its start would offer a slot named list.
        seedMethod(dsl, APP, STORE, "title", STRING);
        seedMethod(dsl, APP, STORE, "get", STRING);
        seedMethod(dsl, APP, STORE, "getlower", STRING);
        seedMethod(dsl, APP, STORE, "get_id", STRING);
        seedMethod(dsl, APP, STORE, "toList", LIST);

        // The overloaded pair: one name, two descriptors, and only the parameterless one a slot.
        seedMethod(dsl, APP, STORE, "getRated", RATING);
        seedReturnForm(dsl, APP, STORE, "getRated", RATING, "Rating", "Rating");
        seedMethod(dsl, APP, STORE, "getRated", SCALED);
        seedReturnForm(dsl, APP, STORE, "getRated", SCALED, "String", "String");
        seedMethodParameter(dsl, APP, STORE, "getRated", SCALED, 0, Map.of());

        seedClass(dsl, APP, PLAIN, "CLASS");
        seedRecordComponent(dsl, APP, PLAIN, "ghost", Map.of("", "java.lang.String"));
        seedComponentForm(dsl, APP, PLAIN, "ghost", "String", "String");
    }

    /**
     * An interface, the declared form that is neither a record nor a class, carrying the second
     * component no compiler would pair with a declared form: the record arm's gate names one form,
     * and a gate that excluded a form instead would answer here.
     */
    private static void seedInterface(DSLContext dsl) {
        seedClass(dsl, APP, LOOKUP, "INTERFACE");
        seedMethod(dsl, APP, LOOKUP, "getName", STRING);
        seedReturnForm(dsl, APP, LOOKUP, "getName", STRING, "String", "String");
        seedRecordComponent(dsl, APP, LOOKUP, "ghost", Map.of("", "java.lang.String"));
        seedComponentForm(dsl, APP, LOOKUP, "ghost", "String", "String");
    }

    /** A second entry, read by a second graph, declaring both class names again with its own members. */
    private static void seedSiblingEntry(DSLContext dsl) {
        seedGraph(dsl, OTHER_GRAPH);
        seedSource(dsl, LIB, "JAR");
        seedGraphSource(dsl, OTHER_GRAPH, LIB);
        seedClass(dsl, LIB, STORE, "CLASS");
        seedMethod(dsl, LIB, STORE, "getRegion", STRING);
        seedReturnForm(dsl, LIB, STORE, "getRegion", STRING, "String", "String");
        seedClass(dsl, LIB, FILM, "RECORD");
        seedRecordComponent(dsl, LIB, FILM, "edition", Map.of("", "java.lang.String"));
        seedComponentForm(dsl, LIB, FILM, "edition", "String", "String");
    }

    private static Result<IntentClassMemberSlotRecord> slots(DSLContext dsl, String sourceName,
                                                             String className) {
        return dsl.selectFrom(INTENT_CLASS_MEMBER_SLOT)
            .where(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME.eq(sourceName))
            .and(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME.eq(className))
            .fetch();
    }

    /** Every column but the owner key, which the caller already named, in one string per row. */
    private static List<String> described(DSLContext dsl, String sourceName, String className) {
        return slots(dsl, sourceName, className).map(r -> r.getSlotName() + " " + r.getOrigin()
            + " " + r.getDisplayType() + " " + r.getAccessorMethodName());
    }

    private static List<String> slotNames(DSLContext dsl, String sourceName, String className) {
        return slots(dsl, sourceName, className).map(IntentClassMemberSlotRecord::getSlotName);
    }

    private static List<String> accessors(DSLContext dsl, String sourceName, String className) {
        return slots(dsl, sourceName, className).map(IntentClassMemberSlotRecord::getAccessorMethodName);
    }

    private static List<IntentClassMemberSlotRecord> rows(DSLContext dsl, String sourceName,
                                                                    String className, String slotName) {
        return slots(dsl, sourceName, className).stream()
            .filter(r -> slotName.equals(r.getSlotName()))
            .toList();
    }

    /** The one row for a slot name, for the cases where the class offers a single spelling of it. */
    private static IntentClassMemberSlotRecord slot(DSLContext dsl, String sourceName,
                                                    String className, String slotName) {
        var matching = rows(dsl, sourceName, className, slotName);
        assertThat(matching).as("one row for slot '%s' on %s", slotName, className).hasSize(1);
        return matching.getFirst();
    }
}
