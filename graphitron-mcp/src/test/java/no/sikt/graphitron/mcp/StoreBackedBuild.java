package no.sikt.graphitron.mcp;

import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.diagnostics.BuildWarningFacts;
import no.sikt.graphitron.rewrite.diagnostics.RejectionFacts;
import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaRecipe;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The store-backed diagnostics fixture: an SDL schema run through the real
 * {@link GraphQLRewriteGenerator#buildOutput()} into a bootstrapped file store, exactly as the
 * dev loop wires it. The pipeline run captures facts (the pilot arm's substrate) into the store
 * directory; this fixture then plays {@code DevMojo}'s part with no mojo in play: it opens its
 * own session handle onto the same store, invokes the residue and warning loaders over the
 * build's two pre-fuse lists, publishes the build onto a {@link Workspace}, and hands the
 * handle to the server under test. Tests over hand-built reports cannot survive the substrate:
 * the loaders read the walk's own streams, so the rows a test asserts on have to come from a
 * real pipeline run.
 */
final class StoreBackedBuild implements AutoCloseable {

    static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    final Workspace workspace;
    final GraphitronModelStore store;
    final String graphName;
    final GraphQLRewriteGenerator.BuildOutput output;

    private StoreBackedBuild(Workspace workspace, GraphitronModelStore store, String graphName,
                             GraphQLRewriteGenerator.BuildOutput output) {
        this.workspace = workspace;
        this.store = store;
        this.graphName = graphName;
        this.output = output;
    }

    static StoreBackedBuild run(Path tmp, String graphName, String sdl) {
        return run(tmp, graphName, sdl, LintConfig.empty());
    }

    static StoreBackedBuild run(Path tmp, String graphName, String sdl, LintConfig lintConfig) {
        try {
            Path schema = tmp.resolve("schema.graphqls");
            Files.writeString(schema, sdl);
            Path storeHome = tmp.resolve("store");
            Path out = tmp.resolve("out");
            var inputs = List.of(
                new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty()));
            var ctx = new RewriteContext(
                inputs,
                tmp, graphName, out, out.resolve("resources"), "fake.output", JOOQ_PACKAGE,
                List.of(), Thread.currentThread().getContextClassLoader(), List.of(),
                lintConfig, null, null, null, storeHome,
                SchemaRecipe.literalOver(inputs, RewriteContext.DEFAULT_SCHEMA_FILE_EXTENSIONS),
                null);
            var output = new GraphQLRewriteGenerator(ctx).buildOutput();
            var workspace = new Workspace();
            workspace.setBuildOutput(output.artifacts(), output.report());

            var store = GraphitronModelStore.openAt(storeHome);
            var identity = new FactCapture.GraphIdentity(graphName, tmp);
            new RejectionFacts(store.dsl(), identity).write(output.walkErrors());
            new BuildWarningFacts(store.dsl(), identity).write(output.warnings());
            return new StoreBackedBuild(workspace, store, graphName, output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    StoreHandle handle() {
        return new StoreHandle(store.dsl(), graphName);
    }

    @Override
    public void close() {
        store.close();
    }
}
