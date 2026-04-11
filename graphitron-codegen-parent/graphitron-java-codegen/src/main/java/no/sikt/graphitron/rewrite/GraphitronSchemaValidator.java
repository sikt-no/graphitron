package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphitron.rewrite.model.FieldConditionRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.ArgumentRef;
import no.sikt.graphitron.rewrite.model.ReferencePathElementRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.TableRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import no.sikt.graphitron.rewrite.model.InputFieldRef;
import no.sikt.graphitron.rewrite.model.InputFieldSpec;

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
            case no.sikt.graphitron.rewrite.model.GraphitronField.NotGeneratedField f       -> validateNotGeneratedField(f, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField f       -> validateUnclassifiedField(f, errors);
        }
    }

    // --- Type validators (stubs — filled in as test classes are added) ---

    private void validateTableType(no.sikt.graphitron.rewrite.model.GraphitronType.TableType type, List<ValidationError> errors) {
        // Unresolved tables and unresolved @node key columns are caught by the builder, which
        // produces UnclassifiedType instead. Nothing more to validate here.
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
        boolean anyArgIsList = field.arguments().stream().anyMatch(ArgumentRef::list);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        } else {
            boolean returnIsList = field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.List;
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
    private void validateQueryTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
        validateDeterministicOrdering(field.name(), field.location(), field.returnType().wrapper(), field.returnType().table(), errors);
    }

    /**
     * Warns when a list or connection field returns rows from a PK-less table with no
     * {@code @defaultOrder} and no {@code @orderBy} enum values. Without a primary key or explicit
     * ordering, the result order is non-deterministic across pages and repeated calls.
     */
    private void validateDeterministicOrdering(
            String fieldName, SourceLocation location, no.sikt.graphitron.rewrite.model.FieldWrapper cardinality,
            no.sikt.graphitron.rewrite.model.TableRef table, List<ValidationError> errors) {
        boolean needsCheck = switch (cardinality) {
            case no.sikt.graphitron.rewrite.model.FieldWrapper.List l ->
                l.defaultOrder() == null && l.orderByValues().isEmpty();
            case no.sikt.graphitron.rewrite.model.FieldWrapper.Connection c ->
                c.defaultOrder() == null && c.orderByValues().isEmpty();
            case no.sikt.graphitron.rewrite.model.FieldWrapper.Single ignored -> false; // single fields don't paginate
        };
        if (!needsCheck) return;
        if (table.hasPrimaryKey()) return;

        errors.add(new ValidationError(
            "Field '" + fieldName + "': table '" + table.tableName()
                + "' has no @defaultOrder directive and no primary key — result ordering is non-deterministic",
            location
        ));
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
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
        // Unresolved service method is caught by the builder (UnclassifiedField).
    }
    private void validateQueryServiceRecordField(no.sikt.graphitron.rewrite.model.QueryField.QueryServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
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
        if (field.referencePath().isEmpty()) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @reference path is required",
                field.location()
            ));
        } else {
            validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        }
    }
    private void validateNodeIdField(no.sikt.graphitron.rewrite.model.ChildField.NodeIdField field, List<ValidationError> errors) {
        // NodeIdField is only classified when the parent type carries @node (i.e. the TableType has a non-null NodeRef).
        // The absence-of-@node case is classified as UnclassifiedField in the builder.
    }
    private void validateNodeIdReferenceField(no.sikt.graphitron.rewrite.model.ChildField.NodeIdReferenceField field, List<ValidationError> errors) {
        // @node is always resolved — builder returns UnclassifiedField if the type is missing or lacks @node
        // Use targetType for table-level FK and path validation
        if (!(field.targetType() instanceof ReturnTypeRef.TableBoundReturnType tb)) {
            validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
            return;
        }
        no.sikt.graphitron.rewrite.model.TableRef targetTable = tb.table();

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

    private void validateReferenceLeadsToType(String fieldName, SourceLocation location, List<ReferencePathElementRef> path, String typeName, no.sikt.graphitron.rewrite.model.TableRef targetTable, List<ValidationError> errors) {
        var lastStep = path.getLast();
        String fkTableSql = null, keyTableSql = null;
        switch (lastStep) {
            case no.sikt.graphitron.rewrite.model.ReferencePathElementRef.FkRef s              -> { fkTableSql = s.fkTableSqlName(); keyTableSql = s.keyTableSqlName(); }
            case no.sikt.graphitron.rewrite.model.ReferencePathElementRef.FkWithConditionRef s -> { fkTableSql = s.fkTableSqlName(); keyTableSql = s.keyTableSqlName(); }
            case no.sikt.graphitron.rewrite.model.ReferencePathElementRef.ConditionOnlyRef ignored -> { return; } // no FK tables to check
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
    private void validateTableField(no.sikt.graphitron.rewrite.model.ChildField.TableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateSplitTableField(no.sikt.graphitron.rewrite.model.ChildField.SplitTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.LookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateSplitLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateTableMethodField(no.sikt.graphitron.rewrite.model.ChildField.TableMethodField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateTableInterfaceField(no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField field, List<ValidationError> errors) {
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
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);

        // Validate each SOURCES parameter's SourcesRef variant.
        var smr = field.serviceMethodRef();
        var parentTypeForSources = types.get(field.parentTypeName());
        List<String> parentPkJavaTypes = (parentTypeForSources instanceof TableType ttSrc)
            ? ttSrc.table().primaryKeyColumnJavaTypes()
            : List.of();

        smr.params().stream()
            .filter(p -> p instanceof no.sikt.graphitron.rewrite.model.ServiceMethodRef.ServiceParam.SourcesParam)
            .map(p -> (no.sikt.graphitron.rewrite.model.ServiceMethodRef.ServiceParam.SourcesParam) p)
            .forEach(sp -> { switch (sp.sourcesRef()) {
                case no.sikt.graphitron.rewrite.model.SourcesRef.RowKeyed rk -> {
                    if (!parentPkJavaTypes.isEmpty() && !rk.pkJavaTypes().equals(parentPkJavaTypes)) {
                        String expected = buildExpectedKeysType("Row", parentPkJavaTypes);
                        String found    = buildExpectedKeysType("Row", rk.pkJavaTypes());
                        errors.add(new ValidationError(
                            "Field '" + field.name() + "': SOURCES parameter '" + sp.name()
                                + "' must be of type " + expected + ", found: " + found,
                            field.location()));
                    }
                }
                case no.sikt.graphitron.rewrite.model.SourcesRef.RecordKeyed rk -> {
                    if (!parentPkJavaTypes.isEmpty() && !rk.pkJavaTypes().equals(parentPkJavaTypes)) {
                        String expected = buildExpectedKeysType("Record", parentPkJavaTypes);
                        String found    = buildExpectedKeysType("Record", rk.pkJavaTypes());
                        errors.add(new ValidationError(
                            "Field '" + field.name() + "': SOURCES parameter '" + sp.name()
                                + "' must be of type " + expected + ", found: " + found,
                            field.location()));
                    }
                }
                case no.sikt.graphitron.rewrite.model.SourcesRef.TableRecordKeyed trk -> {
                    // The whole parent record is the key — no PK-column type check needed.
                }
            } });

        // For Row-keyed and Record-keyed, the parent must have a single-column PK so the key
        // expression can be built. TableRecordKeyed uses the whole parent record as the key.
        boolean hasRowOrRecordKeyed = smr.params().stream()
            .filter(p -> p instanceof no.sikt.graphitron.rewrite.model.ServiceMethodRef.ServiceParam.SourcesParam)
            .map(p -> (no.sikt.graphitron.rewrite.model.ServiceMethodRef.ServiceParam.SourcesParam) p)
            .anyMatch(sp -> sp.sourcesRef() instanceof no.sikt.graphitron.rewrite.model.SourcesRef.RowKeyed
                         || sp.sourcesRef() instanceof no.sikt.graphitron.rewrite.model.SourcesRef.RecordKeyed);

        if (!hasRowOrRecordKeyed) {
            return; // TableRecordKeyed — no PK constraint on the parent table
        }

        var parentType = types.get(field.parentTypeName());
        if (!(parentType instanceof TableType tt)) {
            return; // non-table parent; no DataLoader key needed
        }
        TableRef parentTable = tt.table();
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
    private void validateServiceRecordField(no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateRecordTableField(no.sikt.graphitron.rewrite.model.ChildField.RecordTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateRecordLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.returnType().wrapper(), errors);
        validateArguments(field.name(), field.location(), field.arguments(), types, errors);
    }
    private void validateRecordField(no.sikt.graphitron.rewrite.model.ChildField.RecordField field, List<ValidationError> errors) {}

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

    private void validateComputedField(no.sikt.graphitron.rewrite.model.ChildField.ComputedField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
    }
    private void validatePropertyField(no.sikt.graphitron.rewrite.model.ChildField.PropertyField field, List<ValidationError> errors) {}
    private void validateMultitableReferenceField(no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField field, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Field '" + field.name() + "': @multitableReference is not supported in record-based output",
            field.location()
        ));
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

    private void validateArguments(String fieldName, SourceLocation location, List<ArgumentRef> arguments, Map<String, GraphitronType> types, List<ValidationError> errors) {
        // Type-existence of argument types is already guaranteed by graphql-java schema validation.
        // Graphitron-specific constraints will be added here as needed.
    }

    private void validateCardinality(String fieldName, SourceLocation location, no.sikt.graphitron.rewrite.model.FieldWrapper cardinality, List<ValidationError> errors) {
        switch (cardinality) {
            case no.sikt.graphitron.rewrite.model.FieldWrapper.Single ignored -> {}
            case no.sikt.graphitron.rewrite.model.FieldWrapper.List l -> {
                if (l.defaultOrder() != null) {
                    validateOrderSpec(fieldName, location, l.defaultOrder().spec(), errors);
                }
                for (var enumValue : l.orderByValues()) {
                    validateOrderSpec(fieldName, location, enumValue.spec(), errors);
                }
            }
            case no.sikt.graphitron.rewrite.model.FieldWrapper.Connection c -> {
                if (c.defaultOrder() != null) {
                    validateOrderSpec(fieldName, location, c.defaultOrder().spec(), errors);
                }
                for (var enumValue : c.orderByValues()) {
                    validateOrderSpec(fieldName, location, enumValue.spec(), errors);
                }
            }
        }
    }

    private void validateOrderSpec(String fieldName, SourceLocation location, no.sikt.graphitron.rewrite.model.OrderSpec spec, List<ValidationError> errors) {
        switch (spec) {
            case no.sikt.graphitron.rewrite.model.OrderSpec.IndexOrder ignored -> {}
            case no.sikt.graphitron.rewrite.model.OrderSpec.FieldsOrder ignored -> {}
            case no.sikt.graphitron.rewrite.model.OrderSpec.PrimaryKeyOrder ignored -> {}
        }
    }

    /**
     * No-op: all path elements are guaranteed resolved by the builder (unresolved paths produce
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} instead).
     */
    private void validateReferencePath(String fieldName, SourceLocation location, List<ReferencePathElementRef> path, List<ValidationError> errors) {
        // All elements are resolved — builder rejects unresolved paths at classification time.
    }
}
