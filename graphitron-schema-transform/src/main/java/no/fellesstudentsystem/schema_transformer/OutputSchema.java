package no.fellesstudentsystem.schema_transformer;

import java.util.Set;

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
        this.flags = null; // null means include all features
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
     * Returns whether federation definitions should be removed for this output.
     * @param globalDefault the global setting to use if no per-output override is set
     * @return true if federation definitions should be removed
     */
    public boolean shouldRemoveFederationDefinitions(boolean globalDefault) {
        return removeFederationDefinitions != null ? removeFederationDefinitions : globalDefault;
    }

    /**
     * Returns the per-output override for removing federation definitions, or null if not set.
     */
    public Boolean getRemoveFederationDefinitions() {
        return removeFederationDefinitions;
    }

    /**
     * Returns whether to skip feature filtering and include all content.
     * This is inferred from flags: if flags is not specified (null), all features are included.
     * If flags is specified (even if empty), feature filtering is applied.
     */
    public boolean includeAllFeatures() {
        return flags == null;
    }
}
