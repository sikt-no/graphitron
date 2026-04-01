package no.sikt.graphitron.record.validation;

import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLTypeReference;
import no.sikt.graphitron.record.GraphitronSchema;
import no.sikt.graphitron.record.GraphitronSchemaValidator;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.RootType;
import no.sikt.graphitron.record.type.TableType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    // --- Type reference helpers ---

    public static GraphQLTypeReference typeRef(String name) {
        return new GraphQLTypeReference(name);
    }

    public static GraphQLList list(GraphQLOutputType type) {
        return new GraphQLList(type);
    }

    public static GraphQLNonNull nonNull(GraphQLOutputType type) {
        return new GraphQLNonNull(type);
    }

    // --- Field definition helpers ---

    public static GraphQLFieldDefinition fieldOf(String name, GraphQLOutputType type) {
        return GraphQLFieldDefinition.newFieldDefinition()
            .name(name)
            .type(type)
            .build();
    }

    // --- Type definition helpers ---

    public static GraphQLObjectType objectType(String name) {
        return GraphQLObjectType.newObject().name(name).build();
    }

    // --- Schema assembly helpers ---

    /**
     * Wraps a single field under "Query" (RootType). Use for all QueryField and MutationField cases.
     */
    public static GraphitronSchema inQuerySchema(String fieldName, GraphitronField field) {
        return schema(new RootType(objectType("Query")), fieldName, field);
    }

    /**
     * Wraps a single field under a TableType parent. Use for ChildField cases where the
     * parent context is table-mapped.
     */
    public static GraphitronSchema inTableTypeSchema(String typeName, String fieldName, GraphitronField field) {
        var parentType = new TableType(objectType(typeName), typeName.toLowerCase(), typeName.toUpperCase(), Optional.empty());
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

    // --- Validation runner ---

    public static List<ValidationError> validate(GraphitronSchema schema) {
        return VALIDATOR.validate(schema);
    }
}
