package no.fellesstudentsystem.schema_transformer.schema;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.fellesstudentsystem.schema_transformer.transform.FeatureConfiguration;
import no.sikt.graphql.schema.SchemaReadingHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphql.schema.SchemaReadingHelper.readSchemas;

/**
 * Class for reading schema files from disk.
 */
public class SchemaReader {
    private static final String SCHEMA_FILE_SUFFIX = ".graphql", SCHEMA_FILE_SUFFIX2 = ".graphqls";

    public static TypeDefinitionRegistry getTypeDefinitionRegistry(List<String> schemas) {
        // https://github.com/kobylynskyi/graphql-java-codegen/blob/master/src/main/java/com/kobylynskyi/graphql/codegen/parser/GraphQLDocumentParser.java
        return new SchemaParser().buildRegistry(readSchemas(schemas));
    }

    public static List<String> findSchemaFilesRecursivelyInDirectory(Set<String> schemaRootPaths) {
        var allFiles = schemaRootPaths
                .stream()
                .map(Path::of)
                .map(SchemaReader::findSchemaFilesRecursivelyInDirectory)
                .flatMap(List::stream)
                .sorted()
                .toList();
        return Stream.concat(
                allFiles.stream().filter(it -> !it.contains(FeatureConfiguration.FEATURES_DIRECTORY)),
                allFiles.stream().filter(it -> it.contains(FeatureConfiguration.FEATURES_DIRECTORY))
        ).collect(Collectors.toList());
    }

    private static List<String> findSchemaFilesRecursivelyInDirectory(Path schemaRootPath) {
        try (Stream<Path> walk = Files.walk(schemaRootPath)) {
            return walk
                    .map(Path::toFile)
                    .filter(file -> file.getName().endsWith(SCHEMA_FILE_SUFFIX) || file.getName().endsWith(SCHEMA_FILE_SUFFIX2))
                    .map(File::getPath)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, String> createDescriptionSuffixForFeatureMap(Set<String> schemaRootPaths, String descriptionSuffixFilename) {
        return schemaRootPaths.stream()
                .map(it -> createDescriptionSuffixForFeatureMap(Path.of(it), descriptionSuffixFilename))
                .flatMap(map -> map.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Map<String, String> createDescriptionSuffixForFeatureMap(Path schemaRootPath, String descriptionSuffixFilename) {
        if (!schemaRootPath.toFile().exists()) {
            return new HashMap<>();
        }
        try (Stream<Path> walk = Files.walk(schemaRootPath)) {
            return walk
                    .filter(it -> it.toFile().getName().equals(descriptionSuffixFilename))
                    .collect(Collectors.toMap(
                            it -> {
                                String parentPath = it.getParent().getFileName().toString();
                                return parentPath.substring(parentPath.lastIndexOf("/") + 1);
                            }, SchemaReadingHelper::fileAsString)
                    );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
