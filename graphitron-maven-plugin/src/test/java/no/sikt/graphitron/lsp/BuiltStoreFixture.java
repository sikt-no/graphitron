package no.sikt.graphitron.lsp;

import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactWriters;
import no.sikt.graphitron.rewrite.BuiltStore;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;

import java.nio.file.Path;
import java.util.List;

/**
 * A real generator pass over an SDL fixture into a store on disk, with the round's own findings
 * loaded the way a dev round loads them: the walk's errors and the suppression-filtered warnings,
 * through the loaders the dev goal runs and in the order it runs them.
 *
 * <p>The build-driving half of what used to be one fixture in {@code graphitron-lsp}'s own tests.
 * It lives here because a build is the generator's, and the editor-side tests it serves are about
 * the two agreeing, so they belong above both rather than inside the client reaching up. The
 * capture-driving half stayed behind as {@code StoreFixture}, serving the tests that never wanted a
 * build.
 *
 * <p>Declared in the client's package so a relocated test keeps the access it had. What it hands
 * back is what {@code DevMojo} hands a language server: a scoped handle on a store a build filled.
 */
final class BuiltStoreFixture implements AutoCloseable {

    /** The generated jOOQ model the build captures, so a {@code @table} binding resolves. */
    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    private final BuiltStore built;

    private BuiltStoreFixture(BuiltStore built) {
        this.built = built;
    }

    /**
     * Runs {@link GraphQLRewriteGenerator#buildOutput()} over {@code sdl} under {@code lintConfig}
     * and loads what it found. The suppression is applied before the loader's input rather than by a
     * log-side filter, which is the property the tests over this fixture are about.
     */
    static BuiltStoreFixture run(Path directory, String sdl, LintConfig lintConfig) {
        var built = BuiltStore.run(directory, CapturedStore.GRAPH, sdl, lintConfig,
            JOOQ_PACKAGE, List.of());
        var output = built.output();
        FactWriters.rejectionFacts(built.dsl(), CapturedStore.GRAPH, directory)
            .write(output.walkErrors());
        FactWriters.buildWarningFacts(built.dsl(), CapturedStore.GRAPH, directory)
            .write(output.warnings());
        return new BuiltStoreFixture(built);
    }

    /** The scoped query surface a provider takes. */
    StoreHandle handle() {
        return new StoreHandle(built.dsl(), built.graphName());
    }

    /** The schema file this build captured, spelled as the store's {@code source_name} spells it. */
    String sourceName() {
        return SchemaSource.file(built.schemaFile()).sourceName();
    }

    @Override
    public void close() {
        built.close();
    }
}
