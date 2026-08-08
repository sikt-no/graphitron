package no.sikt.graphitron.rewrite.capture;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.AttributedRegistry;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A booted fact store with one SDL fixture captured into it, for the tests that read the store.
 *
 * <p>Owns the store's lifetime so a test can query after capture, which is the one thing
 * {@link FactCapture#run} deliberately does not allow: in the pipeline the store dies with the
 * pass, because nothing is meant to read it yet.
 */
final class CapturedStore implements AutoCloseable {

    private final GraphitronModelStore store;
    private final TypeDefinitionRegistry registry;
    private final AttributedRegistry attributed;

    private CapturedStore(GraphitronModelStore store, TypeDefinitionRegistry registry,
                          AttributedRegistry attributed) {
        this.store = store;
        this.registry = registry;
        this.attributed = attributed;
    }

    /** Parses {@code sdl} into a file under {@code directory}, captures it, and returns the store. */
    static CapturedStore of(Path directory, String sdl) {
        var registry = registryOf(directory, sdl);
        var store = GraphitronModelStore.open();
        FactCapture.capture(store.dsl(), registry);
        return new CapturedStore(store, registry, null);
    }

    /**
     * The same, but through the attribution pipeline production runs, capturing the handle
     * production captures. The difference matters wherever a rewrite stands between the parse and
     * the capture: a bare parse would let capture's macro expansion mint what the rewrite has
     * already put there in the pipeline, and the store would agree with the model for the wrong
     * reason. {@link #attributed()} exposes both handles so a test can compare the two stages.
     */
    static CapturedStore ofPipeline(Path directory, String sdl) {
        Path file = write(directory, sdl);
        var ctx = new RewriteContext(
            List.of(SchemaInput.plain(file.toString())),
            directory, directory,
            TestConfiguration.DEFAULT_OUTPUT_PACKAGE, TestConfiguration.DEFAULT_JOOQ_PACKAGE);
        var attributed = TestSchemaHelper.attributedRegistry(ctx);
        var store = GraphitronModelStore.open();
        FactCapture.capture(store.dsl(), attributed.preSynthesisRegistry(), CatalogFacts.empty(),
            List.of(), TestSchemaHelper.nodeDeclaration(ctx));
        return new CapturedStore(store, attributed.preSynthesisRegistry(), attributed);
    }

    /** Just the parse, for callers that fill a store from something other than the SDL. */
    static TypeDefinitionRegistry registryOf(Path directory, String sdl) {
        return RewriteSchemaLoader.load(List.of(write(directory, sdl).toString()));
    }

    private static Path write(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    DSLContext dsl() {
        return store.dsl();
    }

    TypeDefinitionRegistry registry() {
        return registry;
    }

    /** The pipeline's own two handles; null unless this store came from {@link #ofPipeline}. */
    AttributedRegistry attributed() {
        return attributed;
    }

    @Override
    public void close() {
        store.close();
    }
}
