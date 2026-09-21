package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.run.GraphitronStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.CODE_CONSTRUCTION;
import static no.sikt.graphitron.model.Tables.CODE_TYPE;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_SLOT;
import static no.sikt.graphitron.model.Tables.CODE_WRITE_SLOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The questions a generator asks when it has to make a value, asked of the relations directly.
 *
 * <p>Not a check that capture agrees with something. A model earns its shape by answering the
 * queries its readers have without a view in between to rearrange it first, so each case here is
 * one of those queries written as a reader would write it, against base relations only, and the
 * assertion is on the answer. A query that needs a subquery to find its own key, or a join whose
 * direction depends on which arm answered, is the relation telling us it is shaped for storing
 * rather than for asking.
 *
 * <p>Read over this module's own compiled output, so the classes are ones a compiler produced and
 * the fixtures are the shapes a consumer actually writes: a record filled in one call, a bean
 * filled one member at a time with half its members inherited, and a class nothing can make.
 */
class ConstructionQueryTest {

    /**
     * The emission recipe: what to call and what to pass, in the order to pass it.
     *
     * <p>One join. The construction names the call and the write slots name its arguments, so a
     * reader holding a class gets the whole of what it needs to emit in one pass, ordered by the
     * position it will write them at.
     */
    @Test
    @DisplayName("what do I call to make one of these, and what goes in")
    void theRecipeForOneClass() {
        withReactorCapture(dsl ->
            assertThat(dsl.select(CODE_CONSTRUCTION.SHAPE, CODE_WRITE_SLOT.POSITION,
                    CODE_WRITE_SLOT.SLOT_NAME, CODE_WRITE_SLOT.METHOD_NAME)
                .from(CODE_CONSTRUCTION)
                .join(CODE_WRITE_SLOT)
                .on(CODE_WRITE_SLOT.SOURCE_NAME.eq(CODE_CONSTRUCTION.SOURCE_NAME),
                    CODE_WRITE_SLOT.TYPE_NAME.eq(CODE_CONSTRUCTION.TYPE_NAME))
                .where(CODE_CONSTRUCTION.TYPE_NAME.eq(FIXTURES + "SlotRecord"))
                .orderBy(CODE_WRITE_SLOT.POSITION.asc())
                .fetch(r -> r.value1() + " " + r.value2() + " " + r.value3() + " " + r.value4()))
                .containsExactly(
                    "POSITIONAL 0 title <init>",
                    "POSITIONAL 1 year <init>",
                    "POSITIONAL 2 tags <init>"));
    }

    /**
     * The same question of the other shape, which is the one where the answer's shape differs and
     * the query does not: the call is per member instead of shared, and that is a value in a column
     * rather than a second query.
     *
     * <p>The inherited member is the case worth having. What an author may fill is what the class
     * offers, not what its own file declares, and a reading that stopped at the declared methods
     * would answer half of this.
     *
     * <p>The write side answers it and the read side does not, which this suite pins rather than
     * hides: {@link #theReadSideStopsAtTheDeclaringClass} states the asymmetry as it stands.
     */
    @Test
    @DisplayName("and of a class filled one member at a time, inherited members included")
    void theRecipeForABean() {
        withReactorCapture(dsl ->
            assertThat(dsl.select(CODE_CONSTRUCTION.SHAPE, CODE_WRITE_SLOT.SLOT_NAME,
                    CODE_WRITE_SLOT.METHOD_NAME)
                .from(CODE_CONSTRUCTION)
                .join(CODE_WRITE_SLOT)
                .on(CODE_WRITE_SLOT.SOURCE_NAME.eq(CODE_CONSTRUCTION.SOURCE_NAME),
                    CODE_WRITE_SLOT.TYPE_NAME.eq(CODE_CONSTRUCTION.TYPE_NAME))
                .where(CODE_CONSTRUCTION.TYPE_NAME.eq(FIXTURES + "BeanChild"))
                .orderBy(CODE_WRITE_SLOT.SLOT_NAME.asc())
                .fetch(r -> r.value1() + " " + r.value2() + " " + r.value3()))
                .containsExactly(
                    "SETTERS title setTitle",
                    "SETTERS year setYear"));
    }

    /**
     * What a member is filled with, followed to the class it finally delivers. The question every
     * descent into a nested input asks, and the one that says whether the two sides compose: a
     * write slot names a type, a type says what it delivers, and what it delivers is a class that
     * may itself be constructible.
     */
    @Test
    @DisplayName("what does a member take, and is that itself something I can make")
    void aMemberDescendsIntoWhatFillsIt() {
        withReactorCapture(dsl ->
            assertThat(dsl.select(CODE_WRITE_SLOT.SLOT_NAME, CODE_TYPE.DISPLAY_NAME,
                    CODE_TYPE_ELEMENT.ELEMENT_CLASS, CODE_TYPE_ELEMENT.IS_MANY)
                .from(CODE_WRITE_SLOT)
                .join(CODE_TYPE).on(CODE_TYPE.SOURCE_NAME.eq(CODE_WRITE_SLOT.SOURCE_NAME),
                    CODE_TYPE.TYPE_NAME.eq(CODE_WRITE_SLOT.SLOT_TYPE))
                .join(CODE_TYPE_ELEMENT)
                .on(CODE_TYPE_ELEMENT.SOURCE_NAME.eq(CODE_TYPE.SOURCE_NAME),
                    CODE_TYPE_ELEMENT.TYPE_NAME.eq(CODE_TYPE.TYPE_NAME))
                .where(CODE_WRITE_SLOT.TYPE_NAME.eq(FIXTURES + "SlotRecord"))
                .orderBy(CODE_WRITE_SLOT.POSITION.asc())
                .fetch(r -> r.value1() + " " + r.value2() + " -> " + r.value3()
                    + (r.value4() ? " many" : " one")))
                .as("the list member delivers its element and says so; the plain one delivers itself")
                .containsExactly(
                    "title String -> java.lang.String one",
                    "tags List<String> -> java.lang.String many"));
    }

    /**
     * Which members a caller has to supply. A class made in one call takes every argument, so every
     * write slot is required; a class filled afterwards requires none. The question is the shape
     * column and nothing else, which is what having stored the decision buys.
     */
    @Test
    @DisplayName("which members must a caller supply")
    void whatIsRequired() {
        withReactorCapture(dsl -> {
            assertThat(requiredOf(dsl, FIXTURES + "SlotRecord"))
                .as("every argument of the one call")
                .containsExactly("tags", "title", "year");
            assertThat(requiredOf(dsl, FIXTURES + "BeanChild"))
                .as("and nothing, a setter never having to be called")
                .isEmpty();
        });
    }

    /**
     * What an author can both read and write on one class, which is the question an SDL type used
     * on both axes asks. A name join, the two relations being two answers about one vocabulary, and
     * the outer side is the read one because a getter with no setter is the asymmetry that matters.
     */
    @Test
    @DisplayName("which members can be read but not filled")
    void theAsymmetryBetweenTheAxes() {
        withReactorCapture(dsl ->
            assertThat(dsl.selectDistinct(CODE_TYPE_SLOT.SLOT_NAME)
                .from(CODE_TYPE_SLOT)
                .where(CODE_TYPE_SLOT.CLASS_NAME.eq(FIXTURES + "SlotBean"))
                .and(notExists(selectOne().from(CODE_WRITE_SLOT)
                    .where(CODE_WRITE_SLOT.SOURCE_NAME.eq(CODE_TYPE_SLOT.SOURCE_NAME),
                        CODE_WRITE_SLOT.TYPE_NAME.eq(CODE_TYPE_SLOT.CLASS_NAME),
                        CODE_WRITE_SLOT.SLOT_NAME.eq(CODE_TYPE_SLOT.SLOT_NAME))))
                .orderBy(CODE_TYPE_SLOT.SLOT_NAME.asc())
                .fetch(CODE_TYPE_SLOT.SLOT_NAME))
                .as("a class with getters and no setters offers every member to a reader and none"
                    + " to a caller filling one")
                .containsExactly("restricted", "tags", "title", "uRL"));
    }

    /**
     * The same question on the read side, which is where the queries found the model short and
     * where it now answers: a member a base class declares is one the subclass offers, and the slot
     * says where it is written so a jump to its source lands in the file that has it.
     *
     * <p>One join for the type, which is the shape the write side settled first. The accessor's own
     * row is not consulted and could not be: it sits under the class that declares the method, and
     * the class offering the member is a different one.
     */
    @Test
    @DisplayName("what can I read off this class, inherited members included")
    void theRecipeForReadingABean() {
        withReactorCapture(dsl ->
            assertThat(dsl.select(CODE_TYPE_SLOT.SLOT_NAME, CODE_TYPE.DISPLAY_NAME,
                    CODE_TYPE_SLOT.METHOD_NAME, CODE_TYPE_SLOT.DECLARING_CLASS)
                .from(CODE_TYPE_SLOT)
                .join(CODE_TYPE).on(CODE_TYPE.SOURCE_NAME.eq(CODE_TYPE_SLOT.SOURCE_NAME),
                    CODE_TYPE.TYPE_NAME.eq(CODE_TYPE_SLOT.SLOT_TYPE))
                .where(CODE_TYPE_SLOT.CLASS_NAME.eq(FIXTURES + "BeanChild"))
                .orderBy(CODE_TYPE_SLOT.SLOT_NAME.asc())
                .fetch(r -> r.value1() + " " + r.value2() + " " + r.value3() + " from "
                    + r.value4().substring(FIXTURES.length())))
                .as("the inherited getter is offered here and written there")
                .containsExactly("title String getTitle from BeanBase"));
    }

    /**
     * And the two axes now agree about one class, which is what says the gap is closed rather than
     * moved. The member is readable and fillable, each through the method its own side names.
     */
    @Test
    @DisplayName("an inherited member is both readable and fillable")
    void bothAxesReachAnInheritedMember() {
        withReactorCapture(dsl -> {
            assertThat(dsl.select(CODE_TYPE_SLOT.METHOD_NAME)
                .from(CODE_TYPE_SLOT)
                .where(CODE_TYPE_SLOT.CLASS_NAME.eq(FIXTURES + "BeanChild"))
                .and(CODE_TYPE_SLOT.SLOT_NAME.eq("title"))
                .fetch(CODE_TYPE_SLOT.METHOD_NAME))
                .containsExactly("getTitle");
            assertThat(dsl.select(CODE_WRITE_SLOT.METHOD_NAME)
                .from(CODE_WRITE_SLOT)
                .where(CODE_WRITE_SLOT.TYPE_NAME.eq(FIXTURES + "BeanChild"))
                .and(CODE_WRITE_SLOT.SLOT_NAME.eq("title"))
                .fetch(CODE_WRITE_SLOT.METHOD_NAME))
                .containsExactly("setTitle");
        });
    }

    /**
     * And a class nothing can make answers nothing, which is the same silence as a class the
     * reading never reached. The two are one answer to a caller: there is no call to emit.
     */
    @Test
    @DisplayName("a class nothing can make is absent rather than present and unusable")
    void whatCannotBeMade() {
        withReactorCapture(dsl ->
            assertThat(dsl.fetchCount(CODE_CONSTRUCTION,
                CODE_CONSTRUCTION.TYPE_NAME.eq(FIXTURES + "AbstractHolder")))
                .isZero());
    }

    // ===== Helpers =====

    private static final String FIXTURES = "no.sikt.graphitron.model.capture.code.fixtures.";

    private static final LocalDateTime FIRST = LocalDateTime.of(2024, 1, 1, 0, 0);

    private static final List<ClasspathEntry> REACTOR =
        List.of(ClasspathEntry.project(Path.of("target", "test-classes")));

    /** The members a caller of this class has to supply, which the shape alone decides. */
    private static List<String> requiredOf(DSLContext dsl, String className) {
        return dsl.select(CODE_WRITE_SLOT.SLOT_NAME)
            .from(CODE_WRITE_SLOT)
            .join(CODE_CONSTRUCTION)
            .on(CODE_CONSTRUCTION.SOURCE_NAME.eq(CODE_WRITE_SLOT.SOURCE_NAME),
                CODE_CONSTRUCTION.TYPE_NAME.eq(CODE_WRITE_SLOT.TYPE_NAME),
                CODE_CONSTRUCTION.SHAPE.eq("POSITIONAL"))
            .where(CODE_WRITE_SLOT.TYPE_NAME.eq(className))
            .orderBy(CODE_WRITE_SLOT.SLOT_NAME.asc())
            .fetch(CODE_WRITE_SLOT.SLOT_NAME);
    }

    private static void withReactorCapture(Consumer<DSLContext> body) {
        try (var store = GraphitronStore.inMemory()) {
            CodeCapture.capture(store.dsl(),
                ClasspathSourceCapture.read(store.dsl(), REACTOR, null, FIRST), null, FIRST);
            body.accept(store.dsl());
        }
    }
}
