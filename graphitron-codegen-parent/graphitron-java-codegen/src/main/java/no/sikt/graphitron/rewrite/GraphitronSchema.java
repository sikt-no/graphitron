package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.List;
import java.util.Map;

/**
 * The parsed representation of a GraphQL schema. Holds all classified types and fields.
 *
 * <p>Types are keyed by name. Output types ({@link GraphitronType.TableType},
 * {@link GraphitronType.NodeType}, and {@link GraphitronType.RootType}) carry a
 * {@code fieldCoordinates()} list recording the schema coordinates of their fields in declaration order.
 *
 * <p>The {@link #fields} map is the authoritative flat index of all classified fields, keyed by
 * {@link FieldCoordinates}. Use {@link #field} for O(1) point lookups and {@link #fieldsOf} to
 * retrieve all fields belonging to a given type.
 */
public record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields
) {

    /**
     * Returns the field at the given coordinates, or {@code null} if absent.
     */
    public GraphitronField field(String typeName, String fieldName) {
        return fields.get(FieldCoordinates.coordinates(typeName, fieldName));
    }

    /**
     * Returns the type with the given name, or {@code null} if absent.
     */
    public GraphitronType type(String typeName) {
        return types.get(typeName);
    }

    /**
     * Returns all fields belonging to {@code typeName}, in declaration order, or an empty list
     * if the type has no fields recorded in this schema.
     */
    public List<GraphitronField> fieldsOf(String typeName) {
        return fields.values().stream()
            .filter(f -> typeName.equals(f.parentTypeName()))
            .toList();
    }

    /**
     * Returns all fields across all types whose return type is bound to the given table.
     * This includes root query fields, child table fields, split fields, etc.
     *
     * <p>Used by table-class generators to find all fields that need condition methods,
     * subselect methods, or other per-field SQL artefacts on a table class.
     */
    public List<GraphitronField> fieldsTargeting(String tableClassName) {
        return fields.values().stream()
            .filter(f -> {
                var table = tableBoundReturnTable(f);
                return table != null && table.javaClassName().equals(tableClassName);
            })
            .toList();
    }

    /**
     * Extracts the {@link no.sikt.graphitron.rewrite.model.TableRef} from a field's return type
     * when it is table-bound, or {@code null} otherwise.
     */
    public static no.sikt.graphitron.rewrite.model.TableRef tableBoundReturnTable(GraphitronField field) {
        var returnType = switch (field) {
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.QueryField.QueryLookupTableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableInterfaceField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableMethodTableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.ChildField.TableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.ChildField.SplitTableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.ChildField.LookupTableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.ChildField.RecordTableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField f -> f.returnType();
            case no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField f -> f.returnType();
            default -> null;
        };
        return returnType instanceof no.sikt.graphitron.rewrite.model.ReturnTypeRef.TableBoundReturnType tb
            ? tb.table() : null;
    }
}
