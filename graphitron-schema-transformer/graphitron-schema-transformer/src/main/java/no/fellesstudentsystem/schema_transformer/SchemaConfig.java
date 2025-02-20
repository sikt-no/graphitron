package no.fellesstudentsystem.schema_transformer;

import no.fellesstudentsystem.schema_transformer.transform.FeatureFlagConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SchemaConfig {
    private static final String SCHEMA_FILE_SUFFIX = ".graphql", SCHEMA_FILE_SUFFIX2 = ".graphqls";
    public static List<String> findSchemaFilesRecursivelyInDirectory(Set<String> schemaRootPaths) {
        var allFiles = schemaRootPaths
                .stream()
                .map(Path::of)
                .map(SchemaConfig::findSchemaFilesRecursivelyInDirectory)
                .flatMap(List::stream)
                .sorted()
                .collect(Collectors.toList());
        return Stream.concat(
                allFiles.stream().filter(it -> !it.contains(FeatureFlagConfiguration.FEATURES_DIRECTORY)),
                allFiles.stream().filter(it -> it.contains(FeatureFlagConfiguration.FEATURES_DIRECTORY))
        ).collect(Collectors.toList());
    }

    public static List<String> findSchemaFilesRecursivelyInDirectory(Path schemaRootPath) {
        try (Stream<Path> walk = Files.walk(schemaRootPath)) {
            return walk
                    .map(Path::toFile)
                    .filter(file -> file.getName().endsWith(SCHEMA_FILE_SUFFIX) || file.getName().endsWith(SCHEMA_FILE_SUFFIX2))
                    .map(File::getPath)
                    .collect(Collectors.toList());
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

    public static Map<String, String> createDescriptionSuffixForFeatureMap(Path schemaRootPath, String descriptionSuffixFilename) {
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
                            }, SchemaConfig::fileAsString)
                    );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String fileAsString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
