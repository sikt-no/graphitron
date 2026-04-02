package no.sikt.graphitron.record.validation;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.record.GraphitronSchema;
import no.sikt.graphitron.record.GraphitronSchemaValidator;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.NodeDirective;
import no.sikt.graphitron.record.type.NoNode;
import no.sikt.graphitron.record.type.ResolvedTable;
import no.sikt.graphitron.record.type.RootType;
import no.sikt.graphitron.record.type.TableType;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;

/**
 * Static factory helpers shared by all Level 1 validation test classes.
 *
 * <p>Usage — static import in each test class:
 * <pre>
 * import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.*;
 * </pre>
 */
public final class FieldValidationTestHelper {

    private static final GraphitronSchemaValidator VALIDATOR = new GraphitronSchemaValidator();

    private FieldValidationTestHelper() {}

    // --- Schema assembly helpers ---

    /**
     * Wraps a single field under "Query" (RootType). Use for all QueryField and MutationField cases.
     */
    public static GraphitronSchema inQuerySchema(String fieldName, GraphitronField field) {
        return schema(new RootType("Query", null), fieldName, field);
    }

    /**
     * Wraps a single field under a TableType parent with no {@code @node} directive.
     * Use for ChildField cases where the parent context is table-mapped.
     */
    public static GraphitronSchema inTableTypeSchema(String typeName, String fieldName, GraphitronField field) {
        var parentType = new TableType(typeName, null, typeName.toLowerCase(), new ResolvedTable(typeName.toUpperCase(), FILM), new NoNode());
        return schema(parentType, fieldName, field);
    }

    /**
     * Wraps a single field under a TableType parent that carries {@code @node} (no explicit
     * key columns — primary key is used at generation time). Use for {@code NodeIdField} valid
     * cases where the source type is required to have {@code @node}.
     */
    public static GraphitronSchema inNodeTableTypeSchema(String typeName, String fieldName, GraphitronField field) {
        var parentType = new TableType(typeName, null, typeName.toLowerCase(),
            new ResolvedTable(typeName.toUpperCase(), FILM),
            new NodeDirective(null, List.of()));
        return schema(parentType, fieldName, field);
    }

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
        return VALIDATOR.validate(schema);
    }

    public static List<ValidationError> validate(GraphitronType type) {
        return VALIDATOR.validate(new GraphitronSchema(Map.of(type.name(), type), Map.of()));
    }
}
