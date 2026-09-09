package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.jooq.DSLContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * Reads a graph's schema files and writes what each one says.
 *
 * <p>This gatherer finds its own inputs. It is handed the configuration rather than a parsed
 * document, so the per-file parses exist here where the rows are keyed by the file they came from,
 * and no caller can hand over a merged registry that has already dropped one of two documents
 * declaring a name.
 *
 * <p>It reads what the author wrote and nothing else. Loading rewrites that stamp a tag or append a
 * note onto every element of a configured source are not applied: those are facts about the build's
 * configuration, they are already recorded as such in {@code store_graph_schema_input}, and a
 * consumer that wants the decorated schema joins the two.
 */
public final class SdlCapture {

    private SdlCapture() {}

    /**
     * Makes {@code graph}'s SDL rows be what its configured schema files now say.
     *
     * <p>The instant is the caller's: the graph row, the source rows and every entry row date the
     * same reading, which is what the entries' sweep tells readings apart by.
     */
    public static void capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                               LocalDateTime readAt) {
        writeGraph(dsl, graph, readAt);
        for (var document : SchemaLoader.parsePerSource(schemaFiles(config, graph.baseDir()))
                .perSource()) {
            writeSource(dsl, document.sourceName(), readAt);
            SdlEntries.write(dsl, graph.name(), document.sourceName(), document.registry(), readAt);
        }
    }

    /**
     * The files the recipe resolves to, or none.
     *
     * <p>A pattern matching nothing and a scanner that failed both yield no files here rather than
     * a refusal, because a gatherer that throws gathers nothing: a run whose recipe is half broken
     * still has the other half's facts, and pronouncing on the recipe is the caller's.
     */
    private static List<SchemaSource.File> schemaFiles(SubjectConfig config, Path baseDir) {
        return config.recipe()
            .filter(recipe -> !recipe.bindings().isEmpty())
            .map(recipe -> recipe.expand(baseDir))
            .filter(SchemaRecipe.Expansion.Resolved.class::isInstance)
            .map(SchemaRecipe.Expansion.Resolved.class::cast)
            .map(resolved -> resolved.matches().stream()
                .map(match -> match.input().source())
                .filter(SchemaSource.File.class::isInstance)
                .map(SchemaSource.File.class::cast)
                .toList())
            .orElseGet(List::of);
    }

    private static void writeGraph(DSLContext dsl, GraphIdentity graph, LocalDateTime readAt) {
        var t = STORE_GRAPH;
        dsl.insertInto(t, t.GRAPH_NAME, t.BASE_DIR, t.LAST_CAPTURED)
            .values(graph.name(), graph.baseDir().toString(), readAt)
            .onDuplicateKeyUpdate()
            .set(t.BASE_DIR, graph.baseDir().toString())
            .set(t.LAST_CAPTURED, readAt)
            .execute();
    }

    /**
     * The registry row every one of this file's rows hangs its {@code source_ref} on. Written from
     * the parse's own source name, so the bundled directive vocabulary gets a row on the same terms
     * as an author's file: it is a document this reading read.
     */
    private static void writeSource(DSLContext dsl, String sourceName, LocalDateTime readAt) {
        var t = STORE_SOURCE;
        var mtime = modifiedAt(sourceName);
        dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.MTIME, t.LAST_SEEN, t.READ_AT)
            .values(sourceName, "SCHEMA_FILE", mtime, readAt, readAt)
            .onDuplicateKeyUpdate()
            .set(t.MTIME, mtime)
            .set(t.LAST_SEEN, readAt)
            .set(t.READ_AT, readAt)
            .execute();
    }

    /** When the file was last written, or null for a source that is not a file on disk. */
    private static LocalDateTime modifiedAt(String sourceName) {
        try {
            return LocalDateTime.ofInstant(
                Files.getLastModifiedTime(Path.of(sourceName)).toInstant(),
                ZoneId.systemDefault()).withNano(0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
