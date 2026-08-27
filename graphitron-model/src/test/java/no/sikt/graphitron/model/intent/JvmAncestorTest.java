package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_JVM_ANCESTOR;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedRecordComponent;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedSupertype;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_jvm_ancestor} returns: every type a class is known to be, itself included.
 *
 * <p>Three kinds of case. The first is the closure itself, which is ordinary: reflexive, transitive
 * through both declaration clauses, and one row per pair however many inheritance paths connect
 * them.
 *
 * <p>The second is the seed, and it is the load-bearing one. The relation deliberately does not
 * close every pair in the census, only the classes some captured signature named, so a case states
 * both halves of that: a named class is closed, and a class nothing names has no rows at all
 * however many supertypes it declares. The three relations that count as naming one, and the
 * positions within a declared type that count, each get a case because the rule is uniform across
 * them and a later narrowing would have to break one of these.
 *
 * <p>The third is what the closure cannot answer, which a reader has to know before reading an
 * absent pair. A chain stops at the first name with no census row of its own, and two entries
 * declaring one class are one edge rather than two, that being the constraint the capture relation
 * stated in prose and this relation had to honour.
 */
class JvmAncestorTest {

    // ===== The closure =====

    /**
     * Every seeded class is its own ancestor. What lets a reader asking whether a declared type is
     * some interface spell one existence test rather than an equality beside it, which is the shape
     * the condition table-parameter rule reads this relation with.
     */
    @Test
    void aSeededClassIsItsOwnAncestor() {
        withSources(dsl -> {
            namedByASignature(dsl, "com.example.Plain");

            assertThat(ancestorsOf(dsl, "com.example.Plain"))
                .containsExactly("com.example.Plain");
        });
    }

    /**
     * The chain climbs through both clauses. A classfile records an extends and an implements list
     * in the same relation and the distinction between them is not a distinction about
     * assignability, so a case states one of each and expects both to be reached.
     */
    @Test
    void aClassReachesEveryTypeAboveItThroughEitherClause() {
        withSources(dsl -> {
            seedClass(dsl, JAR, "com.example.Leaf", "CLASS");
            seedClass(dsl, JAR, "com.example.Middle", "CLASS");
            seedSupertype(dsl, JAR, "com.example.Leaf", "com.example.Middle", "EXTENDS");
            seedSupertype(dsl, JAR, "com.example.Leaf", "com.example.Marker", "IMPLEMENTS");
            seedSupertype(dsl, JAR, "com.example.Middle", "com.example.Root", "EXTENDS");
            namedByASignature(dsl, "com.example.Leaf");

            assertThat(ancestorsOf(dsl, "com.example.Leaf")).containsExactly(
                "com.example.Leaf", "com.example.Marker", "com.example.Middle", "com.example.Root");
        });
    }

    /**
     * A pair two inheritance paths connect is one row. The recursion walks both paths, and a
     * consumer asking whether one class is another wants an answer and not a count of the routes to
     * it, so the duplicates collapse here rather than at every reader.
     */
    @Test
    void aDiamondIsOneRowPerPair() {
        withSources(dsl -> {
            seedClass(dsl, JAR, "com.example.Leaf", "CLASS");
            seedClass(dsl, JAR, "com.example.Left", "CLASS");
            seedClass(dsl, JAR, "com.example.Right", "CLASS");
            seedSupertype(dsl, JAR, "com.example.Leaf", "com.example.Left", "EXTENDS");
            seedSupertype(dsl, JAR, "com.example.Leaf", "com.example.Right", "IMPLEMENTS");
            seedSupertype(dsl, JAR, "com.example.Left", "com.example.Top", "IMPLEMENTS");
            seedSupertype(dsl, JAR, "com.example.Right", "com.example.Top", "IMPLEMENTS");
            namedByASignature(dsl, "com.example.Leaf");

            assertThat(ancestorsOf(dsl, "com.example.Leaf")).containsExactly(
                "com.example.Leaf", "com.example.Left", "com.example.Right", "com.example.Top");
        });
    }

    /**
     * A cycle no valid classfile could produce still terminates, with the names it reached. The
     * guard is deliberate rather than incidental: a malformed census is a capture defect to be
     * found by a short answer, never by a build that does not finish.
     */
    @Test
    void aCycleTerminatesInsteadOfSpinning() {
        withSources(dsl -> {
            seedClass(dsl, JAR, "com.example.A", "CLASS");
            seedClass(dsl, JAR, "com.example.B", "CLASS");
            seedSupertype(dsl, JAR, "com.example.A", "com.example.B", "EXTENDS");
            seedSupertype(dsl, JAR, "com.example.B", "com.example.A", "EXTENDS");
            namedByASignature(dsl, "com.example.A");

            assertThat(ancestorsOf(dsl, "com.example.A"))
                .containsExactly("com.example.A", "com.example.B");
        });
    }

    // ===== The seed =====

    /**
     * A class nothing names has no rows, however much hierarchy it declares. This is the whole of
     * what keeps the closure off the census's sideways growth, and it is the property a later
     * narrowing of the seed would still have to hold.
     */
    @Test
    void aClassNoCapturedSignatureNamesHasNoRowsAtAll() {
        withSources(dsl -> {
            seedClass(dsl, JAR, "com.example.Unasked", "CLASS");
            seedSupertype(dsl, JAR, "com.example.Unasked", "com.example.Root", "EXTENDS");

            assertThat(everyPair(dsl)).isEmpty();
        });
    }

    /**
     * The three relations a class can be named by all seed it. A parameter type, a return type and a
     * record component type are one rule here, so a reader never has to know which kind of member
     * mentioned the class it is asking about.
     */
    @Test
    void aParameterAReturnAndARecordComponentAllSeed() {
        withSources(dsl -> {
            seedClass(dsl, JAR, "com.example.Holder", "RECORD");
            seedMethod(dsl, JAR, "com.example.Holder", "read", "()Lcom/example/Returned;",
                Map.of("", "com.example.Returned"));
            seedMethod(dsl, JAR, "com.example.Holder", "take", "(Lcom/example/Taken;)V");
            seedMethodParameter(dsl, JAR, "com.example.Holder", "take", "(Lcom/example/Taken;)V", 0,
                Map.of("", "com.example.Taken"));
            seedRecordComponent(dsl, JAR, "com.example.Holder", "held",
                Map.of("", "com.example.Held"));

            assertThat(seededClasses(dsl)).contains(
                "com.example.Returned", "com.example.Taken", "com.example.Held");
        });
    }

    /**
     * Every position within a declared type seeds, not only its root. An element type is asked about
     * as often as the type that wraps it, so admitting only roots would be a guess about cost
     * written into the vocabulary; the case states it as the rule it is.
     */
    @Test
    void aClassNamedOnlyAtATypeArgumentPositionIsSeededToo() {
        withSources(dsl -> {
            seedClass(dsl, JAR, CARRIER, "CLASS");
            seedClass(dsl, JAR, "com.example.Element", "CLASS");
            seedSupertype(dsl, JAR, "com.example.Element", "com.example.Root", "IMPLEMENTS");
            seedMethod(dsl, JAR, CARRIER, "take", "(Ljava/util/List;)V");
            seedMethodParameter(dsl, JAR, CARRIER, "take", "(Ljava/util/List;)V", 0,
                Map.of("", "java.util.List", "0", "com.example.Element"));

            assertThat(ancestorsOf(dsl, "com.example.Element"))
                .containsExactly("com.example.Element", "com.example.Root");
        });
    }

    // ===== What the closure cannot answer =====

    /**
     * A supertype outside the census is a row, and nothing above it is. The scan drops nested
     * classes and the generated jOOQ package and nothing ships the JDK, so this is the ordinary end
     * of a chain rather than a broken fixture, and it is why a missing pair reads as
     * not-known-to-be-assignable and never as not-assignable.
     */
    @Test
    void aChainStopsAtTheFirstNameOutsideTheCensus() {
        withSources(dsl -> {
            seedClass(dsl, JAR, "com.example.Leaf", "CLASS");
            seedSupertype(dsl, JAR, "com.example.Leaf", "java.util.AbstractList", "EXTENDS");
            namedByASignature(dsl, "com.example.Leaf");

            assertThat(ancestorsOf(dsl, "com.example.Leaf"))
                .containsExactly("com.example.Leaf", "java.util.AbstractList");
        });
    }

    /**
     * Two classpath entries declaring one class are one edge. This is the constraint
     * {@code jvm_class_supertype} stated in prose against the closure that was here before: the
     * duplicate rows would double the frontier at every hop, and dropping the entry the row came
     * from is what stops them from doing so.
     */
    @Test
    void twoEntriesDeclaringOneClassContributeOneEdge() {
        withSources(dsl -> {
            seedSource(dsl, SECOND_JAR, "JAR");
            seedGraphSource(dsl, GRAPH, SECOND_JAR);
            seedClass(dsl, JAR, "com.example.Leaf", "CLASS");
            seedClass(dsl, SECOND_JAR, "com.example.Leaf", "CLASS");
            seedSupertype(dsl, JAR, "com.example.Leaf", "com.example.Root", "EXTENDS");
            seedSupertype(dsl, SECOND_JAR, "com.example.Leaf", "com.example.Root", "EXTENDS");
            namedByASignature(dsl, "com.example.Leaf");

            assertThat(ancestorsOf(dsl, "com.example.Leaf"))
                .containsExactly("com.example.Leaf", "com.example.Root");
        });
    }

    /** The graph partition, on a relation whose every arm scopes through it. */
    @Test
    void aSiblingGraphReadsNoAncestor() {
        withSources(dsl -> {
            seedGraph(dsl, "other");
            seedClass(dsl, JAR, "com.example.Leaf", "CLASS");
            seedSupertype(dsl, JAR, "com.example.Leaf", "com.example.Root", "EXTENDS");
            namedByASignature(dsl, "com.example.Leaf");

            assertThat(ancestorsOf(dsl, "com.example.Leaf")).hasSize(2);
            assertThat(everyPairIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String JAR = "classes.jar";
    private static final String SECOND_JAR = "shadowing.jar";
    private static final String CARRIER = "com.example.Carrier";

    /** One classpath entry, which is all this relation reads besides the graph it hangs off. */
    private static void withSources(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, JAR, "JAR");
            seedGraphSource(dsl, GRAPH, JAR);
            body.accept(dsl);
        });
    }

    /**
     * Puts a class into the seed the only way anything can: some captured signature names it. Every
     * case above that is about the closure rather than about the seed goes through here, so the
     * seeding is stated once and the cases stay about their own subject.
     */
    private static void namedByASignature(DSLContext dsl, String classFqn) {
        String descriptor = "(L" + classFqn.replace('.', '/') + ";)V";
        seedClass(dsl, JAR, CARRIER, "CLASS");
        seedMethod(dsl, JAR, CARRIER, "take", descriptor);
        seedMethodParameter(dsl, JAR, CARRIER, "take", descriptor, 0, Map.of("", classFqn));
    }

    /** Every type the class is known to be, ordered so a case can state them as written. */
    private static List<String> ancestorsOf(DSLContext dsl, String classFqn) {
        derive(dsl);
        var a = INTENT_JVM_ANCESTOR;
        return dsl.select(a.ANCESTOR_NAME)
            .from(a)
            .where(a.GRAPH_NAME.eq(GRAPH)).and(a.CLASS_NAME.eq(classFqn))
            .orderBy(a.ANCESTOR_NAME)
            .fetch(a.ANCESTOR_NAME);
    }

    /** The classes the seed admitted, which is every class with a row of its own. */
    private static List<String> seededClasses(DSLContext dsl) {
        derive(dsl);
        var a = INTENT_JVM_ANCESTOR;
        return dsl.selectDistinct(a.CLASS_NAME)
            .from(a)
            .where(a.GRAPH_NAME.eq(GRAPH))
            .orderBy(a.CLASS_NAME)
            .fetch(a.CLASS_NAME);
    }

    private static List<String> everyPair(DSLContext dsl) {
        return everyPairIn(dsl, GRAPH);
    }

    private static List<String> everyPairIn(DSLContext dsl, String graphName) {
        derive(dsl);
        var a = INTENT_JVM_ANCESTOR;
        return dsl.select(a.fields())
            .from(a)
            .where(a.GRAPH_NAME.eq(graphName))
            .orderBy(a.CLASS_NAME, a.ANCESTOR_NAME)
            .fetch(row -> row.get(a.CLASS_NAME) + " -> " + row.get(a.ANCESTOR_NAME));
    }
}
