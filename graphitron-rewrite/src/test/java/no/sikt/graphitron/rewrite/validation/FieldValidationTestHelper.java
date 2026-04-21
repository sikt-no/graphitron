package no.sikt.graphitron.rewrite.validation;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;

import java.util.List;
import java.util.Map;

/**
 * Static factory helpers shared by all Level 1 validation test classes.
 *
 * <p>Usage — static import in each test class:
 * <pre>
 * import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.*;
 * </pre>
 */
public final class FieldValidationTestHelper {

    private FieldValidationTestHelper() {}

    private static GraphitronSchemaValidator validator() {
        return new GraphitronSchemaValidator();
    }

    // --- Schema assembly helpers ---

    /**
     * General schema assembly: one parent type, one field at the given coordinate.
     */
    public static GraphitronSchema schema(GraphitronType parentType, String fieldName, GraphitronField field) {
        return new GraphitronSchema(
            Map.of(parentType.name(), parentType),
            Map.of(FieldCoordinates.coordinates(parentType.name(), fieldName), field)
        );
    }

    // --- Validation runners ---

    public static List<ValidationError> validate(GraphitronSchema schema) {
        return validator().validate(schema);
    }

    public static List<ValidationError> validate(GraphitronType type) {
        return validator().validate(new GraphitronSchema(Map.of(type.name(), type), Map.of()));
    }

    /**
     * Validates a single field. All field types carry all the data needed for their own validation;
     * the schema is built minimally from the field's own {@code parentTypeName} and {@code name}.
     */
    public static List<ValidationError> validate(GraphitronField field) {
        return validate(schema(new RootType(field.parentTypeName(), null), field.name(), field));
    }

    /**
     * Returns the expected "not yet implemented" error message that
     * {@code GraphitronSchemaValidator.validateVariantIsImplemented} produces for a stubbed
     * variant. Reads from {@link TypeFetcherGenerator#NOT_IMPLEMENTED_REASONS} so the test
     * stays in lock-step with production — updating the reason string in one place updates
     * both sides.
     *
     * <p>Use in a per-variant test's expected-error list where that variant is stubbed:
     * <pre>
     * List.of(stubbedError("Mutation.createFilm", MutationInsertTableField.class))
     * </pre>
     */
    public static String stubbedError(String qualifiedName, Class<? extends GraphitronField> variant) {
        return "Field '" + qualifiedName + "': "
            + TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.get(variant);
    }
}
