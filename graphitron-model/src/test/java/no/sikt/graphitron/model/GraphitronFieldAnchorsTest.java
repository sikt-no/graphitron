package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFAULT_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PIVOT_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static no.sikt.graphitron.model.test.ElementOrder.writtenAt;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The field site's anchors, derived rather than walked.
 *
 * <p>Four directives the specification admits at {@code FIELD_DEFINITION} and nowhere else moved
 * here, which is what lets each of them derive from one entry relation. A directive also legal on
 * an input object's field has its applications split across two, an input object's field being an
 * input value in the entry stratum rather than a field, and its anchor is a union with a
 * parent-kind test rather than a select. That distinction is the reason this batch is these four.
 *
 * <p>What the cases are about is the same two things the type site's are, plus one the field site
 * adds. The choosing, which here has to reach through two declarations to find merge order. The
 * absence, where a join's polarity is the relation's own shape. And the ordering, which is the one
 * value the two strata have to agree about rather than a row one of them declines.
 */
class GraphitronFieldAnchorsTest {

    private static final String GRAPH = "field-anchors";

    /**
     * The ordering, which is a value the two strata have to agree about rather than a row one of
     * them declines. An element's position is the index the author wrote it at, and the anchor
     * states the same index, so a reader sorting by it sorts the way the schema reads.
     *
     * <p>It used to be more than that. The entry admitted an element naming no field, the anchor
     * dropped it, and the anchor renumbered what was left so the gap closed. Under the rule that an
     * entry holds what the directive definition admits, a nameless element is not an element of a
     * {@code FieldSort} list and its whole application is not transcribed, so no gap can open and
     * the anchor copies the index rather than recomputing it. {@code EntryLegalityTest} holds the
     * case this one used to.
     */
    @Test
    @DisplayName("the anchor states the index the author wrote each order element at")
    void theAnchorKeepsTheWrittenPositions(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            type Query {
              films: [Film!]
                @defaultOrder(fields: [
                  {name: "title"},
                  {name: "released", direction: DESC},
                  {name: "rating", collate: "xdanish_ai"}
                ])
            }
            type Film { title: String, released: String, rating: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var e = GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
            assertThat(dsl.select(writtenAt(e), e.NAME_REF).from(e).where(e.GRAPH_NAME.eq(GRAPH))
                    .orderBy(writtenAt(e)).fetch().map(Record::intoList))
                .as("the entry keeps every element where the author wrote it")
                .containsExactly(List.of(0, "title"), List.of(1, "released"),
                    List.of(2, "rating"));

            var t = GRAPHITRON_DEFAULT_ORDER_FIELD_ENTRY;
            assertThat(dsl.select(t.POSITION, t.NAME_REF).from(t).where(t.GRAPH_NAME.eq(GRAPH))
                    .orderBy(t.POSITION).fetch().map(Record::intoList))
                .as("and the anchor states the same indices, the two strata agreeing about the "
                    + "order as they agree about the rows")
                .containsExactly(List.of(0, "title"), List.of(1, "released"),
                    List.of(2, "rating"));
        });
    }

    /**
     * The polarity at a site whose payload is the fact. Both of {@code @pivot}'s columns are NOT
     * NULL, so an application naming neither asserts nothing and draws no row, where the walk said
     * the same thing by returning before it wrote.
     */
    @Test
    @DisplayName("a pivot naming neither column writes no row")
    void aPivotNamingNeitherColumnWritesNoRow(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            type Query {
              spread: [Film!] @pivot(on: "category", value: "total")
              empty: [Film!] @pivot
            }
            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_PIVOT_ENTRY;
            assertThat(dsl.select(t.FIELD_NAME, t.ON_COLUMN, t.VALUE_COLUMN).from(t)
                    .where(t.GRAPH_NAME.eq(GRAPH)).fetch().map(Record::intoList))
                .as("the application that named its columns is a row; the one that named none is "
                    + "not, the grain having nothing to hold for it")
                .containsExactly(List.of("spread", "category", "total"));
        });
    }

    /**
     * The other polarity, at a site where every column is optional because the directive deduces
     * what the author leaves out. A bare application is still a request for a connection, so the
     * row exists and says nothing, which is the difference between deducing a page size and not
     * having been asked for a connection at all.
     */
    @Test
    @DisplayName("a bare connection request is a row that states nothing")
    void aBareConnectionRequestIsARowStatingNothing(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            type Query {
              films: [Film!] @asConnection
              actors: [Film!] @asConnection(defaultFirstValue: 25)
              plain: [Film!]
            }
            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_CONNECTION_ENTRY;
            assertThat(dsl.select(t.FIELD_NAME, t.DEFAULT_FIRST_VALUE).from(t)
                    .where(t.GRAPH_NAME.eq(GRAPH)).orderBy(t.FIELD_NAME).fetch()
                    .map(Record::intoList))
                .as("both applications are rows and the bare one carries no page size; the field "
                    + "that applied nothing has no row, which is what tells the two apart")
                .containsExactly(List.of("actors", 25), Arrays.asList("films", null));
        });
    }

    /**
     * The choosing, at the grain that makes it reachable. A field declared twice is retained by the
     * registry rather than refused, so one coordinate can carry two applications, and a
     * coordinate-keyed anchor admits one of them. Merge order picks it, which is the declaration's
     * order and not the file's.
     */
    @Test
    @DisplayName("a field declared twice keeps the base declaration's application")
    void theBaseDeclarationWinsARedeclaredField(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            extend type Query { films: [Film!] @asConnection(defaultFirstValue: 99) }

            type Query { films: [Film!] @asConnection(defaultFirstValue: 25) }

            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var e = GRAPHITRON_AST_CONNECTION_ENTRY;
            assertThat(dsl.select(e.DEFAULT_FIRST_VALUE).from(e).where(e.GRAPH_NAME.eq(GRAPH))
                    .fetch(e.DEFAULT_FIRST_VALUE))
                .as("both declarations reached capture and both applications are entries; without "
                    + "this the case below could hold because the registry kept only one")
                .containsExactlyInAnyOrder(25, 99);
            var t = GRAPHITRON_CONNECTION_ENTRY;
            assertThat(dsl.select(t.DEFAULT_FIRST_VALUE).from(t).where(t.GRAPH_NAME.eq(GRAPH))
                    .fetch(t.DEFAULT_FIRST_VALUE))
                .as("one row, and it is the base declaration's, however the file is laid out")
                .containsExactly(25);
        });
    }

    /**
     * The facts this gatherer writes, without the assembly that raises the problem rows. The
     * corpora above are ones an author can write and a toolchain reports on, and capture states
     * what they say either way; asking for the problems too would mean assembling first, which is
     * a different question from what the rows are.
     */
    private static void read(DSLContext dsl, Path baseDir) {
        SdlCapture.captureFacts(dsl, new GraphIdentity(GRAPH, baseDir),
            SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            LocalDateTime.now());
    }

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
