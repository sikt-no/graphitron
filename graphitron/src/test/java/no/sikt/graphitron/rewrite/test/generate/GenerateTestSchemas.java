package no.sikt.graphitron.rewrite.test.generate;

import no.sikt.graphitron.model.config.DependencyVersions;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.FactDocuments;
import no.sikt.graphitron.model.test.TestRunContext;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Generates the module's test schemas, for the tests of generated code: every fact scenario, the
 * documents under {@code graphitron-model}'s {@code facts/scenarios}, as a generating run would,
 * without the plugin and so without the language server or the MCP server it brings.
 *
 * <p>A generating run, whole: each scenario is a {@link RunContext} declaring its output
 * coordinates, captured by {@link GraphitronStore#captured(RunContext)} and generated from what that
 * captured. So everything the run declares reaches the store the way it does in production, and an
 * emitter reading the run's configuration from the store finds it there. What the scenario is captured
 * over is what {@code FactExpectationTest} checks: the document as {@link FactDocuments#captured}
 * spells it, the sakila catalog and the classpath corpus.
 *
 * <p>A scenario is a fact document the store records no defect for, which is the folder's claim and
 * {@code FactExpectationTest}'s to check; a scenario this refuses is a defect the store does not
 * record. A scenario stating the configuration its graph is captured under is about how the store
 * reads a configuration, and is not generated.
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
        // The directory is this launcher's alone, so it starts empty: a scenario that left the
        // folder, or now states a configuration, would otherwise go on compiling from a past run.
        if (Files.exists(outputDirectory)) {
            delete(outputDirectory);
        }
        var directory = Files.createTempDirectory("graphitron-test-schemas");
        try {
            for (var scenario : FactDocuments.documents(FactDocuments.Claim.SCENARIO)) {
                if (FactDocuments.statesConfiguration(scenario)) {
                    continue;
                }
                var file = directory.resolve(scenario.id() + ".graphqls");
                Files.writeString(file, FactDocuments.captured(scenario));
                generate(file, scenario.id(), packagePrefix + "." + scenario.id().replace('-', '_'),
                    outputDirectory);
            }
        } finally {
            delete(directory);
        }
    }

    /** One scenario's run: its context, its capture, and the generator over what it captured. */
    private static void generate(Path file, String graph, String outputPackage, Path outputDirectory) {
        var inputs = List.of(SchemaInput.file(file));
        var ctx = new RunContext(
            inputs,
            file.getParent(),
            graph,
            outputDirectory,
            outputDirectory.getParent().resolve("generated-resources-graphitron"),
            outputPackage,
            TestRunContext.JOOQ_PACKAGE,
            ClasspathCorpus.entries(),
            Thread.currentThread().getContextClassLoader(),
            List.of(),
            LintConfig.empty(),
            SessionStateConfig.none(),
            null,
            DependencyVersions.empty(),
            null,
            SchemaRecipe.literalOver(inputs, RunContext.DEFAULT_SCHEMA_FILE_EXTENSIONS),
            null,
            null);
        try (var captured = GraphitronStore.captured(ctx)) {
            new GraphQLRewriteGenerator(ctx, captured).generate();
        }
    }

    private static void delete(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
