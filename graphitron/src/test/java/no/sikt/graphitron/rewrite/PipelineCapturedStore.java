package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.capture.FactCapture;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A fact store captured behind the attribution pipeline production runs, taking the handle
 * production captures. The two-tier arm of the capture harness, and the reason it is here rather
 * than beside the rest of {@link CapturedStore}: everything on that handle is a capture and lives
 * with capture, and this one runs {@link GraphQLRewriteGenerator#loadAttributedRegistry()} first, so
 * it can only exist where both tiers are visible.
 *
 * <p>The difference the pipeline makes is the whole point of the arm. A bare parse would let
 * capture's macro expansion mint what the rewrite has already put there in the pipeline, and the
 * store would agree with the model for the wrong reason. {@link #attributed()} exposes both handles
 * so a test can compare the two stages.
 *
 * <p>The catalog reaches capture, so a rule reading both corpora answers here the way it answers in
 * production. This arm used to capture no catalog while handing the walk a catalog-bearing nodehood
 * predicate, which was the shape that let a fixture disagree with production about nodehood without
 * any assertion noticing.
 */
public final class PipelineCapturedStore implements AutoCloseable {

    private final no.sikt.graphitron.model.boot.GraphitronModelStore store;
    private final AttributedRegistry attributed;

    private PipelineCapturedStore(no.sikt.graphitron.model.boot.GraphitronModelStore store,
                                  AttributedRegistry attributed) {
        this.store = store;
        this.attributed = attributed;
    }

    /** Captures {@code sdl} under {@link CapturedStore#GRAPH} behind the pipeline's attribution. */
    public static PipelineCapturedStore of(Path directory, String sdl) {
        return of(directory, sdl, null);
    }

    /**
     * {@link #of(Path, String)} with a tag on the input, so {@code TagLinkSynthesiser} fires and its
     * synthesised source name enters the registry capture walks. The only fixture in the tree that
     * puts that sentinel in front of capture's stamp lookup.
     */
    public static PipelineCapturedStore of(Path directory, String sdl, String tag) {
        Path file = write(directory, sdl);
        var input = new SchemaInput(SchemaSource.file(file), Optional.ofNullable(tag), Optional.empty());
        var ctx = new RunContext(
            List.of(input),
            directory, CapturedStore.GRAPH, directory,
            TestConfiguration.DEFAULT_OUTPUT_PACKAGE, TestConfiguration.DEFAULT_JOOQ_PACKAGE);
        var attributed = TestSchemaHelper.attributedRegistry(ctx);
        var store = FactStores.inMemory();
        FactCapture.capture(store.dsl(), CapturedStore.graph(directory), SubjectConfig.none(),
            attributed.preSynthesisRegistry(), SchemaInputAttribution.build(List.of(input)),
            new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), List.of());
        return new PipelineCapturedStore(store, attributed);
    }

    /**
     * Writes the fixture where {@link CapturedStore#fixtureFile} says a fixture for this graph goes,
     * so the file the pipeline parses is the one capture's stamp lookup is keyed on.
     */
    private static Path write(Path directory, String sdl) {
        Path file = CapturedStore.fixtureFile(directory);
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    public DSLContext dsl() {
        return store.dsl();
    }

    /**
     * The registry capture actually walked, which is the pre-synthesis handle: capture runs before
     * the synthesis rewrites, so a test reading rows back compares them against this one.
     */
    public graphql.schema.idl.TypeDefinitionRegistry registry() {
        return attributed.preSynthesisRegistry();
    }

    /** The pipeline's own two handles, before and after the synthesis rewrites. */
    public AttributedRegistry attributed() {
        return attributed;
    }

    @Override
    public void close() {
        store.close();
    }
}
