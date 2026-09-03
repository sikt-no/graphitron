package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.derive.ClassifiedRun;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.model.run.CapturePort;
import no.sikt.graphitron.model.capture.FactCapture;
import no.sikt.graphitron.model.run.SubjectConfig;

/**
 * The capture's window: how long the store a run captured into stays open, and who may read it
 * while it is.
 *
 * <p>The subject is a lifecycle rather than a signature. Store ownership used to sit inside the
 * capture pass, which opened, filled, detected and closed, so a later phase wanting a captured
 * fact had to reopen the store to ask a question the pass could have answered. It now sits with
 * the caller: {@link FactCapture#runAndRead} hands a {@link StoreHandle} to a continuation and
 * closes the store when that returns, which is what lets the build pipeline validate and plan
 * against the same rows the capture just wrote. The dev loop has always had this shape, opening a
 * session store and running generator passes inside it; these cases pin that the build path now
 * agrees.
 *
 * <p>Both halves are pinned, because either alone would be satisfied by a mistake. A window that
 * opens but never closes leaks the file lock a workspace shares; a window that closes before the
 * continuation runs hands out a handle that answers nothing.
 */
@UnitTier
class CaptureWindowTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film { title: String }
        """;

    private static final String GRAPH_NAME = "CaptureWindowTest";

    @Test
    @DisplayName("the continuation reads the rows the capture just wrote")
    void theHandleAnswersInsideTheWindow(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");

        var typeNames = captureAndRead(directory, tmp, (store, detections) -> {
            assertThat(store.graphName())
                .as("the handle is scoped to the graph this run captured under")
                .isEqualTo(GRAPH_NAME);
            return store.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .where(GRAPHQL_TYPE.GRAPH_NAME.eq(store.graphName()))
                .orderBy(GRAPHQL_TYPE.TYPE_NAME)
                .fetch(0, String.class);
        });

        assertThat(typeNames)
            .as("capture has run by the time the continuation does, so its rows are there to read")
            .contains("Film", "Query");
    }

    @Test
    @DisplayName("the value the continuation produces is the value the run returns")
    void theContinuationsValueIsTheRunsValue(@TempDir Path tmp) {
        String produced = captureAndRead(tmp.resolve("graphitron-model"), tmp,
            (store, detections) -> "planned");

        assertThat(produced).isEqualTo("planned");
    }

    @Test
    @DisplayName("the store closes when the continuation returns")
    void theWindowClosesAfterTheContinuation(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");

        // The handle deliberately escapes the window here, which is the one thing a caller must
        // not do with it: the store is a workspace-shared file, and a run holding it past its own
        // work is warmth every other module pays for. Reading through the escaped handle is what
        // proves the close happened rather than being asserted about the code.
        StoreHandle escaped = captureAndRead(directory, tmp, (store, detections) -> store);

        assertThatThrownBy(() -> escaped.dsl().fetchCount(GRAPHQL_TYPE))
            .as("the connection the handle was built over is closed with the window")
            .isInstanceOf(Exception.class);
    }

    private static <T> T captureAndRead(Path directory, Path scratch,
                                        CapturePort.AfterCapture<T> after) {
        var registry = CapturedStore.registryOf(scratch, GRAPH_NAME, SDL);
        return FactCapture.runAndRead(directory,
            CapturedStore.graph(scratch, GRAPH_NAME),
            SubjectConfig.none(),
            registry,
            SchemaAssembly.of(registry),
            SdlVerdicts.none(),
            CapturedStore.attributionOf(scratch, GRAPH_NAME),
            null,
            List.of(),
            ClassifiedRun.absent(),
            after);
    }
}
