package no.sikt.graphitron.rewrite.test.generate;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Generates the module's test schemas, for the tests of generated code: every fact scenario, the
 * documents under {@code graphitron-model}'s {@code facts/scenarios}, each with the fact prelude
 * beside it, a store captured in memory and the generator run over it into one output directory,
 * the three steps the plugin's {@code generate} goal takes, so what it writes is what a consumer
 * gets. Without the plugin, so without the language server or the MCP server it brings.
 *
 * <p>A scenario is a fact document the store records no defect for, which is the folder's claim
 * and {@code FactExpectationTest}'s to check; a scenario this refuses is a defect the store does
 * not record.
 *
 * <p>Test code, because it opens the store: the generator is handed one and never decides which, a
 * mojo or a test does ({@code FactTierBoundaryTest}). {@code graphitron}'s {@code pom.xml} compiles
 * this package ahead of the rest of the test sources, in {@code generate-test-sources}, and runs it
 * forked on the test classpath, so the services and the jOOQ catalog the schemas name load as they
 * do when the generated code compiles and runs.
 *
 * <p>Arguments: {@code <outputDirectory> <jooqPackage> <factsDirectory> <packagePrefix>}. Each
 * scenario is its own graph, generated under {@code <packagePrefix>.<name>}, its file name with
 * each hyphen an underscore.
 */
public final class GenerateTestSchemas {

    private static final String PRELUDE = "_prelude.graphqls";
    private static final String SUFFIX = ".graphqls";

    private GenerateTestSchemas() {}

    public static void main(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                "usage: GenerateTestSchemas <outputDirectory> <jooqPackage> <factsDirectory> <packagePrefix>");
        }
        var outputDirectory = Path.of(args[0]).toAbsolutePath();
        var jooqPackage = args[1];
        var facts = Path.of(args[2]).toAbsolutePath();
        var packagePrefix = args[3];
        var prelude = facts.resolve(PRELUDE);
        var scenarios = scenarios(facts.resolve("scenarios"));
        if (scenarios.isEmpty()) {
            throw new IllegalStateException("no scenario under " + facts + "; a folder that reads as "
                + "empty generates nothing and every test of generated code fails to compile");
        }
        for (Path scenario : scenarios) {
            String name = scenario.getFileName().toString();
            String outputPackage = packagePrefix + "."
                + name.substring(0, name.length() - SUFFIX.length()).replace('-', '_');
            generate(List.of(prelude, scenario), outputPackage, outputDirectory, jooqPackage);
        }
    }

    /**
     * One graph of {@code schemaFiles} into {@code outputDirectory}, under {@code outputPackage}, its
     * base directory the first file's, so every file of the graph is named from under it.
     */
    public static void generate(List<Path> schemaFiles, String outputPackage, Path outputDirectory,
                                String jooqPackage) {
        var ctx = new RunContext(
            schemaFiles.stream().map(SchemaInput::file).toList(),
            schemaFiles.getFirst().getParent(),
            outputPackage,
            outputDirectory,
            outputPackage,
            jooqPackage);
        try (var captured = GraphitronStore.captured(ctx)) {
            new GraphQLRewriteGenerator(ctx, captured).generate();
        }
    }

    private static List<Path> scenarios(Path folder) {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
