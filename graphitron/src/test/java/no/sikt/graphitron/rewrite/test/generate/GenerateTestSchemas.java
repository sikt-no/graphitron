package no.sikt.graphitron.rewrite.test.generate;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;

import java.nio.file.Path;
import java.util.List;

/**
 * Generates the module's test schemas, for the tests of generated code: for each schema file and
 * output package, a store captured in memory and the generator run over it into one output
 * directory, the three steps the plugin's {@code generate} goal takes, so what it writes is what a
 * consumer gets. Without the plugin, so without the language server or the MCP server it brings.
 *
 * <p>Test code, because it opens the store: the generator is handed one and never decides which, a
 * mojo or a test does ({@code FactTierBoundaryTest}). {@code graphitron}'s {@code pom.xml} compiles
 * this package ahead of the rest of the test sources, in {@code generate-test-sources}, and runs it
 * forked on the test classpath, so the services and the jOOQ catalog the schemas name load as they
 * do when the generated code compiles and runs.
 *
 * <p>Arguments: {@code <outputDirectory> <jooqPackage> (<schemaFile> <outputPackage>)...}. Each
 * schema is its own graph, named after its output package.
 */
public final class GenerateTestSchemas {

    private GenerateTestSchemas() {}

    public static void main(String[] args) {
        if (args.length < 4 || args.length % 2 != 0) {
            throw new IllegalArgumentException(
                "usage: GenerateTestSchemas <outputDirectory> <jooqPackage> (<schemaFile> <outputPackage>)...");
        }
        var outputDirectory = Path.of(args[0]).toAbsolutePath();
        var jooqPackage = args[1];
        for (int i = 2; i < args.length; i += 2) {
            generate(Path.of(args[i]).toAbsolutePath(), args[i + 1], outputDirectory, jooqPackage);
        }
    }

    /** One schema into {@code outputDirectory}, under {@code outputPackage}. */
    public static void generate(Path schemaFile, String outputPackage, Path outputDirectory,
                                String jooqPackage) {
        var ctx = new RunContext(
            List.of(SchemaInput.file(schemaFile)),
            schemaFile.getParent(),
            outputPackage,
            outputDirectory,
            outputPackage,
            jooqPackage);
        try (var captured = GraphitronStore.captured(ctx)) {
            new GraphQLRewriteGenerator(ctx, captured).generate();
        }
    }
}
