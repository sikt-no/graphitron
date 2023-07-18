package no.fellesstudentsystem.graphitron.configuration;

import no.fellesstudentsystem.graphitron.GenerateMojo;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalConditions;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalEnums;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalExceptions;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalServices;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GeneratorConfig {
    public static final String
            SYSTEM_PACKAGE = "no.fellesstudentsystem", // Read this from pom.xml?
            CLASS_TABLES = "Tables",
            CLASS_KEYS = "Keys",
            PACKAGE_RECORDS = "tables.records",
            PLUGIN_OUTPUT_PATH = "graphitron"
    ;

    private static final String
            DEFAULT_OUTPUT_PACKAGE = SYSTEM_PACKAGE + ".graphql",
            TEMP_GRAPHQL_GENERATED_PACKAGE = "fake.graphql.example.package", // Once codegen is fully contained in this module, this will be redundant.
            TEMP_JOOQ_GENERATED_PACKAGE = SYSTEM_PACKAGE + ".kjerneapi", // Remove once Graphitron is no longer dependent on DB.
            DEFAULT_API_SUFFIX = ".api",
            DEFAULT_MODEL_SUFFIX = ".model";
    private static final URL GENERATOR_DIRECTIVES_PATH = GeneratorConfig.class.getResource("schema/directives.graphqls");

    public static void setProperties(
            List<String> files,
            String outputDir,
            String outputPkg,
            Map<String, Class<?>> enums,
            Map<String, Method> conditions,
            Map<String, Class<?>> services,
            Map<String, Class<?>> exceptions
    ) {
        schemaFiles = files;
        outputDirectory = outputDir;
        outputPackage = outputPkg;

        generatedSchemaResolversPackage = TEMP_GRAPHQL_GENERATED_PACKAGE + DEFAULT_API_SUFFIX;
        generatedSchemaModelsPackage = TEMP_GRAPHQL_GENERATED_PACKAGE + DEFAULT_MODEL_SUFFIX;
        generatedJooqPackage = TEMP_JOOQ_GENERATED_PACKAGE;
        generatedJooqTablesPackage = generatedJooqPackage + "." + CLASS_TABLES;
        generatedJooqKeysPackage = generatedJooqPackage + "." + CLASS_KEYS;
        generatedJooqRecordsPackage = generatedJooqPackage + "." + PACKAGE_RECORDS;

        externalEnums = new ExternalEnums(enums);
        externalConditions = new ExternalConditions(conditions);
        externalServices = new ExternalServices(services);
        externalExceptions = new ExternalExceptions(exceptions);
    }

    public static void setSchemaFiles(String file) {
        schemaFiles = List.of(file);
    }

    public static void setSchemaFiles(String... files) {
        schemaFiles = List.of(files);
    }

    public static void loadProperties(GenerateMojo mojo) {
        var inputFiles = new ArrayList<>(mojo.getSchemaFiles());
        if (GENERATOR_DIRECTIVES_PATH != null) {
            inputFiles.add(GENERATOR_DIRECTIVES_PATH.getPath());
        }

        schemaFiles = inputFiles;
        outputDirectory = mojo.getOutputPath() + "/" + PLUGIN_OUTPUT_PATH;
        outputPackage = DEFAULT_OUTPUT_PACKAGE;

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

    private static List<String> schemaFiles;
    private static String
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

    public static List<String> schemaFiles() {
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
}
