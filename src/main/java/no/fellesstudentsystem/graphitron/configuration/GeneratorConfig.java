package no.fellesstudentsystem.graphitron.configuration;

import no.fellesstudentsystem.graphitron.mojo.GenerateMojo;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.GlobalTransform;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.*;

import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Static generator configuration. Mostly dependent on the settings set through the POM XML.
 */
public class GeneratorConfig {
    private static final String
            PLUGIN_OUTPUT_PATH = "graphitron",
            PACKAGE_RECORDS = "tables.records",
            CLASS_TABLES = "Tables",
            CLASS_KEYS = "Keys",
            TEMP_GRAPHQL_GENERATED_PACKAGE = "fake.graphql.example.package", // Once codegen is fully contained in this module, this will be redundant.
            DEFAULT_API_SUFFIX = ".api",
            DEFAULT_MODEL_SUFFIX = ".model";

    private static final URL GENERATOR_DIRECTIVES_PATH = GeneratorConfig.class.getResource("schema/directives.graphqls");

    /**
     * Set the generator properties from code. Intended for tests.
     */
    public static void setProperties(
            Set<String> files,
            String outputDir,
            String outputPkg,
            String jooqPkg,
            List<ExternalClassReference> references,
            List<GlobalTransform> globalTransforms
    ) {
        schemaFiles = files;
        outputDirectory = outputDir;
        outputPackage = outputPkg;

        generatedSchemaResolversPackage = TEMP_GRAPHQL_GENERATED_PACKAGE + DEFAULT_API_SUFFIX;
        generatedSchemaModelsPackage = TEMP_GRAPHQL_GENERATED_PACKAGE + DEFAULT_MODEL_SUFFIX;
        generatedJooqPackage = jooqPkg;
        generatedJooqTablesPackage = generatedJooqPackage + "." + CLASS_TABLES;
        generatedJooqKeysPackage = generatedJooqPackage + "." + CLASS_KEYS;
        generatedJooqRecordsPackage = generatedJooqPackage + "." + PACKAGE_RECORDS;

        setJOOQClasses();

        externalReferences = new ExternalReferences(references);

        GeneratorConfig.globalTransforms = globalTransforms;

        GeneratorConfig.shouldGenerateRecordValidation = false;
    }

    /**
     * Read all the configurations set in the XML from the provided MOJO.
     */
    public static void loadProperties(GenerateMojo mojo) {
        var files = mojo.getSchemaFiles();
        Set<String> inputFiles = Set.of();
        if (files != null) {
            inputFiles = new HashSet<>(files);
            if (GENERATOR_DIRECTIVES_PATH != null) {
                inputFiles.add(GENERATOR_DIRECTIVES_PATH.getPath());
            }
        }

        schemaFiles = inputFiles;
        outputDirectory = mojo.getOutputPath() + "/" + PLUGIN_OUTPUT_PATH;
        outputPackage = mojo.getOutputPackage();

        var graphQLGeneratedPackage = mojo.getGeneratedSchemaCodePackage();
        generatedSchemaResolversPackage = graphQLGeneratedPackage + DEFAULT_API_SUFFIX; // Once codegen is fully contained in this module, this will be redundant.
        generatedSchemaModelsPackage = graphQLGeneratedPackage + DEFAULT_MODEL_SUFFIX;
        generatedJooqPackage = mojo.getJooqGeneratedPackage();
        generatedJooqTablesPackage = generatedJooqPackage + "." + CLASS_TABLES;
        generatedJooqKeysPackage = generatedJooqPackage + "." + CLASS_KEYS;
        generatedJooqRecordsPackage = generatedJooqPackage + "." + PACKAGE_RECORDS;

        setJOOQClasses();
        externalReferences = new ExternalReferences(mojo.getExternalReferences());

        GeneratorConfig.globalTransforms = mojo.getGlobalTransforms();
        GeneratorConfig.shouldGenerateRecordValidation = mojo.shouldGenerateRecordValidation();
    }

    /**
     * Use reflection to find the tables and keys classes in jOOQ.
     */
    private static void setJOOQClasses() {
        try {
            TABLES_CLASS = Class.forName(generatedJooqTablesPackage);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Unable to find jOOQ generated tables class. ", e);
        }
        try {
            KEYS_CLASS = Class.forName(generatedJooqKeysPackage);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Unable to find jOOQ generated keys class. ", e);
        }
    }

    /**
     * Clear all configurations. Intended for tests.
     */
    public static void clear() {
        schemaFiles = null;
        outputDirectory = null;
        outputPackage = null;
        generatedSchemaResolversPackage = null;
        generatedSchemaModelsPackage = null;
        generatedJooqPackage = null;
        generatedJooqTablesPackage = null;
        generatedJooqKeysPackage = null;
        generatedJooqRecordsPackage = null;
        externalReferences = new ExternalReferences(List.of());
        globalTransforms = List.of();
        TABLES_CLASS = null;
        KEYS_CLASS = null;
    }

    private static Set<String> schemaFiles;

    private static String
            outputDirectory,
            outputPackage,
            generatedSchemaResolversPackage,
            generatedSchemaModelsPackage,
            generatedJooqPackage,
            generatedJooqTablesPackage,
            generatedJooqKeysPackage,
            generatedJooqRecordsPackage;

    private static boolean shouldGenerateRecordValidation;

    public static Class<?> TABLES_CLASS, KEYS_CLASS;

    private static ExternalReferences externalReferences;
    private static List<GlobalTransform> globalTransforms;

    public static Set<String> schemaFiles() {
        return schemaFiles;
    }

    public static String outputDirectory() {
        return outputDirectory;
    }

    public static String outputPackage() {
        return outputPackage;
    }

    public static String generatedResolversPackage() {
        return generatedSchemaResolversPackage;
    }

    public static String generatedModelsPackage() {
        return generatedSchemaModelsPackage;
    }

    public static String getGeneratedJooqPackage() {
        return generatedJooqPackage;
    }

    public static String getGeneratedJooqTablesPackage() {
        return generatedJooqTablesPackage;
    }

    public static String getGeneratedJooqKeysPackage() {
        return generatedJooqKeysPackage;
    }

    public static Class<?> getGeneratedJooqTablesClass() {
        return TABLES_CLASS;
    }

    public static Class<?> getGeneratedJooqKeysClass() {
        return KEYS_CLASS;
    }

    public static String getGeneratedJooqRecordsPackage() {
        return generatedJooqRecordsPackage;
    }

    public static ExternalReferences getExternalReferences() {
        return externalReferences;
    }

    public static List<GlobalTransform> getGlobalTransforms(TransformScope scope) {
        return globalTransforms
                .stream()
                .filter(it -> it.getScope().equals(scope))
                .collect(Collectors.toList());
    }

    public static boolean shouldGenerateRecordValidation() {
        return shouldGenerateRecordValidation;
    }

    public static void setShouldGenerateRecordValidation(boolean shouldGenerateRecordValidation) {
        GeneratorConfig.shouldGenerateRecordValidation = shouldGenerateRecordValidation;
    }

    public static void setSchemaFiles(String file) {
        schemaFiles = Set.of(file);
    }

    public static void setSchemaFiles(String... files) {
        schemaFiles = Set.of(files);
    }

    public static void setSchemaFiles(Set<String> files) {
        schemaFiles = files;
    }

    public static void setOutputDirectory(String path) {
        outputDirectory = path + "/" + PLUGIN_OUTPUT_PATH;
    }
}
