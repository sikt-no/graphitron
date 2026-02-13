package no.sikt.graphitron.mojo.lsp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Root configuration object for LSP tooling.
 */
public record LspConfig(
    List<TableConfig> tables,
    List<TypeConfig> types,
    @JsonProperty("external_references") @JsonInclude(JsonInclude.Include.NON_NULL) List<ExternalReferenceConfig> externalReferences
) {
    /**
     * Configuration for a single database table.
     */
    public record TableConfig(
        @JsonProperty("table_name") String tableName,
        String description,
        TableDefinition definition,
        List<TableReference> references,
        List<FieldConfig> fields
    ) {}

    /**
     * Configuration for a table field/column.
     */
    public record FieldConfig(
        @JsonProperty("field_name") String fieldName,
        @JsonProperty("field_type") String fieldType,
        boolean nullable
    ) {}

    /**
     * Definition location for a table.
     */
    public record TableDefinition(
        String file,
        int line,
        int col
    ) {}

    /**
     * Foreign key reference between tables.
     */
    public record TableReference(
        String table,
        String key,
        boolean inverse
    ) {}

    /**
     * Type configuration for GraphQL scalar types.
     */
    public record TypeConfig(
        String name,
        List<String> aliases,
        String description
    ) {}

    /**
     * Configuration for an external reference (service, condition, record, etc.).
     */
    public record ExternalReferenceConfig(
        String name,
        @JsonProperty("class_name") String className,
        List<String> methods
    ) {}
}
