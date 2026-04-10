package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphitron.rewrite.field.FieldConditionRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.ConditionOnlyRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkWithConditionRef;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.ArgumentRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef;
import no.sikt.graphitron.rewrite.field.ColumnRef.UnresolvedColumn;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.UnresolvedConditionRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.UnresolvedKeyAndConditionRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.UnresolvedKeyRef;
import no.sikt.graphitron.rewrite.field.NodeTypeRef.ResolvedNodeType;
import no.sikt.graphitron.rewrite.field.NodeTypeRef.NoNodeDirectiveType;
import no.sikt.graphitron.rewrite.field.NodeTypeRef.NotFoundNodeType;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.type.GraphitronType;
import no.sikt.graphitron.rewrite.type.NodeRef.NodeDirective;
import no.sikt.graphitron.rewrite.type.ParticipantRef.UnboundParticipant;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.type.TableRef.UnresolvedTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import no.sikt.graphitron.rewrite.type.InputFieldRef;
import no.sikt.graphitron.rewrite.type.InputFieldSpec;

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
            case no.sikt.graphitron.rewrite.type.GraphitronType.TableType t          -> validateTableType(t, errors);
            case no.sikt.graphitron.rewrite.type.GraphitronType.ResultType t         -> validateResultType(t, errors);
            case no.sikt.graphitron.rewrite.type.GraphitronType.RootType t           -> validateRootType(t, errors);
            case no.sikt.graphitron.rewrite.type.GraphitronType.TableInterfaceType t -> validateTableInterfaceType(t, errors);
            case no.sikt.graphitron.rewrite.type.GraphitronType.InterfaceType t      -> validateInterfaceType(t, errors);
            case no.sikt.graphitron.rewrite.type.GraphitronType.UnionType t          -> validateUnionType(t, errors);
            case no.sikt.graphitron.rewrite.type.GraphitronType.ErrorType t          -> {} // no structural validation needed
            case no.sikt.graphitron.rewrite.type.GraphitronType.InputType t          -> validateInputType(t, types, errors);
            case no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType t     -> validateTableInputType(t, errors);
            case no.sikt.graphitron.rewrite.type.GraphitronType.UnclassifiedType t   -> validateUnclassifiedType(t, errors);
        }
    }

    private void validateField(GraphitronField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        switch (field) {
            case no.sikt.graphitron.rewrite.field.QueryField.QueryLookupTableField f        -> validateQueryLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.QueryField.QueryTableField f         -> validateQueryTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.QueryField.QueryTableMethodTableField f   -> validateQueryTableMethodTableField(f, errors);
            case no.sikt.graphitron.rewrite.field.QueryField.QueryNodeField f          -> validateQueryNodeField(f, errors);
            case no.sikt.graphitron.rewrite.field.QueryField.QueryEntityField f        -> validateQueryEntityField(f, errors);
            case no.sikt.graphitron.rewrite.field.QueryField.QueryTableInterfaceField f -> validateQueryTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.field.QueryField.QueryInterfaceField f     -> validateQueryInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.field.QueryField.QueryUnionField f         -> validateQueryUnionField(f, errors);
            case no.sikt.graphitron.rewrite.field.QueryField.QueryServiceTableField f       -> validateQueryServiceTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.QueryField.QueryServiceRecordField f      -> validateQueryServiceRecordField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.MutationField.MutationInsertTableField f     -> validateMutationInsertTableField(f, errors);
            case no.sikt.graphitron.rewrite.field.MutationField.MutationUpdateTableField f     -> validateMutationUpdateTableField(f, errors);
            case no.sikt.graphitron.rewrite.field.MutationField.MutationDeleteTableField f     -> validateMutationDeleteTableField(f, errors);
            case no.sikt.graphitron.rewrite.field.MutationField.MutationUpsertTableField f     -> validateMutationUpsertTableField(f, errors);
            case no.sikt.graphitron.rewrite.field.MutationField.MutationServiceTableField f    -> validateMutationServiceTableField(f, errors);
            case no.sikt.graphitron.rewrite.field.MutationField.MutationServiceRecordField f   -> validateMutationServiceRecordField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.ColumnField f             -> validateColumnField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.ColumnReferenceField f    -> validateColumnReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.NodeIdField f             -> validateNodeIdField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.NodeIdReferenceField f    -> validateNodeIdReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.TableField f              -> validateTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.SplitTableField f        -> validateSplitTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.LookupTableField f       -> validateLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.SplitLookupTableField f  -> validateSplitLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.TableMethodField f        -> validateTableMethodField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.TableInterfaceField f     -> validateTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.InterfaceField f          -> validateInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.UnionField f              -> validateUnionField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.NestingField f            -> validateNestingField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.ConstructorField f        -> validateConstructorField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.ServiceTableField f       -> validateServiceTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.ServiceRecordField f      -> validateServiceRecordField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.RecordTableField f        -> validateRecordTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.RecordLookupTableField f  -> validateRecordLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.RecordField f             -> validateRecordField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.ComputedField f           -> validateComputedField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.PropertyField f           -> validatePropertyField(f, errors);
            case no.sikt.graphitron.rewrite.field.ChildField.MultitableReferenceField f -> validateMultitableReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.field.GraphitronField.NotGeneratedField f       -> validateNotGeneratedField(f, errors);
            case no.sikt.graphitron.rewrite.field.GraphitronField.UnclassifiedField f       -> validateUnclassifiedField(f, errors);
        }
    }

    // --- Type validators (stubs — filled in as test classes are added) ---

    private void validateTableType(no.sikt.graphitron.rewrite.type.GraphitronType.TableType type, List<ValidationError> errors) {
        if (type.table() instanceof UnresolvedTable) {
            errors.add(new ValidationError(
                "Type '" + type.name() + "': table '" + type.table().tableName() + "' could not be resolved in the jOOQ catalog",
                type.location()
            ));
        }
        if (type.node() instanceof no.sikt.graphitron.rewrite.type.NodeRef.NodeDirective nd) {
            for (var keyColumn : nd.keyColumns()) {
                if (keyColumn instanceof no.sikt.graphitron.rewrite.type.KeyColumnRef.UnresolvedKeyColumn u) {
                    errors.add(new ValidationError(
                        "Type '" + type.name() + "': key column '" + u.name() + "' in @node could not be resolved in the jOOQ table",
                        type.location()
                    ));
                }
            }
        }
    }
    private void validateResultType(no.sikt.graphitron.rewrite.type.GraphitronType.ResultType type, List<ValidationError> errors) {}
    private void validateRootType(no.sikt.graphitron.rewrite.type.GraphitronType.RootType type, List<ValidationError> errors) {}
    private void validateTableInterfaceType(no.sikt.graphitron.rewrite.type.GraphitronType.TableInterfaceType type, List<ValidationError> errors) {
        if (type.table() instanceof UnresolvedTable) {
            errors.add(new ValidationError(
                "Type '" + type.name() + "': table '" + type.table().tableName() + "' could not be resolved in the jOOQ catalog",
                type.location()
            ));
        }
        validateParticipants(type.name(), type.participants(), errors);
    }
    private void validateInterfaceType(no.sikt.graphitron.rewrite.type.GraphitronType.InterfaceType type, List<ValidationError> errors) {
        validateParticipants(type.name(), type.participants(), errors);
    }
    private void validateUnionType(no.sikt.graphitron.rewrite.type.GraphitronType.UnionType type, List<ValidationError> errors) {
        validateParticipants(type.name(), type.participants(), errors);
    }

    private void validateInputType(no.sikt.graphitron.rewrite.type.GraphitronType.InputType type, Map<String, GraphitronType> types, List<ValidationError> errors) {
        // Type-existence of field types is already guaranteed by graphql-java schema validation.
        // Graphitron-specific constraints (e.g. javaName deprecation) will be added here.
    }

    private void validateTableInputType(no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType type, List<ValidationError> errors) {
        if (type.table() instanceof UnresolvedTable) {
            errors.add(new ValidationError(
                "Input type '" + type.name() + "': table '" + type.table().tableName() + "' could not be resolved in the jOOQ catalog",
                type.location()
            ));
        }
        for (var field : type.fields()) {
            if (field instanceof InputFieldRef.UnresolvedInputField u) {
                errors.add(new ValidationError(
                    "Input type '" + type.name() + "', field '" + u.name() + "': column '" + u.columnName() + "' could not be resolved in the jOOQ table",
                    type.location()
                ));
            }
        }
    }

    private void validateParticipants(String typeName, java.util.List<no.sikt.graphitron.rewrite.type.ParticipantRef> participants, List<ValidationError> errors) {
        for (var participant : participants) {
            if (participant instanceof UnboundParticipant u) {
                errors.add(new ValidationError(
                    "Type '" + typeName + "': implementing type '" + u.typeName() + "' is not table-bound (missing @table directive)",
                    null
                ));
            }
        }
    }

    // --- Field validators (stubs — filled in as test classes are added) ---

    private void validateQueryLookupTableField(no.sikt.graphitron.rewrite.field.QueryField.QueryLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        boolean anyArgIsList = field.arguments().stream().anyMatch(ArgumentRef::list);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.field.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        } else {
            boolean returnIsList = field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.field.FieldWrapper.List;
            if (anyArgIsList != returnIsList) {
                errors.add(new ValidationError(
                    "Field '" + field.name() + "': result type does not match input cardinality",
                    field.location()
                ));
            }
        }
        for (var arg : field.arguments()) {
            switch (arg) {
                case ArgumentRef.InputTypeArg.OrderByArg a -> errors.add(new ValidationError(
                    "Field '" + field.name() + "': @orderBy is not valid on a lookup field",
                    field.location()
                ));
                case ArgumentRef.UnclassifiedArg a -> errors.add(new ValidationError(
                    "Field '" + field.name() + "', argument '" + a.name() + "': " + a.reason(),
                    field.location()
                ));
                case ArgumentRef.ScalarArg.UnboundScalarArg a -> errors.add(new ValidationError(
                    "Field '" + field.name() + "': argument '" + a.name()
                        + "' could not be resolved to column '" + a.columnName()
                        + "' on the return type's table",
                    field.location()
                ));
                case ArgumentRef.InputTypeArg.TableInputTypeArg ignored -> {} // valid lookup input type
                case ArgumentRef.InputTypeArg.PlainInputTypeArg ignored -> {} // valid lookup input type
                case ArgumentRef.ScalarArg.ColumnArg ignored            -> {} // valid scalar key argument
                case ArgumentRef.ScalarArg.ParamArg ignored             -> {} // valid direct parameter
            }
        }
    }
    private void validateQueryTableField(no.sikt.graphitron.rewrite.field.QueryField.QueryTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
        if (field.returnType().table() instanceof ResolvedTable rt) {
            validateDeterministicOrdering(field.name(), field.location(), field.returnType().wrapper(), rt, errors);
        }
        // UnresolvedTable: type validator reports the unresolved table; skip ordering check
    }

    /**
     * Warns when a list or connection field returns rows from a PK-less table with no
     * {@code @defaultOrder} and no {@code @orderBy} enum values. Without a primary key or explicit
     * ordering, the result order is non-deterministic across pages and repeated calls.
     */
    private void validateDeterministicOrdering(
            String fieldName, SourceLocation location, no.sikt.graphitron.rewrite.field.FieldWrapper cardinality,
            ResolvedTable table, List<ValidationError> errors) {
        boolean needsCheck = switch (cardinality) {
            case no.sikt.graphitron.rewrite.field.FieldWrapper.List l ->
                l.defaultOrder() == null && l.orderByValues().isEmpty();
            case no.sikt.graphitron.rewrite.field.FieldWrapper.Connection c ->
                c.defaultOrder() == null && c.orderByValues().isEmpty();
            case no.sikt.graphitron.rewrite.field.FieldWrapper.Single ignored -> false; // single fields don't paginate
        };
        if (!needsCheck) return;
        if (table.hasPrimaryKey()) return;

        errors.add(new ValidationError(
            "Field '" + fieldName + "': table '" + table.tableName()
                + "' has no @defaultOrder directive and no primary key — result ordering is non-deterministic",
            location
        ));
    }
    private void validateQueryTableMethodTableField(no.sikt.graphitron.rewrite.field.QueryField.QueryTableMethodTableField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryNodeField(no.sikt.graphitron.rewrite.field.QueryField.QueryNodeField field, List<ValidationError> errors) {}
    private void validateQueryEntityField(no.sikt.graphitron.rewrite.field.QueryField.QueryEntityField field, List<ValidationError> errors) {}
    private void validateQueryTableInterfaceField(no.sikt.graphitron.rewrite.field.QueryField.QueryTableInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryInterfaceField(no.sikt.graphitron.rewrite.field.QueryField.QueryInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryUnionField(no.sikt.graphitron.rewrite.field.QueryField.QueryUnionField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryServiceTableField(no.sikt.graphitron.rewrite.field.QueryField.QueryServiceTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
        if (field.serviceMethodRef() instanceof no.sikt.graphitron.rewrite.field.ServiceMethodRef.Unresolved u) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': service method could not be resolved — " + u.reason(),
                field.location()
            ));
        }
    }
    private void validateQueryServiceRecordField(no.sikt.graphitron.rewrite.field.QueryField.QueryServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateMutationInsertTableField(no.sikt.graphitron.rewrite.field.MutationField.MutationInsertTableField field, List<ValidationError> errors) {}
    private void validateMutationUpdateTableField(no.sikt.graphitron.rewrite.field.MutationField.MutationUpdateTableField field, List<ValidationError> errors) {}
    private void validateMutationDeleteTableField(no.sikt.graphitron.rewrite.field.MutationField.MutationDeleteTableField field, List<ValidationError> errors) {}
    private void validateMutationUpsertTableField(no.sikt.graphitron.rewrite.field.MutationField.MutationUpsertTableField field, List<ValidationError> errors) {}
    private void validateMutationServiceTableField(no.sikt.graphitron.rewrite.field.MutationField.MutationServiceTableField field, List<ValidationError> errors) {
        if (field.serviceMethodRef() instanceof no.sikt.graphitron.rewrite.field.ServiceMethodRef.Unresolved u) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': service method could not be resolved — " + u.reason(),
                field.location()
            ));
        }
    }
    private void validateMutationServiceRecordField(no.sikt.graphitron.rewrite.field.MutationField.MutationServiceRecordField field, List<ValidationError> errors) {}
    private void validateColumnField(no.sikt.graphitron.rewrite.field.ChildField.ColumnField field, List<ValidationError> errors) {
        if (field.column() instanceof UnresolvedColumn) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': column '" + field.columnName() + "' could not be resolved in the jOOQ table",
                field.location()
            ));
        }
        if (field.javaNamePresent()) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @field(javaName:) is not supported in record-based output",
                field.location()
            ));
        }
    }
    private void validateColumnReferenceField(no.sikt.graphitron.rewrite.field.ChildField.ColumnReferenceField field, List<ValidationError> errors) {
        if (field.column() instanceof UnresolvedColumn) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': column '" + field.columnName() + "' could not be resolved in the jOOQ table",
                field.location()
            ));
        }
        if (field.javaNamePresent()) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @field(javaName:) is not supported in record-based output",
                field.location()
            ));
        }
        if (field.referencePath().isEmpty()) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @reference path is required",
                field.location()
            ));
        } else {
            validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        }
    }
    private void validateNodeIdField(no.sikt.graphitron.rewrite.field.ChildField.NodeIdField field, List<ValidationError> errors) {
        if (field.node() instanceof no.sikt.graphitron.rewrite.type.NodeRef.NoNode) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @nodeId requires the containing type to have @node",
                field.location()
            ));
        }
    }
    private void validateNodeIdReferenceField(no.sikt.graphitron.rewrite.field.ChildField.NodeIdReferenceField field, List<ValidationError> errors) {
        switch (field.nodeType()) {
            case NotFoundNodeType ignored -> {
                errors.add(new ValidationError(
                    "Field '" + field.name() + "': type '" + field.typeName() + "' does not exist in the schema",
                    field.location()
                ));
                validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
                return;
            }
            case NoNodeDirectiveType ignored -> {
                errors.add(new ValidationError(
                    "Field '" + field.name() + "': type '" + field.typeName() + "' does not have @node",
                    field.location()
                ));
                validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
                return;
            }
            case ResolvedNodeType ignored -> {} // @node resolved; continue to table validation
        }

        // @node is resolved; use targetType for table-level FK and path validation
        if (!(field.targetType() instanceof ReturnTypeRef.TableBoundReturnType tb)) {
            validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
            return;
        }
        if (!(tb.table() instanceof ResolvedTable targetTable)) {
            // Target type's table is unresolved; type validator reports that error
            validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
            return;
        }

        if (field.referencePath().isEmpty()) {
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
            validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
            validateReferenceLeadsToType(field.name(), field.location(), field.referencePath(), field.typeName(), targetTable, errors);
        }
    }

    private void validateReferenceLeadsToType(String fieldName, SourceLocation location, List<ReferencePathElementRef> path, String typeName, ResolvedTable targetTable, List<ValidationError> errors) {
        var lastStep = path.getLast();
        String fkTableSql = null, keyTableSql = null;
        switch (lastStep) {
            case FkRef s              -> { fkTableSql = s.fkTableSqlName(); keyTableSql = s.keyTableSqlName(); }
            case FkWithConditionRef s -> { fkTableSql = s.fkTableSqlName(); keyTableSql = s.keyTableSqlName(); }
            case ConditionOnlyRef ignored          -> { return; } // no FK tables to check; condition method only
            case UnresolvedKeyRef ignored          -> { return; } // unresolved FK already reported elsewhere
            case UnresolvedConditionRef ignored    -> { return; } // unresolved condition already reported elsewhere
            case UnresolvedKeyAndConditionRef ignored -> { return; } // both already reported elsewhere
        }
        var targetSqlName = targetTable.tableName();
        if (!fkTableSql.equalsIgnoreCase(targetSqlName) &&
            !keyTableSql.equalsIgnoreCase(targetSqlName)) {
            errors.add(new ValidationError(
                "Field '" + fieldName + "': @reference path does not lead to the table of type '" + typeName + "'",
                location
            ));
        }
    }
    private void validateTableField(no.sikt.graphitron.rewrite.field.ChildField.TableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.condition() instanceof FieldConditionRef.UnresolvedFieldCondition u) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': condition method '" + u.qualifiedName() + "' could not be resolved",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateSplitTableField(no.sikt.graphitron.rewrite.field.ChildField.SplitTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.condition() instanceof FieldConditionRef.UnresolvedFieldCondition u) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': condition method '" + u.qualifiedName() + "' could not be resolved",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateLookupTableField(no.sikt.graphitron.rewrite.field.ChildField.LookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.field.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateSplitLookupTableField(no.sikt.graphitron.rewrite.field.ChildField.SplitLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.field.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateTableMethodField(no.sikt.graphitron.rewrite.field.ChildField.TableMethodField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateTableInterfaceField(no.sikt.graphitron.rewrite.field.ChildField.TableInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateInterfaceField(no.sikt.graphitron.rewrite.field.ChildField.InterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateUnionField(no.sikt.graphitron.rewrite.field.ChildField.UnionField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateNestingField(no.sikt.graphitron.rewrite.field.ChildField.NestingField field, List<ValidationError> errors) {}
    private void validateConstructorField(no.sikt.graphitron.rewrite.field.ChildField.ConstructorField field, List<ValidationError> errors) {}
    private void validateServiceTableField(no.sikt.graphitron.rewrite.field.ChildField.ServiceTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);

        // Unresolved service method reference — cannot generate DataLoader code.
        if (field.serviceMethodRef() instanceof no.sikt.graphitron.rewrite.field.ServiceMethodRef.Unresolved u) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': service method could not be resolved — " + u.reason(),
                field.location()
            ));
            return;
        }

        // Validate each SOURCES parameter's SourcesRef variant.
        var smr = (no.sikt.graphitron.rewrite.field.ServiceMethodRef.Resolved) field.serviceMethodRef();
        var parentTypeForSources = types.get(field.parentTypeName());
        List<String> parentPkJavaTypes = (parentTypeForSources instanceof no.sikt.graphitron.rewrite.type.GraphitronType.TableType ttSrc
                && ttSrc.table() instanceof ResolvedTable prtSrc)
            ? prtSrc.primaryKeyColumnJavaTypes()
            : List.of();

        smr.params().stream()
            .filter(p -> p instanceof no.sikt.graphitron.rewrite.field.ServiceMethodRef.ServiceParam.SourcesParam)
            .map(p -> (no.sikt.graphitron.rewrite.field.ServiceMethodRef.ServiceParam.SourcesParam) p)
            .forEach(sp -> { switch (sp.sourcesRef()) {
                case no.sikt.graphitron.rewrite.field.SourcesRef.RowKeyed rk -> {
                    if (!parentPkJavaTypes.isEmpty() && !rk.pkJavaTypes().equals(parentPkJavaTypes)) {
                        String expected = buildExpectedKeysType("Row", parentPkJavaTypes);
                        String found    = buildExpectedKeysType("Row", rk.pkJavaTypes());
                        errors.add(new ValidationError(
                            "Field '" + field.name() + "': SOURCES parameter '" + sp.name()
                                + "' must be of type " + expected + ", found: " + found,
                            field.location()));
                    }
                }
                case no.sikt.graphitron.rewrite.field.SourcesRef.RecordKeyed rk -> {
                    if (!parentPkJavaTypes.isEmpty() && !rk.pkJavaTypes().equals(parentPkJavaTypes)) {
                        String expected = buildExpectedKeysType("Record", parentPkJavaTypes);
                        String found    = buildExpectedKeysType("Record", rk.pkJavaTypes());
                        errors.add(new ValidationError(
                            "Field '" + field.name() + "': SOURCES parameter '" + sp.name()
                                + "' must be of type " + expected + ", found: " + found,
                            field.location()));
                    }
                }
                case no.sikt.graphitron.rewrite.field.SourcesRef.TableRecordKeyed trk -> {
                    // The whole parent record is the key — no PK-column type check needed.
                }
                case no.sikt.graphitron.rewrite.field.SourcesRef.Unrecognized u -> {
                    errors.add(new ValidationError(
                        "Field '" + field.name() + "': SOURCES parameter '" + sp.name()
                            + "' type is not recognized — expected List<RowN<...>>, List<RecordN<...>>,"
                            + " or List<SomeTableRecord>, found: " + u.typeName(),
                        field.location()));
                }
            } });

        // For Row-keyed and Record-keyed, the parent must have a single-column PK so the key
        // expression can be built. TableRecordKeyed uses the whole parent record as the key.
        boolean hasRowOrRecordKeyed = smr.params().stream()
            .filter(p -> p instanceof no.sikt.graphitron.rewrite.field.ServiceMethodRef.ServiceParam.SourcesParam)
            .map(p -> (no.sikt.graphitron.rewrite.field.ServiceMethodRef.ServiceParam.SourcesParam) p)
            .anyMatch(sp -> sp.sourcesRef() instanceof no.sikt.graphitron.rewrite.field.SourcesRef.RowKeyed
                         || sp.sourcesRef() instanceof no.sikt.graphitron.rewrite.field.SourcesRef.RecordKeyed);

        if (!hasRowOrRecordKeyed) {
            return; // TableRecordKeyed — no PK constraint on the parent table
        }

        var parentType = types.get(field.parentTypeName());
        if (!(parentType instanceof no.sikt.graphitron.rewrite.type.GraphitronType.TableType tt)) {
            return; // non-table parent; no DataLoader key needed
        }
        if (!(tt.table() instanceof ResolvedTable parentTable)) {
            return; // unresolved parent table already reported by type validator
        }
        if (!parentTable.hasPrimaryKey()) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @service on a table-bound return type requires the parent table '" + parentTable.tableName() + "' to have a primary key",
                field.location()
            ));
            return;
        }
        if (parentTable.primaryKeyColumnSqlNames().size() > 1) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': composite primary keys are not yet supported for @service DataLoader generation (table '" + parentTable.tableName() + "')",
                field.location()
            ));
        }
    }
    private void validateServiceRecordField(no.sikt.graphitron.rewrite.field.ChildField.ServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
        if (field.serviceMethodRef() instanceof no.sikt.graphitron.rewrite.field.ServiceMethodRef.Unresolved u) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': service method could not be resolved — " + u.reason(),
                field.location()
            ));
        }
    }
    private void validateRecordTableField(no.sikt.graphitron.rewrite.field.ChildField.RecordTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.condition() instanceof FieldConditionRef.UnresolvedFieldCondition u) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': condition method '" + u.qualifiedName() + "' could not be resolved",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateRecordLookupTableField(no.sikt.graphitron.rewrite.field.ChildField.RecordLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.field.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateRecordField(no.sikt.graphitron.rewrite.field.ChildField.RecordField field, List<ValidationError> errors) {}

    /**
     * Builds the fully-qualified generic type string for a Row-keyed or Record-keyed SOURCES
     * parameter given the jOOQ type prefix ({@code "Row"} or {@code "Record"}) and the ordered
     * list of primary-key column Java type names.
     *
     * <p>Example: {@code "Row", ["java.lang.Long"]} →
     * {@code "java.util.List<org.jooq.Row1<java.lang.Long>>"}
     */
    private static String buildExpectedKeysType(String jooqPrefix, List<String> pkJavaTypes) {
        String jooqClass = "org.jooq." + jooqPrefix + pkJavaTypes.size();
        String typeParams = String.join(", ", pkJavaTypes);
        return "java.util.List<" + jooqClass + "<" + typeParams + ">>";
    }

    private void validateComputedField(no.sikt.graphitron.rewrite.field.ChildField.ComputedField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
    }
    private void validatePropertyField(no.sikt.graphitron.rewrite.field.ChildField.PropertyField field, List<ValidationError> errors) {}
    private void validateMultitableReferenceField(no.sikt.graphitron.rewrite.field.ChildField.MultitableReferenceField field, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Field '" + field.name() + "': @multitableReference is not supported in record-based output",
            field.location()
        ));
    }
    private void validateNotGeneratedField(no.sikt.graphitron.rewrite.field.GraphitronField.NotGeneratedField field, List<ValidationError> errors) {}
    private void validateUnclassifiedType(no.sikt.graphitron.rewrite.type.GraphitronType.UnclassifiedType type, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Type '" + type.name() + "': could not be classified — " + type.reason(),
            type.location()
        ));
    }

    private void validateUnclassifiedField(no.sikt.graphitron.rewrite.field.GraphitronField.UnclassifiedField field, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Field '" + field.name() + "': could not be classified — " + field.reason(),
            field.location()
        ));
    }

    private void validateArguments(String fieldName, SourceLocation location, List<ArgumentRef> arguments, Map<String, GraphitronType> types, List<ValidationError> errors) {
        // Type-existence of argument types is already guaranteed by graphql-java schema validation.
        // Graphitron-specific constraints will be added here as needed.
    }

    private void validateCardinality(String fieldName, SourceLocation location, no.sikt.graphitron.rewrite.field.FieldWrapper cardinality, List<ValidationError> errors) {
        switch (cardinality) {
            case no.sikt.graphitron.rewrite.field.FieldWrapper.Single ignored -> {}
            case no.sikt.graphitron.rewrite.field.FieldWrapper.List l -> {
                if (l.defaultOrder() != null) {
                    validateOrderSpec(fieldName, location, l.defaultOrder().spec(), errors);
                }
                for (var enumValue : l.orderByValues()) {
                    validateOrderSpec(fieldName, location, enumValue.spec(), errors);
                }
            }
            case no.sikt.graphitron.rewrite.field.FieldWrapper.Connection c -> {
                if (c.defaultOrder() != null) {
                    validateOrderSpec(fieldName, location, c.defaultOrder().spec(), errors);
                }
                for (var enumValue : c.orderByValues()) {
                    validateOrderSpec(fieldName, location, enumValue.spec(), errors);
                }
            }
        }
    }

    private void validateOrderSpec(String fieldName, SourceLocation location, no.sikt.graphitron.rewrite.field.OrderSpec spec, List<ValidationError> errors) {
        switch (spec) {
            case no.sikt.graphitron.rewrite.field.OrderSpec.IndexOrder ignored -> {}
            case no.sikt.graphitron.rewrite.field.OrderSpec.FieldsOrder ignored -> {}
            case no.sikt.graphitron.rewrite.field.OrderSpec.PrimaryKeyOrder ignored -> {}
            case no.sikt.graphitron.rewrite.field.OrderSpec.UnresolvedIndexOrder u -> errors.add(new ValidationError(
                "Field '" + fieldName + "': index '" + u.indexName() + "' could not be resolved in the jOOQ catalog",
                location));
            case no.sikt.graphitron.rewrite.field.OrderSpec.UnresolvedPrimaryKeyOrder ignored -> errors.add(new ValidationError(
                "Field '" + fieldName + "': primary key could not be resolved — the table may not have one",
                location));
        }
    }

    private void validateReferencePath(String fieldName, SourceLocation location, List<ReferencePathElementRef> path, List<ValidationError> errors) {
        for (var element : path) {
            switch (element) {
                case no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkRef ignored -> {}
                case no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkWithConditionRef ignored -> {}
                case no.sikt.graphitron.rewrite.field.ReferencePathElementRef.ConditionOnlyRef ignored -> {}
                case UnresolvedKeyRef u -> errors.add(new ValidationError(
                    "Field '" + fieldName + "': key '" + u.keyName() + "' could not be resolved in the jOOQ catalog",
                    location));
                case UnresolvedConditionRef u -> errors.add(new ValidationError(
                    "Field '" + fieldName + "': condition method '" + u.qualifiedName() + "' could not be resolved",
                    location));
                case UnresolvedKeyAndConditionRef u -> {
                    errors.add(new ValidationError(
                        "Field '" + fieldName + "': key '" + u.keyName() + "' could not be resolved in the jOOQ catalog",
                        location));
                    errors.add(new ValidationError(
                        "Field '" + fieldName + "': condition method '" + u.conditionName() + "' could not be resolved",
                        location));
                }
            }
        }
    }
}
