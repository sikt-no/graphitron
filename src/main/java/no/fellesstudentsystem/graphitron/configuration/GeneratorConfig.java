package no.fellesstudentsystem.graphitron.configuration;

import no.fellesstudentsystem.graphitron.GenerateMojo;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalConditions;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalEnums;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalExceptions;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalServices;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GeneratorConfig {
    public static final String
            CLASS_TABLES = "Tables",
            CLASS_KEYS = "Keys",
            PACKAGE_RECORDS = "tables.records",
            PLUGIN_OUTPUT_PATH = "graphitron"
    ;

    private static final String
            TEMP_GRAPHQL_GENERATED_PACKAGE = "fake.graphql.example.package", // Once codegen is fully contained in this module, this will be redundant.
            DEFAULT_API_SUFFIX = ".api",
            DEFAULT_MODEL_SUFFIX = ".model";
    private static final URL GENERATOR_DIRECTIVES_PATH = GeneratorConfig.class.getResource("schema/directives.graphqls");

    public static void setProperties(
            String topPackage,
            Set<String> files,
            String outputDir,
            String outputPkg,
            String jooqPkg,
            Map<String, Class<?>> enums,
            Map<String, Method> conditions,
            Map<String, Class<?>> services,
            Map<String, Class<?>> exceptions
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

        externalEnums = new ExternalEnums(enums);
        externalConditions = new ExternalConditions(conditions);
        externalServices = new ExternalServices(services);
        externalExceptions = new ExternalExceptions(exceptions);
    }

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

        externalEnums = new ExternalEnums(mojo.getExternalEnums());
        externalConditions = new ExternalConditions(mojo.getExternalConditions());
        externalServices = new ExternalServices(mojo.getExternalServices());
        externalExceptions = new ExternalExceptions(mojo.getExternalExceptions());
    }

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
        externalEnums = null;
        externalConditions = null;
        externalServices = null;
        externalExceptions = null;
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

    private static ExternalEnums externalEnums;
    private static ExternalConditions externalConditions;
    private static ExternalServices externalServices;
    private static ExternalExceptions externalExceptions;

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
