package no.sikt.graphitron.generate;

import java.util.Set;

/**
 * Minimal configuration interface for schema validation.
 * Unlike {@link Generator}, this only includes parameters needed for validation.
 */
public interface Validator {
    /**
     * @return Schema files to validate
     */
    Set<String> getSchemaFiles();

    /**
     * @return Package where jOOQ generated code resides (for database metadata)
     */
    String getJooqGeneratedPackage();

    /**
     * @return Whether Node strategy is enabled (affects validation rules)
     */
    boolean makeNodeStrategy();

    /**
     * @return Whether type ID is required on Node types (affects validation rules)
     */
    boolean requireTypeIdOnNode();
}
