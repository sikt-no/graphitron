package no.fellesstudentsystem.graphitron.configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GeneratorConfig {
    public static final String
            PROPERTY_SCHEMA_LOCATIONS = "schemaLocations", //falls back to PROPERTY_SCHEMA_ROOT_DIRECTORY if not specified
            PROPERTY_SCHEMA_ROOT_DIRECTORY = "schemaRootDirectory",
            PROPERTY_OUTPUT_DIRECTORY = "outputDirectory",
            PROPERTY_OUTPUT_PACKAGE = "outputPackage",
            PROPERTY_GENERATED_RESOLVERS_PACKAGE = "generatedResolversPackage",
            PROPERTY_GENERATED_MODELS_PACKAGE = "generatedModelsPackage",
            TABLES_PACKAGE_PATH = "no.fellesstudentsystem.kjerneapi.tables",
            RECORDS_PACKAGE_PATH = TABLES_PACKAGE_PATH + ".records";
    private static final String
            DEFAULT_SCHEMA_ROOT_DIRECTORY = "fs-graphql-spec/src/main/resources",
            DEFAULT_OUTPUT_DIRECTORY = "graphitron/target/generated-sources",
            DEFAULT_OUTPUT_PACKAGE = "no.fellesstudentsystem.graphql",
            DEFAULT_GENERATED_RESOLVERS_PACKAGE = "no.fellesstudentsystem.graphql.generated.api",
            DEFAULT_GENERATED_MODELS_PACKAGE = "no.fellesstudentsystem.graphql.generated.model",
            QUERIES_PACKAGE_PATH = "queries";
    private static final String SCHEMA_FILE_SUFFIX = ".graphqls";

    public static void loadProperties() {
        Optional<String> schemaLocationsPropertyValue = Optional.ofNullable(System.getProperty(PROPERTY_SCHEMA_LOCATIONS));
        Optional<String> schemaRootDirectoryPropertyValue = Optional.ofNullable(System.getProperty(PROPERTY_SCHEMA_ROOT_DIRECTORY));

        if (schemaLocationsPropertyValue.isPresent() && schemaRootDirectoryPropertyValue.isPresent()) {
            throw new IllegalArgumentException("Only one of the following properties should be set at the same time: " +
                    GeneratorConfig.PROPERTY_SCHEMA_LOCATIONS + " " + GeneratorConfig.PROPERTY_SCHEMA_ROOT_DIRECTORY);
        }

        schemaLocations = schemaLocationsPropertyValue
                .map(it -> List.of(it.split(",")))
                .orElseGet(() -> {
                    try {
                        return findSchemaFilesRecursivelyInDirectory(schemaRootDirectoryPropertyValue.orElse(DEFAULT_SCHEMA_ROOT_DIRECTORY));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        outputDirectory = System.getProperty(PROPERTY_OUTPUT_DIRECTORY, DEFAULT_OUTPUT_DIRECTORY);
        outputPackage = System.getProperty(PROPERTY_OUTPUT_PACKAGE, DEFAULT_OUTPUT_PACKAGE);
        generatedResolversPackage = System.getProperty(PROPERTY_GENERATED_RESOLVERS_PACKAGE, DEFAULT_GENERATED_RESOLVERS_PACKAGE);
        generatedModelsPackage = System.getProperty(PROPERTY_GENERATED_MODELS_PACKAGE, DEFAULT_GENERATED_MODELS_PACKAGE);
        outputQueriesPackage = outputPackage + "." + QUERIES_PACKAGE_PATH;
    }

    private static List<String> findSchemaFilesRecursivelyInDirectory(String schemaRootDirectory) throws IOException {
        Path path = Paths.get(schemaRootDirectory);

        try (Stream<Path> walk = Files.walk(path)) {
            return walk
                    .map(Path::toFile)
                    .filter(file -> file.getName().endsWith(SCHEMA_FILE_SUFFIX))
                    .map(File::getPath)
                    .collect(Collectors.toList());
        }
    }

    private static List<String> schemaLocations;
    private static String outputDirectory;
    private static String outputPackage;
    private static String outputQueriesPackage;
    private static String generatedResolversPackage;
    private static String generatedModelsPackage;

    public static List<String> schemaLocations() {
        return schemaLocations;
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
