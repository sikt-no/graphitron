package no.sikt.graphitron.mojo;

import no.fellesstudentsystem.schema_transformer.OutputSchema;
import no.fellesstudentsystem.schema_transformer.TransformConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maven plugin configuration for schema transformation.
 * <p>
 * This class is populated by Maven's XML parameter binding from the {@code <transform>} element
 * in plugin configuration. Use {@link #toTransformConfig} to create a {@link TransformConfig}
 * for use by the schema transformer.
 */
public class TransformPluginConfiguration {

    /**
     * Directories to search for GraphQL schema files. The plugin will process all schema files found
     * in these directories and their subdirectories.
     */
    private Set<String> schemaRootDirectories;

    /**
     * Name of the file containing description suffixes to be added to schema elements based on their feature flags.
     * These files are looked for in each feature directory.
     */
    private String descriptionSuffixFilename = "description-suffix.md";

    /**
     * Whether to remove generator directives from the output schema. Generator directives are
     * implementation details needed for Graphitron code generation but not needed in the runtime schema.
     */
    private boolean removeGeneratorDirectives = true;

    /**
     * Whether to remove all Apollo federation directives and types from the schema.
     */
    private boolean removeFederationDefinitions = false;

    /**
     * Whether to remove all elements that have set directives which indicate that they should be skipped in further processing.
     */
    private boolean removeExcludedElements = false;

    /**
     * Whether to expand GraphQL connection types into full GraphQL Cursor Connections Specification-compliant structures.
     */
    private boolean expandConnections = true;

    /**
     * Whether to add feature flags to the schema based on directory structure.
     */
    private boolean addFeatureFlags = false;

    /**
     * The name of the output schema file for full content (no feature filtering).
     * <p>
     * <b>Note:</b> Only used by the standalone {@code transform} goal.
     * When using {@code generate} with transform configuration, {@code schema.graphql} is produced automatically.
     */
    private String outputSchema;

    /**
     * Configuration for additional output schemas.
     * <p>
     * <b>Note:</b> Behavior differs between goals:
     * <ul>
     *   <li><b>transform goal:</b> All schemas go through feature filtering.
     *       Use {@link #outputSchema} for full content.</li>
     *   <li><b>generate goal (with transform config):</b> {@code schema.graphql} is always produced automatically.
     *       Use this for additional variants. Schemas without {@code flags} include all content;
     *       schemas with {@code flags} are filtered.</li>
     * </ul>
     */
    private Set<OutputSchema> outputSchemas;

    private Set<String> directivesToRemove;

    public TransformPluginConfiguration() {}

    public Set<String> getSchemaRootDirectories() {
        return schemaRootDirectories;
    }

    public void setSchemaRootDirectories(Set<String> schemaRootDirectories) {
        this.schemaRootDirectories = schemaRootDirectories;
    }

    public String getDescriptionSuffixFilename() {
        return descriptionSuffixFilename;
    }

    public void setDescriptionSuffixFilename(String descriptionSuffixFilename) {
        this.descriptionSuffixFilename = descriptionSuffixFilename;
    }

    public boolean isRemoveGeneratorDirectives() {
        return removeGeneratorDirectives;
    }

    public void setRemoveGeneratorDirectives(boolean removeGeneratorDirectives) {
        this.removeGeneratorDirectives = removeGeneratorDirectives;
    }

    public boolean isRemoveFederationDefinitions() {
        return removeFederationDefinitions;
    }

    public void setRemoveFederationDefinitions(boolean removeFederationDefinitions) {
        this.removeFederationDefinitions = removeFederationDefinitions;
    }

    public boolean isExpandConnections() {
        return expandConnections;
    }

    public void setExpandConnections(boolean expandConnections) {
        this.expandConnections = expandConnections;
    }

    public boolean isAddFeatureFlags() {
        return addFeatureFlags;
    }

    public void setAddFeatureFlags(boolean addFeatureFlags) {
        this.addFeatureFlags = addFeatureFlags;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(String outputSchema) {
        this.outputSchema = outputSchema;
    }

    public Set<OutputSchema> getOutputSchemas() {
        return outputSchemas;
    }

    public void setOutputSchemas(Set<OutputSchema> outputSchemas) {
        this.outputSchemas = outputSchemas;
    }

    public Set<String> getDirectivesToRemove() {
        return directivesToRemove != null ? directivesToRemove : Set.of();
    }

    public void setDirectivesToRemove(Set<String> directivesToRemove) {
        this.directivesToRemove = directivesToRemove;
    }

    public boolean isRemoveExcludedElements() {
        return removeExcludedElements;
    }

    public void setRemoveExcludedElements(boolean removeExcludedElements) {
        this.removeExcludedElements = removeExcludedElements;
    }

    /**
     * Creates a {@link TransformConfig} for use by the schema transformer.
     *
     * @param schemaLocations resolved schema file paths
     * @param descriptionSuffixForFeatures resolved description suffix map
     * @return a TransformConfig with the current settings
     */
    public TransformConfig toTransformConfig(List<String> schemaLocations, Map<String, String> descriptionSuffixForFeatures) {
        return new TransformConfig(
                schemaLocations,
                getDirectivesToRemove(),
                descriptionSuffixForFeatures,
                addFeatureFlags,
                removeGeneratorDirectives,
                isRemoveExcludedElements(),
                expandConnections);
    }
}
