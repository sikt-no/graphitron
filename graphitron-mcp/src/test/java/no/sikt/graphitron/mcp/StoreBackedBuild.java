package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.BuiltStore;
import no.sikt.graphitron.rewrite.FactWriters;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.lint.LintConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * The store-backed diagnostics fixture: an SDL schema run through the real
 * {@link GraphQLRewriteGenerator#buildOutput()} into a bootstrapped file store, exactly as the
 * dev loop wires it. The pipeline run captures facts (the pilot arm's substrate) into the store
 * directory; this fixture then plays {@code DevMojo}'s part with no mojo in play: it invokes the
 * residue and warning loaders over the build's two pre-fuse lists and hands the handle to the server
 * under test. That is the whole of what the mojo hands the server, so the fixture holds no build state
 * beside it. Tests over hand-built reports cannot survive the substrate: the loaders read the walk's
 * own streams, so the rows a test asserts on have to come from a real pipeline run.
 *
 * <p>A local layer over {@link BuiltStore}, which is the reactor's build level and owns the floor this
 * fixture used to own for itself: the schema file it writes, the store's lifetime, and the file
 * substrate that lifetime needs, a store the build writes into and a session reopens being the whole
 * point here. What is left local is the pair of writer calls the mojo makes afterwards, which is what
 * {@link BuiltStore} means by a module doing something with the build and the store together.
 */
final class StoreBackedBuild implements AutoCloseable {

    /**
     * The single-schema generated package every case captures from unless it says otherwise:
     * {@code graphitron-sakila-db} generates it with {@code inputSchema=public} and nothing else, so
     * every name in its census is unique.
     */
    static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    /**
     * The two-schema generated package, for the cases whose subject is a bare spelling reaching more
     * than one table. It declares {@code event} in both {@code multischema_a} and
     * {@code multischema_b} precisely so an unqualified name is ambiguous. A capture from
     * {@link #JOOQ_PACKAGE} cannot show ambiguity at all, every name there being unique, and a real
     * capture can only show what the source declares; the projections these cases replace could
     * assert one by fiat.
     */
    static final String MULTISCHEMA_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";

    private final BuiltStore built;
    private StoreReader reader;

    private StoreBackedBuild(BuiltStore built) {
        this.built = built;
    }

    static StoreBackedBuild run(Path tmp, String graphName, String sdl) {
        return run(tmp, graphName, sdl, LintConfig.empty());
    }

    static StoreBackedBuild run(Path tmp, String graphName, String sdl, LintConfig lintConfig) {
        return run(tmp, graphName, sdl, lintConfig, JOOQ_PACKAGE);
    }

    /**
     * The generated-package overload, for a case whose subject is a property of the census rather
     * than of the schema. The package is what {@link RewriteContext} resolves the catalog from, so it
     * decides which tables the capture writes and therefore what a bare table name can reach.
     */
    static StoreBackedBuild run(Path tmp, String graphName, String sdl, String jooqPackage) {
        return run(tmp, graphName, sdl, LintConfig.empty(), jooqPackage);
    }

    /**
     * The classpath-roots overload, for a case whose subject includes the {@code jvm_} class census. A
     * run with no roots captures no classes at all, the walk's fallback being a
     * {@code <basedir>/target/classes} that a temporary directory does not have, so a case reading the
     * census has to name the entry it means.
     */
    static StoreBackedBuild run(Path tmp, String graphName, String sdl, List<Path> classpathRoots) {
        return run(tmp, graphName, sdl, LintConfig.empty(), JOOQ_PACKAGE, classpathRoots);
    }

    static StoreBackedBuild run(
        Path tmp, String graphName, String sdl, LintConfig lintConfig, String jooqPackage
    ) {
        return run(tmp, graphName, sdl, lintConfig, jooqPackage, List.of());
    }

    static StoreBackedBuild run(
        Path tmp, String graphName, String sdl, LintConfig lintConfig, String jooqPackage,
        List<Path> classpathRoots
    ) {
        var built = BuiltStore.run(tmp, graphName, sdl, lintConfig, jooqPackage, classpathRoots);
        var output = built.output();
        FactWriters.rejectionFacts(built.dsl(), graphName, tmp).write(output.walkErrors());
        FactWriters.buildWarningFacts(built.dsl(), graphName, tmp).write(output.warnings());
        return new StoreBackedBuild(built);
    }

    StoreHandle handle() {
        return new StoreHandle(built.dsl(), built.graphName());
    }

    /**
     * The reader the tools whose answer is several queries take, minted on first use and closed with
     * this fixture, which is {@code DevMojo}'s arrangement with no mojo in play.
     *
     * <p>One per fixture rather than one per call, deliberately: reads through a reader serialize, so a
     * case that minted a second would be testing a connection the dev session does not have.
     */
    StoreReader reader() {
        if (reader == null) {
            reader = built.reader();
        }
        return reader;
    }

    @Override
    public void close() {
        if (reader != null) {
            reader.close();
        }
        built.close();
    }
}
