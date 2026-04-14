package no.sikt.graphitron.rewrite;

import java.util.Set;

/**
 * Static configuration holder for the rewrite code-generation pipeline.
 *
 * <p>Set once by the Maven plugin ({@code GenerateMojo}) before generation starts,
 * and read by generators and validators during the same plugin execution.
 * In tests, call {@link #setProperties} in a {@code @BeforeEach} and {@link #clear}
 * in {@code @AfterEach}.
 */
public class RewriteConfig {

    private static Set<String> generatorSchemaFiles;
    private static String outputDirectory;
    private static String outputPackage;
    private static String generatedJooqPackage;

    private RewriteConfig() {}

    public static void setProperties(
            Set<String> schemaFiles,
            String outputDir,
            String outputPkg,
            String jooqPkg
    ) {
        generatorSchemaFiles = schemaFiles;
        outputDirectory = outputDir;
        outputPackage = outputPkg;
        generatedJooqPackage = jooqPkg;
    }

    public static Set<String> generatorSchemaFiles() {
        return generatorSchemaFiles;
    }

    public static String outputDirectory() {
        return outputDirectory;
    }

    public static String outputPackage() {
        return outputPackage;
    }

    public static String getGeneratedJooqPackage() {
        return generatedJooqPackage;
    }

    /** Resets all fields to {@code null}. Intended for test teardown. */
    public static void clear() {
        generatorSchemaFiles = null;
        outputDirectory = null;
        outputPackage = null;
        generatedJooqPackage = null;
    }
}
