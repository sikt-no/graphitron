package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.BatchKey;

/**
 * Validates a {@link GraphitronSchema}, collecting all errors rather than failing on the first.
 *
 * <p>Each validation method receives the classified field or type and appends to the shared
 * error list. The Maven plugin calls this after {@code GraphitronSchemaBuilder.build()} and formats
 * the resulting {@link ValidationError} list as compiler-style messages with file and line
 * references.
 */
public class GraphitronSchemaValidator {

    public List<ValidationError> validate(GraphitronSchema schema) {
        var types = schema.types();
        var errors = new ArrayList<ValidationError>();
        types.values().forEach(type -> validateType(type, types, errors));
        schema.fields().values().forEach(field -> validateField(field, types, errors));
        return List.copyOf(errors);
    }

    private void validateType(GraphitronType type, Map<String, GraphitronType> types, List<ValidationError> errors) {
        switch (type) {
            case no.sikt.graphitron.rewrite.model.GraphitronType.TableType t          -> validateTableType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.NodeType t           -> validateNodeType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.ResultType t         -> validateResultType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.RootType t           -> validateRootType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType t -> validateTableInterfaceType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType t      -> validateInterfaceType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.UnionType t          -> validateUnionType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType t          -> {} // no structural validation needed
            case no.sikt.graphitron.rewrite.model.GraphitronType.InputType t          -> validateInputType(t, types, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType t     -> validateTableInputType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType t   -> validateUnclassifiedType(t, errors);
        }
    }

    private void validateField(GraphitronField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        switch (field) {
            case no.sikt.graphitron.rewrite.model.QueryField.QueryLookupTableField f        -> validateQueryLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableField f         -> validateQueryTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableMethodTableField f   -> validateQueryTableMethodTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryNodeField f          -> validateQueryNodeField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryEntityField f        -> validateQueryEntityField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableInterfaceField f -> validateQueryTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryInterfaceField f     -> validateQueryInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryUnionField f         -> validateQueryUnionField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableField f       -> validateQueryServiceTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryServiceRecordField f      -> validateQueryServiceRecordField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationInsertTableField f     -> validateMutationInsertTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationUpdateTableField f     -> validateMutationUpdateTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationDeleteTableField f     -> validateMutationDeleteTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationUpsertTableField f     -> validateMutationUpsertTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableField f    -> validateMutationServiceTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationServiceRecordField f   -> validateMutationServiceRecordField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ColumnField f             -> validateColumnField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField f    -> validateColumnReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.NodeIdField f             -> validateNodeIdField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.NodeIdReferenceField f    -> validateNodeIdReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.TableField f              -> validateTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.SplitTableField f        -> validateSplitTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.LookupTableField f       -> validateLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField f  -> validateSplitLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.TableMethodField f        -> validateTableMethodField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField f     -> validateTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.InterfaceField f          -> validateInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.UnionField f              -> validateUnionField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.NestingField f            -> validateNestingField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ConstructorField f        -> validateConstructorField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField f       -> validateServiceTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField f      -> validateServiceRecordField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordTableField f        -> validateRecordTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField f  -> validateRecordLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordField f             -> validateRecordField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ComputedField f           -> validateComputedField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.PropertyField f           -> validatePropertyField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField f -> validateMultitableReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.InputField.ColumnField f            -> validateInputColumnField(f, errors);
            case no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField f  -> validateInputColumnReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronField.NotGeneratedField f -> validateNotGeneratedField(f, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField f -> validateUnclassifiedField(f, errors);
        }
    }

    // --- Type validators (stubs — filled in as test classes are added) ---

    private void validateTableType(no.sikt.graphitron.rewrite.model.GraphitronType.TableType type, List<ValidationError> errors) {
        // Unresolved tables are caught by the builder (UnclassifiedType). Nothing more to validate here.
    }
    private void validateNodeType(no.sikt.graphitron.rewrite.model.GraphitronType.NodeType type, List<ValidationError> errors) {
        // Unresolved tables and unresolved @node key columns are caught by the builder (UnclassifiedType).
    }
    private void validateResultType(no.sikt.graphitron.rewrite.model.GraphitronType.ResultType type, List<ValidationError> errors) {}
    private void validateRootType(no.sikt.graphitron.rewrite.model.GraphitronType.RootType type, List<ValidationError> errors) {}
    private void validateTableInterfaceType(no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType type, List<ValidationError> errors) {
        validateParticipants(type.name(), type.participants(), errors);
    }
    private void validateInterfaceType(no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType type, List<ValidationError> errors) {
        validateParticipants(type.name(), type.participants(), errors);
    }
    private void validateUnionType(no.sikt.graphitron.rewrite.model.GraphitronType.UnionType type, List<ValidationError> errors) {
        validateParticipants(type.name(), type.participants(), errors);
    }

    private void validateInputType(no.sikt.graphitron.rewrite.model.GraphitronType.InputType type, Map<String, GraphitronType> types, List<ValidationError> errors) {
        // Type-existence of field types is already guaranteed by graphql-java schema validation.
        // Graphitron-specific constraints (e.g. javaName deprecation) will be added here.
    }

    private void validateTableInputType(no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType type, List<ValidationError> errors) {
        // Unresolved tables and unresolved fields are caught by the builder (UnclassifiedType).
    }

    private void validateParticipants(String typeName, java.util.List<no.sikt.graphitron.rewrite.model.ParticipantRef> participants, List<ValidationError> errors) {
        // Unbound participants are caught by the builder (UnclassifiedType). Nothing to validate here.
    }

    // --- Field validators (stubs — filled in as test classes are added) ---

    private void validateQueryLookupTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        } else {
            boolean anyFilterIsList = field.filters().stream().anyMatch(f -> switch (f) {
                case no.sikt.graphitron.rewrite.model.GeneratedConditionFilter gcf ->
                    gcf.bodyParams().stream().anyMatch(bp -> bp.list());
                case no.sikt.graphitron.rewrite.model.ConditionFilter ignored -> false;
            });
            boolean returnIsList = field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.List;
            if (anyFilterIsList != returnIsList) {
                errors.add(new ValidationError(
                    "Field '" + field.name() + "': result type does not match input cardinality",
                    field.location()
                ));
            }
        }
        if (field.orderBy() instanceof OrderBySpec.Argument) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @orderBy is not valid on a lookup field",
                field.location()
            ));
        }
    }
    private void validateQueryTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryTableMethodTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryTableMethodTableField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryNodeField(no.sikt.graphitron.rewrite.model.QueryField.QueryNodeField field, List<ValidationError> errors) {}
    private void validateQueryEntityField(no.sikt.graphitron.rewrite.model.QueryField.QueryEntityField field, List<ValidationError> errors) {}
    private void validateQueryTableInterfaceField(no.sikt.graphitron.rewrite.model.QueryField.QueryTableInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryInterfaceField(no.sikt.graphitron.rewrite.model.QueryField.QueryInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryUnionField(no.sikt.graphitron.rewrite.model.QueryField.QueryUnionField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryServiceTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        // Unresolved service method is caught by the builder (UnclassifiedField).
    }
    private void validateQueryServiceRecordField(no.sikt.graphitron.rewrite.model.QueryField.QueryServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
    }
    private void validateMutationInsertTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationInsertTableField field, List<ValidationError> errors) {}
    private void validateMutationUpdateTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationUpdateTableField field, List<ValidationError> errors) {}
    private void validateMutationDeleteTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationDeleteTableField field, List<ValidationError> errors) {}
    private void validateMutationUpsertTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationUpsertTableField field, List<ValidationError> errors) {}
    private void validateMutationServiceTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableField field, List<ValidationError> errors) {
        // Unresolved service method is caught by the builder (UnclassifiedField).
    }
    private void validateMutationServiceRecordField(no.sikt.graphitron.rewrite.model.MutationField.MutationServiceRecordField field, List<ValidationError> errors) {}
    private void validateColumnField(no.sikt.graphitron.rewrite.model.ChildField.ColumnField field, List<ValidationError> errors) {
        if (field.javaNamePresent()) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @field(javaName:) is not supported in record-based output",
                field.location()
            ));
        }
    }
    private void validateColumnReferenceField(no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField field, List<ValidationError> errors) {
        if (field.javaNamePresent()) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @field(javaName:) is not supported in record-based output",
                field.location()
            ));
        }
        if (field.joinPath().isEmpty()) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @reference path is required",
                field.location()
            ));
        } else {
            validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
        }
    }
    private void validateNodeIdField(no.sikt.graphitron.rewrite.model.ChildField.NodeIdField field, List<ValidationError> errors) {
        // NodeIdField is only classified when the parent type is a NodeType.
        // The absence-of-@node case is classified as UnclassifiedField in the builder.
    }
    private void validateNodeIdReferenceField(no.sikt.graphitron.rewrite.model.ChildField.NodeIdReferenceField field, List<ValidationError> errors) {
        // @node is always resolved — builder returns UnclassifiedField if the type is missing or lacks @node
        // Use targetType for table-level FK and path validation
        if (!(field.targetType() instanceof ReturnTypeRef.TableBoundReturnType tb)) {
            validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
            return;
        }
        no.sikt.graphitron.rewrite.model.TableRef targetTable = tb.table();

        if (field.joinPath().isEmpty()) {
            // Implicit join: exactly one FK must exist between parent and target tables
            var parentTable = field.parentTable();
            if (parentTable != null) {
                int fkCount = TableReflection.getNumberOfForeignKeysBetweenTables(
                    parentTable.javaFieldName(), targetTable.javaFieldName());
                if (fkCount == 0) {
                    errors.add(new ValidationError(
                        "Field '" + field.name() + "': no foreign key found between tables '"
                            + parentTable.tableName() + "' and '"
                            + targetTable.tableName()
                            + "'; add a @reference directive to specify the join path",
                        field.location()
                    ));
                } else if (fkCount > 1) {
                    errors.add(new ValidationError(
                        "Field '" + field.name() + "': multiple foreign keys found between tables '"
                            + parentTable.tableName() + "' and '"
                            + targetTable.tableName()
                            + "'; add a @reference directive to specify the join path",
                        field.location()
                    ));
                }
            }
        } else {
            // Explicit reference path: validate steps and check it leads to the target type's table
            validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
            validateReferenceLeadsToType(field.name(), field.location(), field.joinPath(), field.typeName(), targetTable, errors);
        }
    }

    private void validateReferenceLeadsToType(String fieldName, SourceLocation location, List<JoinStep> path, String typeName, no.sikt.graphitron.rewrite.model.TableRef targetTable, List<ValidationError> errors) {
        var lastStep = path.getLast();
        switch (lastStep) {
            case JoinStep.FkJoin fk -> {
                if (!fk.targetTableSqlName().equalsIgnoreCase(targetTable.tableName())) {
                    errors.add(new ValidationError(
                        "Field '" + fieldName + "': @reference path does not lead to the table of type '" + typeName + "'",
                        location
                    ));
                }
            }
            case JoinStep.ConditionJoin ignored -> { /* no FK tables to check */ }
        }
    }
    private void validateTableField(no.sikt.graphitron.rewrite.model.ChildField.TableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateSplitTableField(no.sikt.graphitron.rewrite.model.ChildField.SplitTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.LookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateSplitLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateTableMethodField(no.sikt.graphitron.rewrite.model.ChildField.TableMethodField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateTableInterfaceField(no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateInterfaceField(no.sikt.graphitron.rewrite.model.ChildField.InterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateUnionField(no.sikt.graphitron.rewrite.model.ChildField.UnionField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateNestingField(no.sikt.graphitron.rewrite.model.ChildField.NestingField field, List<ValidationError> errors) {}
    private void validateConstructorField(no.sikt.graphitron.rewrite.model.ChildField.ConstructorField field, List<ValidationError> errors) {}
    private void validateServiceTableField(no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);

        var smr = field.method();

        // For Row-keyed and Record-keyed, the parent must have a PK so the key
        // expression can be built. ObjectBased uses the whole parent as the key.
        boolean hasRowOrRecordKeyed = smr.params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> ((MethodRef.Param.Sourced) p).batchKey())
            .anyMatch(s -> s instanceof BatchKey.RowKeyed || s instanceof BatchKey.RecordKeyed);

        if (!hasRowOrRecordKeyed) {
            return; // TableRecordKeyed — no PK constraint on the parent table
        }

        var parentType = types.get(field.parentTypeName());
        if (!(parentType instanceof TableBackedType tbt)) {
            return; // non-table parent; no DataLoader key needed
        }
        TableRef parentTable = tbt.table();
        if (!parentTable.hasPrimaryKey()) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @service on a table-bound return type requires the parent table '" + parentTable.tableName() + "' to have a primary key",
                field.location()
            ));
        }
    }
    private void validateServiceRecordField(no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
    }
    private void validateRecordTableField(no.sikt.graphitron.rewrite.model.ChildField.RecordTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateRecordLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateRecordField(no.sikt.graphitron.rewrite.model.ChildField.RecordField field, List<ValidationError> errors) {}

    private void validateComputedField(no.sikt.graphitron.rewrite.model.ChildField.ComputedField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.joinPath(), errors);
    }
    private void validatePropertyField(no.sikt.graphitron.rewrite.model.ChildField.PropertyField field, List<ValidationError> errors) {}
    private void validateMultitableReferenceField(no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField field, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Field '" + field.name() + "': @multitableReference is not supported in record-based output",
            field.location()
        ));
    }
    private void validateInputColumnField(no.sikt.graphitron.rewrite.model.InputField.ColumnField field, List<ValidationError> errors) {
        // Column resolution is guaranteed by the builder (unresolved → UnclassifiedType). Nothing to validate here.
    }
    private void validateInputColumnReferenceField(no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField field, List<ValidationError> errors) {
        // Column and join path resolution is guaranteed by the builder (unresolved → UnclassifiedType). Nothing to validate here.
    }
    private void validateNotGeneratedField(no.sikt.graphitron.rewrite.model.GraphitronField.NotGeneratedField field, List<ValidationError> errors) {}
    private void validateUnclassifiedType(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType type, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Type '" + type.name() + "': could not be classified — " + type.reason(),
            type.location()
        ));
    }

    private void validateUnclassifiedField(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField field, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Field '" + field.name() + "': could not be classified — " + field.reason(),
            field.location()
        ));
    }

    private void validateCardinality(String fieldName, SourceLocation location, no.sikt.graphitron.rewrite.model.FieldWrapper cardinality, List<ValidationError> errors) {
        // Order specs are fully resolved to ColumnOrder at build time; no per-variant validation
        // is needed here. The switch exhausts all cases to keep the compiler warning-free.
        switch (cardinality) {
            case no.sikt.graphitron.rewrite.model.FieldWrapper.Single ignored -> {}
            case no.sikt.graphitron.rewrite.model.FieldWrapper.List ignored -> {}
            case no.sikt.graphitron.rewrite.model.FieldWrapper.Connection ignored -> {}
        }
    }

    /**
     * No-op: all path elements are guaranteed resolved by the builder (unresolved paths produce
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} instead).
     */
    private void validateReferencePath(String fieldName, SourceLocation location, List<JoinStep> path, List<ValidationError> errors) {
        // All elements are resolved — builder rejects unresolved paths at classification time.
    }
}
