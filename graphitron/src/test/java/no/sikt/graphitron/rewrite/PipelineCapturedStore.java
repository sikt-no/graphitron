package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
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
import no.sikt.graphitron.model.schema.AttributedRegistry;

/**
 * A fact store captured beside the attribution pipeline production runs, over the same file and
 * the same configuration. The two-tier arm of the capture harness, and the reason it is here rather
 * than beside the rest of {@link CapturedStore}: everything on that handle is a capture and lives
 * with capture, and this one runs {@link no.sikt.graphitron.model.schema.AttributedRegistry#load} first, so
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
    private final Path file;

    private PipelineCapturedStore(no.sikt.graphitron.model.boot.GraphitronModelStore store,
                                  AttributedRegistry attributed, Path file) {
        this.store = store;
        this.attributed = attributed;
        this.file = file;
    }

    /** Captures {@code sdl} under {@link CapturedStore#GRAPH} behind the pipeline's attribution. */
    public static PipelineCapturedStore of(Path directory, String sdl) {
        return of(directory, sdl, null);
    }

    /**
     * {@link #of(Path, String)} with a tag on the input, so {@code TagLinkSynthesiser} fires on both
     * halves: the generator's load, and the capture's assembly, which composes the corpus with the
     * same rewrites. The tag reaches each half the way a run's configuration would, the generator's
     * through its {@link RunContext} and the capture's through the recipe binding.
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
        // The corpus this pass reads, stated as the configuration a run would have had, and not
        // SubjectConfig.none(). The pass runs the document gatherers, whose rows are keyed by the
        // position a node was written at in one file, so a capture given no corpus writes no entry
        // stratum and therefore derives none of the graphitron_ relations that stand on it. The
        // fixture is already on disk above, and naming it here is what makes the two halves of this
        // pass read the same documents.
        CapturedStore.capture(store.dsl(), CapturedStore.graph(directory),
            corpusOf(directory, file, tag), new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()));
        return new PipelineCapturedStore(store, attributed, file);
    }

    /**
     * The corpus a reading is of, stated as the configuration a run would have had. A literal
     * binding rather than a glob, so the gatherer is handed exactly the file this fixture wrote, and
     * carrying the tag the generator's input carries, so the two halves of the pass read the same
     * configuration as well as the same documents.
     */
    private static SubjectConfig corpusOf(Path directory, Path file, String tag) {
        return SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(new SchemaRecipe.Binding(new SchemaRecipe.Entry.Literal(SchemaSource.file(file)),
                Optional.ofNullable(tag), Optional.empty())),
            List.of("graphqls")));
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
     * The registry the capture's decode walked: the corpus as written, the fixture's file reduced
     * with the bundled directive vocabulary and nothing composed into it. Neither of the pipeline's
     * handles is that registry, both carrying the loading rewrites, so a test reading the decode's
     * rows back compares them against this one.
     */
    public graphql.schema.idl.TypeDefinitionRegistry registry() {
        return SchemaLoader.parsePerSource(List.of(SchemaSource.file(file))).registry();
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
