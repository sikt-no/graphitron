package no.sikt.graphitron.rewrite.test.generate;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.FactDocuments;
import no.sikt.graphitron.model.test.TestRunContext;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the module's test schemas, for the tests of generated code: every fact scenario, the
 * documents under {@code graphitron-model}'s {@code facts/scenarios}, captured into one store the
 * way {@code FactExpectationTest} captures them ({@link FactDocuments#capture}: the prelude with
 * each, the sakila catalog, the classpath corpus), and the generator run over each graph of it into
 * one output directory. So the code under test is generated from the facts the documents check, and
 * without the plugin, so without the language server or the MCP server it brings.
 *
 * <p>A scenario is a fact document the store records no defect for, which is the folder's claim and
 * {@code FactExpectationTest}'s to check; a scenario this refuses is a defect the store does not
 * record. A scenario stating the configuration its graph is captured under is about how the store
 * reads a configuration and is captured but not generated.
 *
 * <p>Test code, because it opens the store: the generator is handed one and never decides which, a
 * mojo or a test does ({@code FactTierBoundaryTest}). {@code graphitron}'s {@code pom.xml} compiles
 * this package ahead of the rest of the test sources, in {@code generate-test-sources}, and runs it
 * forked on the test classpath, so the services and the jOOQ catalog the schemas name load as they
 * do when the generated code compiles and runs.
 *
 * <p>Arguments: {@code <outputDirectory> <packagePrefix>}. Each scenario is its own graph, generated
 * under {@code <packagePrefix>.<name>}, its file name with each hyphen an underscore.
 */
public final class GenerateTestSchemas {

    private GenerateTestSchemas() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: GenerateTestSchemas <outputDirectory> <packagePrefix>");
        }
        var outputDirectory = Path.of(args[0]).toAbsolutePath();
        var packagePrefix = args[1];
        var scenarios = FactDocuments.documents(FactDocuments.Claim.SCENARIO);
        var jooq = CatalogInput.of(TestRunContext.of());
        var corpus = ClasspathCorpus.entries();
        var directory = Files.createTempDirectory("graphitron-test-schemas");
        CapturedStore store = null;
        for (var scenario : scenarios) {
            store = FactDocuments.capture(store, directory, scenario, jooq, corpus);
        }
        try (var captured = store) {
            for (var scenario : scenarios) {
                if (!FactDocuments.statesConfiguration(scenario)) {
                    generate(captured, scenario.id(),
                        packagePrefix + "." + scenario.id().replace('-', '_'), outputDirectory,
                        jooq.jooqPackage());
                }
            }
        } finally {
            delete(directory);
        }
    }

    /** One graph of {@code store} into {@code outputDirectory}, under {@code outputPackage}. */
    private static void generate(CapturedStore store, String graph, String outputPackage,
                                 Path outputDirectory, String jooqPackage) {
        var ctx = new RunContext(
            List.of(SchemaInput.file(CapturedStore.fixtureFile(store.directory(), graph))),
            store.directory(),
            graph,
            outputDirectory,
            outputPackage,
            jooqPackage);
        new GraphQLRewriteGenerator(ctx, new StoreHandle(store.dsl(), graph), store.schema(graph))
            .generate();
    }

    private static void delete(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
