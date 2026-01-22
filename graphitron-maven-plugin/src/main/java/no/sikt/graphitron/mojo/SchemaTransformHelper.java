package no.sikt.graphitron.mojo;

import no.fellesstudentsystem.schema_transformer.schema.SchemaReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper class for schema transformation operations shared between TransformMojo and GenerateAllMojo.
 */
public class SchemaTransformHelper {

    /**
     * Classpath resource path for built-in directives.
     */
    private static final String DIRECTIVES_RESOURCE = "/directives.graphqls";

    /**
     * Collects schema files from the specified directories and adds the built-in directives.
     *
     * @param schemaRootDirectories the directories to search for schema files
     * @return a list of schema file paths including the built-in directives
     */
    public static List<String> collectSchemaFiles(Set<String> schemaRootDirectories) {
        var schemaFiles = new ArrayList<>(SchemaReader.findSchemaFilesRecursivelyInDirectory(schemaRootDirectories));
        schemaFiles.add(DIRECTIVES_RESOURCE);
        return schemaFiles;
    }

    /**
     * Creates the description suffix map for feature flags.
     *
     * @param schemaRootDirectories the directories to search for description suffix files
     * @param descriptionSuffixFilename the filename to look for
     * @return a map of feature names to their description suffixes
     */
    public static Map<String, String> createDescriptionSuffixMap(Set<String> schemaRootDirectories, String descriptionSuffixFilename) {
        return SchemaReader.createDescriptionSuffixForFeatureMap(schemaRootDirectories, descriptionSuffixFilename);
    }
}
