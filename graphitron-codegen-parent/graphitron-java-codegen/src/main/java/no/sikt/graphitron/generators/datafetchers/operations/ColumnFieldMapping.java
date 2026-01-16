package no.sikt.graphitron.generators.datafetchers.operations;

import no.sikt.graphitron.definitions.fields.InputField;

/**
 * Represents information about how to extract a column value from an input field.
 * For nodeId fields, this includes the node type and index in the composite key.
 * For regular fields, this is just the getter call.
 */
record FieldToColumnRecord(
        InputField field,
        boolean isNodeId,
        String nodeTypeId,         // Only set for nodeId fields
        int columnIndex,           // Index in the unpacked array for nodeId fields
        String unpackedVarName,    // Variable name for unpacked nodeId values
        String columnName          // The actual column name being mapped
) {
    static FieldToColumnRecord forRegularField(InputField field, String columnName) {
        return new FieldToColumnRecord(field, false, null, -1, null, columnName);
    }

    static FieldToColumnRecord forNodeIdField(InputField field, String nodeTypeId, int columnIndex, String unpackedVarName, String columnName) {
        return new FieldToColumnRecord(field, true, nodeTypeId, columnIndex, unpackedVarName, columnName);
    }
}