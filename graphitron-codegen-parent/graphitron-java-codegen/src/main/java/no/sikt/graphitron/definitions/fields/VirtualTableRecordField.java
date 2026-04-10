package no.sikt.graphitron.definitions.fields;

import no.sikt.graphitron.definitions.interfaces.GenerationTarget;

/**
 * Virtual field representing a table record, used when code generation needs a table reference
 * without a corresponding GraphQL field definition.
 */
public record VirtualTableRecordField(String tableName) implements GenerationTarget {
}
