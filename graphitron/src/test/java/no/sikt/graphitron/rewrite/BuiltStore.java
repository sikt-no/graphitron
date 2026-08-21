package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaRecipe;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A fact store filled by a real {@link GraphQLRewriteGenerator#buildOutput()} run: the build-level
 * population, for the tests whose subject is the dev loop's own wiring.
 *
 * <p><b>Which harness is this.</b> {@link CapturedStore} beside it drives {@link
 * no.sikt.graphitron.rewrite.capture.FactCapture} directly, which is what a test wants when its
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
     * The full arity. {@code lintConfig} is what a case whose subject is a suppression needs;
     * {@code classpathRoots} is what a case reading the {@code jvm_} census needs, a run with no
     * roots capturing no classes at all because the walk's fallback is a {@code target/classes} a
     * temporary directory does not have.
     */
    public static BuiltStore run(Path tmp, String graphName, String sdl, LintConfig lintConfig,
                                 String jooqPackage, List<Path> classpathRoots) {
        try {
            Path schemaFile = tmp.resolve(graphName + ".graphqls");
            Files.createDirectories(tmp);
            Files.writeString(schemaFile, sdl);
            Path storeHome = tmp.resolve("store");
            Path out = tmp.resolve("out");
            var inputs = List.of(
                new SchemaInput(SchemaSource.file(schemaFile), Optional.empty(), Optional.empty()));
            var ctx = new RewriteContext(
                inputs,
                tmp, graphName, out, out.resolve("resources"), OUTPUT_PACKAGE, jooqPackage,
                classpathRoots, Thread.currentThread().getContextClassLoader(), List.of(),
                lintConfig, null, null, null, storeHome,
                SchemaRecipe.literalOver(inputs, RewriteContext.DEFAULT_SCHEMA_FILE_EXTENSIONS),
                null);
            var output = new GraphQLRewriteGenerator(ctx).buildOutput();
            return new BuiltStore(FactStores.fileBacked(storeHome), graphName, schemaFile, storeHome,
                output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the run produced: the artifacts, the report, and the two pre-fuse lists. */
    public GraphQLRewriteGenerator.BuildOutput output() {
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
