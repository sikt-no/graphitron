package no.fellesstudentsystem.schema_transformer;

import java.util.Set;

/**
 * Configuration for a single output schema file in schema transformation.
 * <p>
 * Controls feature flag filtering and federation settings for the output.
 * <p>
 * <b>Feature flag behavior (generate goal with transform config):</b>
 * <ul>
 *   <li>No {@code flags} specified: Include all content (no filtering)</li>
 *   <li>{@code flags} specified: Filter to include only elements matching those flags</li>
 * </ul>
 * <p>
 * <b>Note:</b> The standalone {@code transform} goal always applies filtering to {@code outputSchemas}.
 * Use the singular {@code outputSchema} parameter for full content with the transform goal.
 */
public class OutputSchema {
    private String fileName;
    private Set<String> flags;

    /**
     * Per-output override for removing federation definitions.
     * If null, uses the global setting.
     */
    private Boolean removeFederationDefinitions;

    public OutputSchema() {}

    public OutputSchema(String fileName) {
        this.fileName = fileName;
    }

    public OutputSchema(String fileName, Set<String> flags) {
        this.fileName = fileName;
        this.flags = flags;
    }

    public OutputSchema(String fileName, Set<String> flags, Boolean removeFederationDefinitions) {
        this.fileName = fileName;
        this.flags = flags;
        this.removeFederationDefinitions = removeFederationDefinitions;
    }

    public String fileName() {
        return fileName;
    }

    public Set<String> flags() {
        return flags != null ? flags : Set.of();
    }

    /**
     * Returns whether to skip feature filtering and include all content.
     * Returns true if no flags are specified.
     */
    public boolean includeAllFeatures() {
        return flags == null;
    }

    /**
     * Returns whether federation definitions should be removed for this output.
     * @param globalDefault the global setting to use if no per-output override is set
     * @return true if federation definitions should be removed
     */
    public boolean shouldRemoveFederationDefinitions(boolean globalDefault) {
        return removeFederationDefinitions != null ? removeFederationDefinitions : globalDefault;
    }
}
