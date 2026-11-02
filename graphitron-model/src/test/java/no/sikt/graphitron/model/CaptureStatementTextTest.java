package no.sikt.graphitron.model;

import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A capture's statements read the same from one capture to the next, so H2 plans each once and
 * reuses the plan: the instant a reading marks its rows with is a bind parameter, never a literal
 * rendered into the text, where every capture would make it a statement H2 has not seen.
 */
class CaptureStatementTextTest {

    private static final String GRAPH = "statement-text";

    /**
     * Enough to run the writers that mark and sweep: types and fields for the anchors, a path the
     * field paths stage walks, and written defects, so the entry-defect merge and its sweep run with
     * rows.
     */
    private static final String SDL = """
        type Query {
          films: [Film] @service(service: {className: "example.Missing", method: "films"})
          film(id: ID): Film
        }
        type Film @table(name: "film") {
          title: String
          language: Language @reference(path: [{key: "no_such_key"}])
        }
        type Language @table(name: "language") { name: String }
        """;

    @Test
    @DisplayName("a second capture of one schema issues no statement text the first did not")
    void aSecondCaptureRepeatsTheFirstsStatements(@TempDir Path directory) {
        try {
            Files.writeString(directory.resolve("schema.graphqls"), SDL);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var config = SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
        var first = LocalDateTime.of(2026, 1, 1, 12, 0);

        ThreadConfinedStore.run(dsl -> {
            var firstTexts = capture(dsl, directory, config, first);
            var secondTexts = capture(dsl, directory, config, first.plusMinutes(1));

            assertThat(firstTexts).as("the capture ran statements").isNotEmpty();
            assertThat(firstTexts)
                .as("statements carrying a timestamp literal, the reading's instant inlined")
                .noneMatch(sql -> sql.contains("timestamp '"));
            var unseen = new LinkedHashSet<>(secondTexts);
            unseen.removeAll(firstTexts);
            assertThat(unseen)
                .as("statements the second capture issued in a text the first did not")
                .isEmpty();
        });
    }

    /** Every distinct statement text a capture at {@code readAt} issues, binds left as markers. */
    private static Set<String> capture(DSLContext dsl, Path directory, SubjectConfig config,
                                       LocalDateTime readAt) {
        var texts = new LinkedHashSet<String>();
        var recording = DSL.using(dsl.configuration().derive(new DefaultExecuteListenerProvider(
            new ExecuteListener() {
                @Override
                public void executeStart(ExecuteContext ctx) {
                    if (ctx.sql() != null) {
                        texts.add(ctx.sql());
                    }
                }
            })));
        ModelCapture.capture(recording, new GraphIdentity(GRAPH, directory), config, List.of(), null,
            readAt);
        return texts;
    }
}
