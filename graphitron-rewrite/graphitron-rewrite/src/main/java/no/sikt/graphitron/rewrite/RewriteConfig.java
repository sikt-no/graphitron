package no.sikt.graphitron.rewrite;

import java.util.Map;
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
    private static Map<String, String> namedReferences;

    private RewriteConfig() {}

    public static void setProperties(
            Set<String> schemaFiles,
            String outputDir,
            String outputPkg,
            String jooqPkg,
            Map<String, String> namedRefs
    ) {
        generatorSchemaFiles = schemaFiles;
        outputDirectory = outputDir;
        outputPackage = outputPkg;
        generatedJooqPackage = jooqPkg;
        namedReferences = namedRefs != null ? Map.copyOf(namedRefs) : Map.of();
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

    public static Map<String, String> namedReferences() {
        return namedReferences != null ? namedReferences : Map.of();
    }

    /** Resets all fields to {@code null}. Intended for test teardown. */
    public static void clear() {
        generatorSchemaFiles = null;
        outputDirectory = null;
        outputPackage = null;
        generatedJooqPackage = null;
        namedReferences = null;
    }
}
