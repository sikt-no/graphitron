package no.sikt.graphitron.rewrite.capture;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
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

    private CapturedStore(GraphitronModelStore store, TypeDefinitionRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    /** Parses {@code sdl} into a file under {@code directory}, captures it, and returns the store. */
    static CapturedStore of(Path directory, String sdl) {
        var registry = registryOf(directory, sdl);
        var store = GraphitronModelStore.open();
        FactCapture.capture(store.dsl(), registry);
        return new CapturedStore(store, registry);
    }

    /** Just the parse, for callers that fill a store from something other than the SDL. */
    static TypeDefinitionRegistry registryOf(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return RewriteSchemaLoader.load(List.of(file.toString()));
    }

    DSLContext dsl() {
        return store.dsl();
    }

    TypeDefinitionRegistry registry() {
        return registry;
    }

    @Override
    public void close() {
        store.close();
    }
}
