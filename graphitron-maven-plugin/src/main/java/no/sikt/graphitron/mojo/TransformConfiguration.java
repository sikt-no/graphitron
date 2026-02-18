package no.sikt.graphitron.mojo;

import no.fellesstudentsystem.schema_transformer.OutputSchema;

import java.util.Set;

/**
 * Configuration for schema transformation.
 */
public class TransformConfiguration {

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

    public TransformConfiguration() {}

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
}
