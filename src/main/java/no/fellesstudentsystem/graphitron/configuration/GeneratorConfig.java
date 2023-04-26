package no.fellesstudentsystem.graphitron.configuration;

import java.util.List;

public class GeneratorConfig {
    public static final String
            PROPERTY_SCHEMA_FILES = "schemaFiles",
            PROPERTY_OUTPUT_DIRECTORY = "outputDirectory",
            PROPERTY_OUTPUT_PACKAGE = "outputPackage",
            PROPERTY_GENERATED_RESOLVERS_PACKAGE = "generatedResolversPackage",
            PROPERTY_GENERATED_MODELS_PACKAGE = "generatedModelsPackage",
            TABLES_PACKAGE_PATH = "no.fellesstudentsystem.kjerneapi.tables",
            RECORDS_PACKAGE_PATH = TABLES_PACKAGE_PATH + ".records";
    private static final String
            DEFAULT_SCHEMA_FILES = "fs-graphql-spec/target/generated-sources/expanded-schema.graphql",
            DEFAULT_OUTPUT_DIRECTORY = "graphitron/target/generated-sources",
            DEFAULT_OUTPUT_PACKAGE = "no.fellesstudentsystem.graphql",
            DEFAULT_GENERATED_RESOLVERS_PACKAGE = "no.fellesstudentsystem.graphql.generated.api",
            DEFAULT_GENERATED_MODELS_PACKAGE = "no.fellesstudentsystem.graphql.generated.model",
            QUERIES_PACKAGE_PATH = "queries";

    public static void loadProperties() {
        schemaFiles = List.of(System.getProperty(PROPERTY_SCHEMA_FILES, DEFAULT_SCHEMA_FILES).split(","));
        outputDirectory = System.getProperty(PROPERTY_OUTPUT_DIRECTORY, DEFAULT_OUTPUT_DIRECTORY);
        outputPackage = System.getProperty(PROPERTY_OUTPUT_PACKAGE, DEFAULT_OUTPUT_PACKAGE);
        generatedResolversPackage = System.getProperty(PROPERTY_GENERATED_RESOLVERS_PACKAGE, DEFAULT_GENERATED_RESOLVERS_PACKAGE);
        generatedModelsPackage = System.getProperty(PROPERTY_GENERATED_MODELS_PACKAGE, DEFAULT_GENERATED_MODELS_PACKAGE);
        outputQueriesPackage = outputPackage + "." + QUERIES_PACKAGE_PATH;
    }

    private static List<String> schemaFiles;
    private static String outputDirectory;
    private static String outputPackage;
    private static String outputQueriesPackage;
    private static String generatedResolversPackage;
    private static String generatedModelsPackage;

    public static List<String> schemaFiles() {
        return schemaFiles;
    }

    public static String outputDirectory() {
        return outputDirectory;
    }

    public static String outputPackage() {
        return outputPackage;
    }

    public static String outputQueriesPackage() {
        return outputQueriesPackage;
    }

    public static String generatedResolversPackage() {
        return generatedResolversPackage;
    }

    public static String generatedModelsPackage() {
        return generatedModelsPackage;
    }
}
