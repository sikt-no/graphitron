package no.sikt.graphitron.record.validation;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.record.GraphitronSchema;
import no.sikt.graphitron.record.GraphitronSchemaValidator;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.NodeRef.NodeDirective;
import no.sikt.graphitron.record.type.NodeRef.NoNode;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;
import no.sikt.graphitron.record.type.GraphitronType.RootType;
import no.sikt.graphitron.record.type.GraphitronType.TableType;
import org.jooq.Table;

import java.util.List;
import java.util.Map;

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
     * Wraps a single field under "Query" (RootType). Use for QueryField cases.
     */
    public static GraphitronSchema inQuerySchema(String fieldName, GraphitronField field) {
        return schema(new RootType("Query", null), fieldName, field);
    }

    /**
     * Wraps a single field under a TableType parent with no {@code @node} directive.
     * Use when the validator needs access to the parent type's table metadata (e.g.
     * {@link no.sikt.graphitron.record.field.ChildField.NodeIdReferenceField} FK resolution).
     * For fields where the validator reads only the field itself, prefer
     * {@link #validate(GraphitronField)}.
     */
    public static GraphitronSchema inTableTypeSchema(String typeName, String fieldName, GraphitronField field) {
        var parentType = new TableType(typeName, null, new ResolvedTable(typeName.toLowerCase(), typeName.toUpperCase(), null), new NoNode());
        return schema(parentType, fieldName, field);
    }

    /**
     * Wraps a single field under a TableType parent, with a separate {@code @node}-annotated
     * TableType as the target of a {@code @nodeId(typeName:)} reference.
     *
     * <p>The {@code javaFieldName} for each type is derived from {@code table.getName().toUpperCase()}
     * (e.g. {@code "film"} → {@code "FILM"}), which must match the field name in the generated
     * jOOQ {@code Tables} class.
     */
    public static GraphitronSchema inTableTypeSchemaWithNodeTarget(
            String parentTypeName, Table<?> parentTable,
            String targetTypeName, Table<?> targetTable,
            String fieldName, GraphitronField field) {
        var parent = new TableType(parentTypeName, null,
            new ResolvedTable(parentTable.getName(), parentTable.getName().toUpperCase(), parentTable), new NoNode());
        var target = new TableType(targetTypeName, null,
            new ResolvedTable(targetTable.getName(), targetTable.getName().toUpperCase(), targetTable),
            new NodeDirective(null, List.of()));
        return new GraphitronSchema(
            Map.of(parent.name(), parent, target.name(), target),
            Map.of(FieldCoordinates.coordinates(parentTypeName, fieldName), field)
        );
    }

    /**
     * Wraps a single QueryField under "Query" with a separate named return type visible to the
     * validator. Use when the validator needs to look up properties of the return type (e.g. PK
     * presence for non-deterministic ordering checks).
     */
    public static GraphitronSchema inQuerySchemaWithReturnType(String fieldName, GraphitronField field, GraphitronType returnType) {
        return new GraphitronSchema(
            Map.of("Query", new RootType("Query", null), returnType.name(), returnType),
            Map.of(FieldCoordinates.coordinates("Query", fieldName), field)
        );
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

    /**
     * Validates a single field without requiring a hand-assembled schema.
     *
     * <p>For the vast majority of field types, the validator only inspects the field's own
     * properties — {@code parentTypeName}, {@code name}, {@code location}, and the data
     * fields specific to each leaf type. The parent type in the schema is not consulted.
     * Two exceptions require explicit schema context and must use a different helper:
     * <ul>
     *   <li>{@link no.sikt.graphitron.record.field.QueryField.TableQueryField} — the validator
     *       looks up the return type to check primary-key presence; use
     *       {@link #inQuerySchemaWithReturnType}.</li>
     *   <li>{@link no.sikt.graphitron.record.field.ChildField.NodeIdReferenceField} — the
     *       validator looks up the {@code @node}-annotated target type; use
     *       {@link #inTableTypeSchemaWithNodeTarget}.</li>
     * </ul>
     */
    public static List<ValidationError> validate(GraphitronField field) {
        return validate(schema(new RootType(field.parentTypeName(), null), field.name(), field));
    }
}
