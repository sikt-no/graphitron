package no.fellesstudentsystem.graphitron.configuration;

import no.fellesstudentsystem.graphitron.mojo.GenerateMojo;
import no.fellesstudentsystem.graphitron.mojo.GlobalTransform;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.*;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
            String topPackage,
            Set<String> files,
            String outputDir,
            String outputPkg,
            String jooqPkg,
            Map<String, Class<?>> enums,
            Map<String, Method> conditions,
            Map<String, Class<?>> services,
            Map<String, Class<?>> exceptions,
            Map<String, Method> transforms,
            List<GlobalTransform> globalTransforms
    ) {
        systemPackage = topPackage;
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

        externalEnums = new ExternalEnums(enums);
        externalConditions = new ExternalConditions(conditions);
        externalServices = new ExternalServices(services);
        externalExceptions = new ExternalExceptions(exceptions);
        externalTransforms = new ExternalTransforms(transforms);

        GeneratorConfig.globalTransforms = globalTransforms;
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

        systemPackage = mojo.getTopPackage();
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

        externalEnums = new ExternalEnums(mojo.getExternalEnums());
        externalConditions = new ExternalConditions(mojo.getExternalConditions());
        externalServices = new ExternalServices(mojo.getExternalServices());
        externalExceptions = new ExternalExceptions(mojo.getExternalExceptions());
        externalTransforms = new ExternalTransforms(mojo.getExternalTransforms());

        GeneratorConfig.globalTransforms = mojo.getGlobalTransforms();
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
        systemPackage = null;
        schemaFiles = null;
        outputDirectory = null;
        outputPackage = null;
        generatedSchemaResolversPackage = null;
        generatedSchemaModelsPackage = null;
        generatedJooqPackage = null;
        generatedJooqTablesPackage = null;
        generatedJooqKeysPackage = null;
        generatedJooqRecordsPackage = null;
        externalEnums = new ExternalEnums(Map.of());
        externalConditions = new ExternalConditions(Map.of());
        externalServices = new ExternalServices(Map.of());
        externalExceptions = new ExternalExceptions(Map.of());
        externalTransforms = new ExternalTransforms(Map.of());
        globalTransforms = List.of();
        TABLES_CLASS = null;
        KEYS_CLASS = null;
    }

    private static Set<String> schemaFiles;

    private static String
            systemPackage,
            outputDirectory,
            outputPackage,
            generatedSchemaResolversPackage,
            generatedSchemaModelsPackage,
            generatedJooqPackage,
            generatedJooqTablesPackage,
            generatedJooqKeysPackage,
            generatedJooqRecordsPackage;

    public static Class<?> TABLES_CLASS, KEYS_CLASS;

    private static ExternalEnums externalEnums;
    private static ExternalConditions externalConditions;
    private static ExternalServices externalServices;
    private static ExternalExceptions externalExceptions;
    private static ExternalTransforms externalTransforms;
    private static List<GlobalTransform> globalTransforms;

    public static String getSystemPackage() {
        return systemPackage;
    }

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


    public static ExternalEnums getExternalEnums() {
        return externalEnums;
    }

    public static ExternalConditions getExternalConditions() {
        return externalConditions;
    }

    public static ExternalServices getExternalServices() {
        return externalServices;
    }

    public static ExternalExceptions getExternalExceptions() {
        return externalExceptions;
    }

    public static ExternalTransforms getExternalTransforms() {
        return externalTransforms;
    }

    public static List<String> getGlobalTransformNames(TransformScope scope) {
        return globalTransforms
                .stream()
                .filter(it -> it.getScope().equals(scope))
                .map(GlobalTransform::getName)
                .collect(Collectors.toList());
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
