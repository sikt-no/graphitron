package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which class backs a type, read by graphs whose classpaths differ, sharing one store.
 *
 * <p>What each graph's classpath makes of the one schema, {@code shapes/type-backing-partition}, is
 * claimed by the documents reading it over the corpus and over the classpath fixtures:
 * {@code defects/type-backing-partition-corpus}, {@code -library-named}, {@code -library-nameless}
 * and {@code scenarios/type-backing-partition-corpus-and-library}. The population reads each in a
 * store of its classpath's own. What is left here is the partition: the corpus graph and the two
 * library graphs captured into one store, in that order, each answering what it answers alone, so a
 * graph is grounded and hops on its own classpath only.
 *
 * <p>The corpus-and-library graph is not among them. A reading resolves a class name two entries
 * declare in classpath order, and an entry another graph has already read into the store is not read
 * again, so beside a graph that read the corpus first its answer is not the one it gives alone.
 */
class TypeBackingPartitionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @TempDir
    Path tmp;

    /** Each graph of the shared store, by the document it reads. */
    private static final Map<String, String> GRAPHS = graphs();

    private static Map<String, String> graphs() {
        var graphs = new LinkedHashMap<String, String>();
        graphs.put("own", "defects/type-backing-partition-corpus");
        graphs.put("named", "defects/type-backing-partition-library-named");
        graphs.put("nameless", "defects/type-backing-partition-library-nameless");
        return graphs;
    }

    @Test
    @DisplayName("a graph is grounded and hops on its own classpath only, beside graphs of other classpaths")
    void aGraphIsGroundedAndHopsOnItsOwnMembershipOnly() {
        var jooq = CatalogInput.of(TestRunContext.of());
        var documents = List.copyOf(GRAPHS.entrySet());
        try (var captured = CapturedStore.ownStoreOfDocument(tmp, documents.getFirst().getValue(),
                documents.getFirst().getKey(), jooq, ClasspathCorpus.entries())) {
            documents.stream().skip(1).forEach(graph ->
                captured.andDocument(graph.getValue(), graph.getKey(), jooq, ClasspathCorpus.entries()));

            GRAPHS.forEach((graph, document) -> assertThat(backings(captured.dsl(), graph))
                .as("%s beside the others, against %s read alone", graph, document)
                .containsExactlyInAnyOrderElementsOf(FACTS.read(document,
                    alone -> backings(alone.handle().dsl(), alone.handle().graphName()))));
        }
    }

    private static List<String> backings(DSLContext dsl, String graph) {
        var b = GRAPHITRON_TYPE_BACKING;
        return dsl.select(b.TYPE_NAME, b.CLASS_NAME, b.DECLARED_VIA)
            .from(b)
            .where(b.GRAPH_NAME.eq(graph))
            .fetch(r -> r.value1() + "=" + r.value2() + " " + r.value3());
    }
}
