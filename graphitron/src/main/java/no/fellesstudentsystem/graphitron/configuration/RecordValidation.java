package no.fellesstudentsystem.graphitron.configuration;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Configure generation of JOOQ record validation for generated mutations according to the Jakarta Bean Validation
 * specification. This class allows for toggling validation and specifying the associated GraphQL schema error type.
 */
public class RecordValidation {

    /**
     * Flag indicating if Graphitron should generate code for validation of JOOQ records.
     */
    private boolean enabled;

    /**
     * Name of the schema error to be returned in case of validation violations and IllegalArgumentExceptions.
     * If null while 'enabled' is true all validation violations and IllegalArgumentExceptions will cause 'AbortExecutionExceptions' to be thrown,
     * leading to top-level GraphQL errors. If the given error is not present in the schema as a returnable error for
     * a specific mutation, validation violations and IllegalArgumentExceptions on this mutation will instead also cause top-level GraphQL errors.
     */
    private String schemaErrorType;

    public RecordValidation() {}

    public RecordValidation(boolean enabled, @Nullable String schemaErrorType) {
        this.enabled = enabled;
        this.schemaErrorType = schemaErrorType;
    }

    /**
     * Checks if the record validation is currently enabled.
     *
     * @return A boolean indicating whether record validation is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Retrieves the schema error type.
     *
     * @return An Optional containing the schema error type if present and validation is enabled, otherwise returns an empty Optional.
     */
    public Optional<String> getSchemaErrorType() {
        return enabled
                ? Optional.ofNullable(schemaErrorType)
                : Optional.empty();
    }
}