package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.test.CapturedStore;

/**
 * A fact store filled by a real generator run: the build-level population, for the tests whose
 * subject is the dev loop's own wiring or which projection of the pipeline was invoked.
 *
 * <p><b>Two arms, named for the entry point each runs.</b> {@link #run} runs
 * {@link GraphQLRewriteGenerator#buildOutput()}, which is the arm a test wanting the run's products
 * beside its store takes. {@link #captured} runs {@link GraphQLRewriteGenerator#capture()}, which
 * fills the store and stops; it has no products, and that is its subject rather than a limitation.
 * The two build the identical context and differ only in the entry point, so a test comparing their
 * stores is comparing the projections and nothing else.
 *
 * <p><b>Which harness is this.</b> {@link CapturedStore} beside it drives {@link
 * no.sikt.graphitron.model.capture.FactCapture} directly, which is what a test wants when its
 * subject is a crawler or a relation the capture walk writes. This one runs the whole generator,
 * which is what a test wants when the rows it reads are written by loaders that consume the walk's
 * own streams: those rows cannot be arranged, only produced. Paying for a build to reach a fact
 * capture already writes is the mistake in the other direction, and the reason this level is
 * separate rather than the one everything stands on.
 *
 * <p><b>A file store, named rather than flagged.</b> The dev loop's store lives in a directory the
 * build writes into and a session reopens, so that is the substrate here, from
 * {@link FactStores#fileBacked}. Every other fixture in the tree is in-memory, and the difference is
 * this level's subject rather than an accident of how it was written.
 *
 * <p>This holds the build and the store; what a module then does with the pair, publishing the
 * output onto its own workspace or writing further facts beside it, is that module's local layer.
 */
public final class BuiltStore implements AutoCloseable {

    /** The output package a fixture build emits under; nothing here compiles what it emits. */
    private static final String OUTPUT_PACKAGE = "fake.output";

    private final GraphitronModelStore store;
    private final String graphName;
    private final Path schemaFile;
    private final Path storeHome;
    private final GraphQLRewriteGenerator.BuildOutput output;

    private BuiltStore(GraphitronModelStore store, String graphName, Path schemaFile, Path storeHome,
                       GraphQLRewriteGenerator.BuildOutput output) {
        this.store = store;
        this.graphName = graphName;
        this.schemaFile = schemaFile;
        this.storeHome = storeHome;
        this.output = output;
    }

    /** Builds {@code sdl} under {@code graphName}, against the caller's generated jOOQ model. */
    public static BuiltStore run(Path tmp, String graphName, String sdl, String jooqPackage) {
        return run(tmp, graphName, sdl, LintConfig.empty(), jooqPackage, List.of());
    }

    /**
     * Captures {@code sdl} under {@code graphName} and stops: the {@code graphitron:capture}
     * projection, which runs no checks, no plan and no renderers. The fixture a test about the
     * capture-only command takes, and the one to compare a {@link #run} fixture's store against
     * when the question is whether the two projections populate a store alike.
     *
     * <p>{@link #output()} on this arm throws, there being no products for it to return.
     */
    public static BuiltStore captured(Path tmp, String graphName, String sdl, String jooqPackage) {
        return build(tmp, graphName, sdl, LintConfig.empty(), jooqPackage, List.of(),
            generator -> {
                generator.capture();
                return null;
            });
    }

    /**
     * The full arity. {@code lintConfig} is what a case whose subject is a suppression needs;
     * {@code classpathRoots} is what a case reading the {@code jvm_} census needs, a run with no
     * roots capturing no classes at all because the walk's fallback is a {@code target/classes} a
     * temporary directory does not have.
     */
    public static BuiltStore run(Path tmp, String graphName, String sdl, LintConfig lintConfig,
                                 String jooqPackage, List<Path> classpathRoots) {
        return build(tmp, graphName, sdl, lintConfig, jooqPackage, classpathRoots,
            GraphQLRewriteGenerator::buildOutput);
    }

    /**
     * The one body both arms run: it lays the fixture out, builds the context, and hands the
     * generator to {@code pass}, which is the entry point the arm is named for. One body rather
     * than two, so the two arms cannot come to differ in anything but that call.
     */
    private static BuiltStore build(Path tmp, String graphName, String sdl, LintConfig lintConfig,
                                    String jooqPackage, List<Path> classpathRoots,
                                    Function<GraphQLRewriteGenerator,
                                        GraphQLRewriteGenerator.BuildOutput> pass) {
        try {
            Path schemaFile = tmp.resolve(graphName + ".graphqls");
            Files.createDirectories(tmp);
            Files.writeString(schemaFile, sdl);
            Path storeHome = tmp.resolve("store");
            Path out = tmp.resolve("out");
            var inputs = List.of(
                new SchemaInput(SchemaSource.file(schemaFile), Optional.empty(), Optional.empty()));
            var ctx = new RunContext(
                inputs,
                tmp, graphName, out, out.resolve("resources"), OUTPUT_PACKAGE, jooqPackage,
                no.sikt.graphitron.model.config.ClasspathEntry.projectRoots(classpathRoots),
                Thread.currentThread().getContextClassLoader(), List.of(),
                lintConfig, null, null, null, storeHome,
                SchemaRecipe.literalOver(inputs, RunContext.DEFAULT_SCHEMA_FILE_EXTENSIONS),
                null);
            var output = pass.apply(new GraphQLRewriteGenerator(ctx));
            return new BuiltStore(FactStores.fileBacked(storeHome), graphName, schemaFile, storeHome,
                output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * What the run produced: the artifacts, the report, and the two pre-fuse lists. Present on a
     * {@link #run} fixture; a {@link #captured} one produces none, so asking is a mistake rather
     * than a question with an empty answer.
     */
    public GraphQLRewriteGenerator.BuildOutput output() {
        if (output == null) {
            throw new IllegalStateException(
                "a capture-only fixture has no build output: it runs the projection that produces "
                    + "a store and nothing else. Take run(...) for a fixture with products.");
        }
        return output;
    }

    /** The store the run captured into, for a local layer that writes further facts beside it. */
    public GraphitronModelStore store() {
        return store;
    }

    public DSLContext dsl() {
        return store.dsl();
    }

    /** The graph the run captured under. */
    public String graphName() {
        return graphName;
    }

    /** The schema file the run read, spelled where the run put it. */
    public Path schemaFile() {
        return schemaFile;
    }

    /** The store's home on disk, which a test reopening it across a session boundary needs. */
    public Path storeHome() {
        return storeHome;
    }

    /**
     * A reader of this store, for the reads a dev session makes through one. Unbounded, for the
     * reason every fixture reader is: a harness naming a number would smuggle a wall-clock
     * threshold into a tier that must not fail for being slow.
     */
    public StoreReader reader() {
        return reader(new ReadBudget.Unbounded());
    }

    /** A reader under a stated budget, for the cases whose subject <em>is</em> the budget. */
    public StoreReader reader(ReadBudget budget) {
        return store.reader(budget);
    }

    @Override
    public void close() {
        store.close();
    }
}
