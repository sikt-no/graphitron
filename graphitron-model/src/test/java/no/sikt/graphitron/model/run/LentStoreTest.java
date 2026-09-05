package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.derive.ClassifiedRun;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arm of {@link CapturePort} that captures into a store its caller opened: what a dev session
 * runs on, and the whole of what "a session opens one store instead of two" comes to.
 *
 * <p>Its two properties are invisible to an assertion over anything a pass produces, which is why
 * they are pinned here rather than left to the goals that use the arm. First, the facts land in the
 * <em>lender's</em> store, so the session's language server and MCP readers are looking at rows the
 * passes wrote; a port that quietly opened one of its own would still capture, still answer its own
 * reads, and leave those readers on an empty database. Second, {@link RunStore.Borrowed#close()}
 * does nothing, because the lender closes what the lender opened. An edit making it close the store
 * would take a session's readers down after its first pass, and no assertion about generated output
 * can see it happen.
 *
 * <p>Here rather than above the line because nothing in it needs a generator: the arm, the store
 * and the capture are all this module's, which is itself a thing the fact tier's own module made
 * possible.
 */
class LentStoreTest {

    private static final String FILM_SDL = """
        type Query { films: [Film!]! }
        type Film { title: String }
        """;

    private static final String ACTOR_SDL = """
        type Query { actors: [Actor!]! }
        type Actor { name: String }
        """;

    /**
     * Both captures against the lent store, and the lender still holding it afterwards. The second
     * capture is what makes this the session's shape rather than a build's: a session's rounds
     * recapture the same graph, so the arm has to reconcile the previous round's rows on a store it
     * did not open, and the type census is what says it did (Actor arrived, Film stood down).
     */
    @Test
    @DisplayName("a port over a lent store captures every pass into it and leaves it open")
    void capturesEveryPassIntoTheLentStore(@TempDir Path tmp) {
        try (var lent = FactStores.fileBacked(tmp.resolve("home"))) {
            try (var port = CapturePort.over(lent)) {
                port.capture(request(tmp, FILM_SDL));

                assertThat(typeNames(lent.dsl()))
                    .as("the first pass's facts are in the store the caller opened")
                    .contains("Film");

                port.capture(request(tmp, ACTOR_SDL));

                assertThat(typeNames(lent.dsl()))
                    .as("so are the second's, reconciled against the first on the lent store")
                    .contains("Actor").doesNotContain("Film");
            }

            assertThat(typeNames(lent.dsl()))
                .as("closing the port leaves the lender's store answering, which is what keeps a "
                    + "session's readers alive across its passes")
                .contains("Actor");
        }
    }

    /**
     * The demotion arm reached the way only a lent store reaches it. A graph name recorded against
     * another checkout refuses this run, and the refusal has to leave the lent store exactly as its
     * owner left it: this is the one case where the run captures somewhere the caller cannot see, so
     * a fallback that wrote through the lent handle anyway would corrupt another checkout's
     * partition on a store the lender is still reading.
     */
    @Test
    @DisplayName("a lent store recorded against another checkout is left alone, and the run goes on")
    void aRefusedGraphLeavesTheLentStoreAlone(@TempDir Path tmp) {
        Path home = tmp.resolve("home");
        Path owner = tmp.resolve("owner");
        Path impostor = tmp.resolve("impostor");

        try (var first = FactStores.fileBacked(home);
             var port = CapturePort.over(first)) {
            port.capture(request(owner, FILM_SDL));
        }

        try (var lent = FactStores.fileBacked(home)) {
            assertThat(lent.warm()).as("the second open meets the first run's rows").isTrue();

            try (var port = CapturePort.over(lent)) {
                List<String> captured = port.captureAndRead(request(impostor, ACTOR_SDL),
                    (store, detections) -> typeNames(store.dsl()));

                assertThat(captured)
                    .as("the run still captured, on a private store of the port's own")
                    .contains("Actor");
            }

            assertThat(typeNames(lent.dsl()))
                .as("and the partition the other checkout owns is untouched")
                .contains("Film").doesNotContain("Actor");
            assertThat(lent.dsl().select(STORE_GRAPH.BASE_DIR).from(STORE_GRAPH)
                    .where(STORE_GRAPH.GRAPH_NAME.eq(CapturedStore.GRAPH))
                    .fetchOne(0, String.class))
                .as("including the row that says who owns it")
                .isEqualTo(owner.toAbsolutePath().normalize().toString());
        }
    }

    /**
     * One pass's whole capture under {@link CapturedStore#GRAPH}, from {@code baseDir}: the graph
     * coordinate is what the ownership check reads, so a case that wants a refusal states it by
     * handing a different directory rather than by arranging one.
     */
    private static CaptureRequest request(Path baseDir, String sdl) {
        var registry = CapturedStore.registryOf(baseDir, sdl);
        return new CaptureRequest(CapturedStore.graph(baseDir), SubjectConfig.none(), registry,
            SchemaAssembly.of(registry), SdlVerdicts.none(), CapturedStore.attributionOf(baseDir),
            null, List.of(), Map.of(), ClassifiedRun.absent());
    }

    /** The captured type census, which is the cheapest fact that says which document landed. */
    private static List<String> typeNames(DSLContext dsl) {
        return dsl.select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
            .fetch(GRAPHQL_TYPE.TYPE_NAME);
    }
}
