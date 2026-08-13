package no.sikt.graphitron.rewrite.capture;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.AttributedRegistry;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        FactCapture.capture(store.dsl(), graph(directory), FactCapture.SubjectConfig.none(),
            registry, attributionOf(directory));
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
        return ofPipeline(directory, sdl, null);
    }

    /**
     * {@link #ofPipeline(Path, String)} with a tag on the input, so {@code TagLinkSynthesiser}
     * fires and its synthesised source name enters the registry capture walks. The only fixture in
     * the tree that puts that sentinel in front of capture's stamp lookup.
     */
    static CapturedStore ofPipeline(Path directory, String sdl, String tag) {
        Path file = write(directory, sdl);
        var input = new SchemaInput(SchemaSource.file(file), Optional.ofNullable(tag), Optional.empty());
        var ctx = new RewriteContext(
            List.of(input),
            directory, "CapturedStore", directory,
            TestConfiguration.DEFAULT_OUTPUT_PACKAGE, TestConfiguration.DEFAULT_JOOQ_PACKAGE);
        var attributed = TestSchemaHelper.attributedRegistry(ctx);
        var store = GraphitronModelStore.open();
        FactCapture.capture(store.dsl(), graph(directory), FactCapture.SubjectConfig.none(),
            attributed.preSynthesisRegistry(), SchemaInputAttribution.build(List.of(input)), null,
            List.of(), TestSchemaHelper.nodeDeclaration(ctx));
        return new CapturedStore(store, attributed.preSynthesisRegistry(), attributed);
    }

    /** The fixture graph capture writes under, shared so readers can scope by it. */
    static FactCapture.GraphIdentity graph(Path directory) {
        return new FactCapture.GraphIdentity("CapturedStore", directory);
    }

    /** Just the parse, for callers that fill a store from something other than the SDL. */
    static TypeDefinitionRegistry registryOf(Path directory, String sdl) {
        return RewriteSchemaLoader.load(List.of(SchemaSource.file(write(directory, sdl))));
    }

    /**
     * The attribution map over the one input {@link #registryOf} minted, so capture's stamp lookup
     * resolves the fixture's schema file instead of meeting a name no input declared. Derived from
     * {@link #fixtureFile} like the load is, so the two cannot disagree about what was handed over.
     */
    static Map<String, SchemaInput> attributionOf(Path directory) {
        return SchemaInputAttribution.build(List.of(SchemaInput.file(fixtureFile(directory))));
    }

    /** The one file every fixture in this helper writes; the load and the attribution share it. */
    static Path fixtureFile(Path directory) {
        return directory.resolve("fixture.graphqls");
    }

    private static Path write(Path directory, String sdl) {
        Path file = fixtureFile(directory);
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
