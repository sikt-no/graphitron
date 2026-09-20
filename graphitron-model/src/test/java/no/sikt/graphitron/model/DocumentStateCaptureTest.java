package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a reading does to the scope of each document it is accountable for.
 *
 * <p>One rule, asserted five ways: a reading brings each {@code (graph, source)} scope into line
 * with what that document now states. The interesting half is the documents that state nothing.
 * A file that stopped parsing and a file that left the corpus are both easy to leave out of the
 * list a gatherer walks, and leaving either out fails nowhere: the rows of the last good reading
 * simply stand, indefinitely, as though the author had never touched anything.
 *
 * <p>The instants are stated rather than taken from the clock. A sweep tells one reading's rows
 * from the last one's by them, so two readings a few microseconds apart is exactly the case where
 * a test would pass while proving nothing.
 */
class DocumentStateCaptureTest {

    private static final String GRAPH = "document-state";

    private static final LocalDateTime FIRST = LocalDateTime.parse("2024-01-01T10:00:00");
    private static final LocalDateTime SECOND = LocalDateTime.parse("2024-01-02T10:00:00");

    private static final String WIDGET = "type Widget { id: ID! name: String }\n";
    private static final String QUERY = "type Query { widgets: [Widget!]! }\n";

    /**
     * The skip, and the reason it is safe: the scope already holds what the document states, so the
     * reading leaves it alone rather than rewriting it to the same thing.
     *
     * <p>Asserted on the instant rather than on a row count, a rewrite to identical values being
     * indistinguishable from a skip by any other measure. The rows keeping the first reading's
     * instant is also the property the sweep has to tolerate: whatever else changes, a scope nobody
     * marked must not be swept.
     */
    @Test
    @DisplayName("a document whose bytes have not moved keeps the rows it already had")
    void anUnchangedDocumentIsLeftAlone() {
        withSeededStore(GRAPH, dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            read(dsl, directory, FIRST);
            var before = positions(dsl, directory, "widget.graphqls");

            read(dsl, directory, SECOND);

            assertThat(positions(dsl, directory, "widget.graphqls"))
                .as("the rows are still there after a reading that had nothing to say about them")
                .isEqualTo(before).isPositive();
            assertThat(instants(dsl, directory, "widget.graphqls"))
                .as("and they carry the reading that wrote them, not the one that skipped them")
                .containsExactly(FIRST);
            assertThat(claimedAt(dsl, directory, "widget.graphqls"))
                .as("the claim dates the transcription too, a reading that performed none having"
                    + " nothing to date")
                .isEqualTo(FIRST);
        });
    }

    /**
     * The other side of the same comparison: bytes that moved are transcribed again, so the skip
     * cannot quietly be a skip of everything.
     */
    @Test
    @DisplayName("a document whose bytes moved is transcribed again")
    void aChangedDocumentIsRewritten() {
        withSeededStore(GRAPH, dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            read(dsl, directory, FIRST);

            write(directory, "widget.graphqls", WIDGET.replace("name: String", "title: String"));
            read(dsl, directory, SECOND);

            assertThat(instants(dsl, directory, "widget.graphqls"))
                .as("the edited file is rewritten on this reading's instant")
                .containsExactly(SECOND);
        });
    }

    /**
     * A file the author has just broken. Its rows are what the file used to say, and the file no
     * longer says it, so they go.
     *
     * <p>The tempting implementation skips an unparsable document, having no registry to write
     * from, and that skip is what leaves the rows standing. A document that states nothing states
     * an empty registry, which is the same operation as any other reading rather than a second one.
     */
    @Test
    @DisplayName("a document that stopped parsing loses the rows of its last good reading")
    void anUnparsableDocumentLosesItsRows() {
        withSeededStore(GRAPH, dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            write(directory, "query.graphqls", QUERY);
            read(dsl, directory, FIRST);
            assertThat(positions(dsl, directory, "widget.graphqls")).isPositive();

            write(directory, "widget.graphqls", "type Widget { ");
            read(dsl, directory, SECOND);

            assertThat(positions(dsl, directory, "widget.graphqls"))
                .as("nothing of the broken file survives as though it were still true")
                .isZero();
            assertThat(positions(dsl, directory, "query.graphqls"))
                .as("and the file beside it is untouched by its neighbour's failure")
                .isPositive();
        });
    }

    /**
     * A file the author deleted. It is not in the configuration any more, so a reading that walks
     * only what the configuration names never reaches it and never sweeps it.
     *
     * <p>The source row goes too, and the order is the point: it is the provenance the rows hang
     * on, so a reading that forgot it first would orphan them instead of removing them.
     */
    @Test
    @DisplayName("a document the author deleted loses its rows and its source row")
    void aDeletedDocumentLosesItsRows() {
        withSeededStore(GRAPH, dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            write(directory, "query.graphqls", QUERY);
            read(dsl, directory, FIRST);
            assertThat(positions(dsl, directory, "widget.graphqls")).isPositive();

            delete(directory, "widget.graphqls");
            read(dsl, directory, SECOND);

            assertThat(positions(dsl, directory, "widget.graphqls"))
                .as("the deleted file's positions are gone")
                .isZero();
            assertThat(dsl.fetchCount(STORE_SOURCE,
                STORE_SOURCE.SOURCE_NAME.eq(name(directory, "widget.graphqls"))))
                .as("and so is the row that said the corpus had ever read it")
                .isZero();
            assertThat(positions(dsl, directory, "query.graphqls"))
                .as("the surviving file is untouched")
                .isPositive();
        });
    }

    /**
     * A file still on disk that the configuration has stopped naming. The same fact to every writer
     * as a deletion, the document being out of this graph's corpus either way, and a different fact
     * to the store: the file is still there, and another graph may be reading it.
     */
    @Test
    @DisplayName("a document the configuration dropped loses its rows and keeps its source row")
    void aDeconfiguredDocumentLosesItsRows() {
        withSeededStore(GRAPH, dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "widget.graphqls", WIDGET);
            write(directory, "query.graphqls", QUERY);
            read(dsl, directory, FIRST, "*.graphqls");
            assertThat(positions(dsl, directory, "widget.graphqls")).isPositive();

            read(dsl, directory, SECOND, "query.graphqls");

            assertThat(positions(dsl, directory, "widget.graphqls"))
                .as("a document this graph no longer reads leaves no rows behind under it")
                .isZero();
            assertThat(dsl.fetchCount(STORE_GRAPH_SOURCE,
                STORE_GRAPH_SOURCE.GRAPH_NAME.eq(GRAPH)
                    .and(STORE_GRAPH_SOURCE.SOURCE_NAME.eq(name(directory, "widget.graphqls")))))
                .as("nor a claim on it")
                .isZero();
            assertThat(dsl.fetchCount(STORE_SOURCE,
                STORE_SOURCE.SOURCE_NAME.eq(name(directory, "widget.graphqls"))))
                .as("but the file is still a file, and the store is shared with graphs that may"
                    + " still be reading it")
                .isOne();
        });
    }

    // ------------------------------------------------------------------------------- the reading

    /** One reading of everything the configuration names, which is what a run does. */
    private static void read(DSLContext dsl, Path baseDir, LocalDateTime readAt) {
        read(dsl, baseDir, readAt, "*.graphqls");
    }

    private static void read(DSLContext dsl, Path baseDir, LocalDateTime readAt, String pattern) {
        var graph = new GraphIdentity(GRAPH, baseDir);
        var documents = GraphQLSourceCapture.capture(dsl, graph, corpus(baseDir, pattern), readAt);
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
        GraphQLSourceCapture.reclaim(dsl, documents);
    }

    private static SubjectConfig corpus(Path baseDir, String pattern) {
        return SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern(pattern)), List.of("graphqls")));
    }

    // --------------------------------------------------------------------------- what it wrote

    private static int positions(DSLContext dsl, Path directory, String file) {
        return dsl.fetchCount(GRAPHQL_AST_ENTRY, GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH)
            .and(GRAPHQL_AST_ENTRY.SOURCE_NAME.eq(name(directory, file))));
    }

    private static List<LocalDateTime> instants(DSLContext dsl, Path directory, String file) {
        return dsl.selectDistinct(GRAPHQL_AST_ENTRY.TOUCHED_AT).from(GRAPHQL_AST_ENTRY)
            .where(GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH))
            .and(GRAPHQL_AST_ENTRY.SOURCE_NAME.eq(name(directory, file)))
            .fetch(GRAPHQL_AST_ENTRY.TOUCHED_AT);
    }

    /** When this graph last says it transcribed the file, off the claim rather than off the rows. */
    private static LocalDateTime claimedAt(DSLContext dsl, Path directory, String file) {
        return dsl.select(STORE_GRAPH_SOURCE.READ_AT).from(STORE_GRAPH_SOURCE)
            .where(STORE_GRAPH_SOURCE.GRAPH_NAME.eq(GRAPH))
            .and(STORE_GRAPH_SOURCE.SOURCE_NAME.eq(name(directory, file)))
            .fetchOne(STORE_GRAPH_SOURCE.READ_AT);
    }

    private static String name(Path directory, String file) {
        return directory.resolve(file).toString();
    }

    // ------------------------------------------------------------------------------- the corpus

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path directory, String name) {
        try {
            Files.delete(directory.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("document-state");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
