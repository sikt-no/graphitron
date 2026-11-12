package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.ClasspathFixtures;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.CODE_CLASS;
import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_READ_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which class backs a type, read by graphs whose classpaths differ, sharing one store.
 *
 * <p>What each graph's classpath makes of the one schema, {@code shapes/type-backing-partition}, is
 * claimed by the documents reading it over the corpus and over the classpath fixtures:
 * {@code defects/type-backing-partition-corpus}, {@code -library-named}, {@code -library-nameless}
 * and {@code scenarios/type-backing-partition-corpus-and-library}. The population reads each in a
 * store of its classpath's own. What is left here is the partition: the graphs captured into one
 * store, each answering what it answers alone, so a graph is grounded and hops on its own classpath
 * only.
 *
 * <p>Order is part of it. The corpus-and-library graph shares the library's entry with the library
 * graphs and its corpus entries with the corpus graph, and an entry another graph has already read
 * is not read again, so whichever graph reads an entry first writes the rows every later graph
 * reads. Those rows have to be what the entry declares, whatever was read beside it, or a graph read
 * first decides what the ones after it answer.
 */
class TypeBackingPartitionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @TempDir
    Path tmp;

    private static final String OWN = "defects/type-backing-partition-corpus";
    private static final String NAMED = "defects/type-backing-partition-library-named";
    private static final String NAMELESS = "defects/type-backing-partition-library-nameless";
    private static final String BOTH = "scenarios/type-backing-partition-corpus-and-library";

    @Test
    @DisplayName("a graph is grounded and hops on its own classpath only, beside graphs of other classpaths")
    void aGraphIsGroundedAndHopsOnItsOwnMembershipOnly() {
        assertEachAnswersAsAlone(graphs("own", OWN, "named", NAMED, "nameless", NAMELESS, "both", BOTH));
    }

    @Test
    @DisplayName("a graph reading an entry first does not decide what a later graph on that entry answers")
    void aGraphReadFirstDoesNotDecideALaterOnesAnswer() {
        assertEachAnswersAsAlone(graphs("both", BOTH, "named", NAMED));
    }

    @Test
    @DisplayName("what the store holds for an entry read beside others is what it holds read alone")
    void anEntrysRowsAreTheEntrysAlone() {
        var library = ClasspathFixtures.entries("library-named").getFirst().path().toString();
        var jooq = CatalogInput.of(TestRunContext.of());
        try (var captured = CapturedStore.ownStoreOfDocument(tmp, BOTH, "both", jooq,
                ClasspathCorpus.entries())) {
            assertThat(declared(captured.dsl(), library))
                .as("the library's entry read after the corpus, against the library read alone")
                .isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(FACTS.read(NAMED,
                    alone -> declared(alone.handle().dsl(), library)));
        }
    }

    /** Captures {@code graphs} into one store in the order given, and compares each to itself alone. */
    private void assertEachAnswersAsAlone(Map<String, String> graphs) {
        var jooq = CatalogInput.of(TestRunContext.of());
        var documents = List.copyOf(graphs.entrySet());
        try (var captured = CapturedStore.ownStoreOfDocument(tmp, documents.getFirst().getValue(),
                documents.getFirst().getKey(), jooq, ClasspathCorpus.entries())) {
            documents.stream().skip(1).forEach(graph ->
                captured.andDocument(graph.getValue(), graph.getKey(), jooq, ClasspathCorpus.entries()));

            graphs.forEach((graph, document) -> assertThat(backings(captured.dsl(), graph))
                .as("%s read in the order %s, against %s read alone", graph, graphs.keySet(), document)
                .containsExactlyInAnyOrderElementsOf(FACTS.read(document,
                    alone -> backings(alone.handle().dsl(), alone.handle().graphName()))));
        }
    }

    /** Graph names and the documents they read, in the order given. */
    private static Map<String, String> graphs(String... pairs) {
        var graphs = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            graphs.put(pairs[i], pairs[i + 1]);
        }
        return graphs;
    }

    private static List<String> backings(DSLContext dsl, String graph) {
        var b = GRAPHITRON_TYPE_BACKING;
        return dsl.select(b.TYPE_NAME, b.CLASS_NAME, b.DECLARED_VIA)
            .from(b)
            .where(b.GRAPH_NAME.eq(graph))
            .fetch(r -> r.value1() + "=" + r.value2() + " " + r.value3());
    }

    /** The classes, methods and read slots the store holds under one entry. */
    private static List<String> declared(DSLContext dsl, String source) {
        var c = CODE_CLASS;
        var m = CODE_METHOD;
        var s = CODE_READ_SLOT;
        var rows = new ArrayList<String>();
        rows.addAll(dsl.select(c.CLASS_NAME).from(c).where(c.SOURCE_NAME.eq(source))
            .fetch(r -> "class " + r.value1()));
        rows.addAll(dsl.select(m.CLASS_NAME, m.METHOD_NAME, m.DESCRIPTOR).from(m)
            .where(m.SOURCE_NAME.eq(source))
            .fetch(r -> "method " + r.value1() + "." + r.value2() + r.value3()));
        rows.addAll(dsl.select(s.CLASS_NAME, s.SLOT_NAME, s.ELEMENT_CLASS).from(s)
            .where(s.SOURCE_NAME.eq(source))
            .fetch(r -> "slot " + r.value1() + "." + r.value2() + " " + r.value3()));
        return rows;
    }
}
