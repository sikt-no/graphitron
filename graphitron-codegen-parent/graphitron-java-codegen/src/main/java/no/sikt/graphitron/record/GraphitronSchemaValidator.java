package no.sikt.graphitron.record;

import graphql.language.SourceLocation;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphitron.record.field.FieldConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkWithConditionRef;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.ReferencePathElementRef;
import no.sikt.graphitron.record.field.ColumnRef.UnresolvedColumn;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyAndConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyRef;
import no.sikt.graphitron.record.field.NodeTypeRef.UnresolvedNodeType;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.NodeRef.NodeDirective;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;
import no.sikt.graphitron.record.type.GraphitronType.TableType;
import no.sikt.graphitron.record.type.TableRef.UnresolvedTable;
import org.jooq.ForeignKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates a {@link GraphitronSchema}, collecting all errors rather than failing on the first.
 *
 * <p>Each validation method receives the classified field or type and appends to the shared
 * error list. The Maven plugin calls this after {@code FieldsSpecBuilder.build()} and formats
 * the resulting {@link ValidationError} list as compiler-style messages with file and line
 * references.
 */
public class GraphitronSchemaValidator {

    public List<ValidationError> validate(GraphitronSchema schema) {
        var errors = new ArrayList<ValidationError>();
        schema.types().values().forEach(type -> validateType(type, errors));
        schema.fields().values().forEach(field -> validateField(field, schema, errors));
        return List.copyOf(errors);
    }

    private void validateType(GraphitronType type, List<ValidationError> errors) {
        switch (type) {
            case no.sikt.graphitron.record.type.GraphitronType.TableType t          -> validateTableType(t, errors);
            case no.sikt.graphitron.record.type.GraphitronType.ResultType t         -> validateResultType(t, errors);
            case no.sikt.graphitron.record.type.GraphitronType.RootType t           -> validateRootType(t, errors);
            case no.sikt.graphitron.record.type.GraphitronType.TableInterfaceType t -> validateTableInterfaceType(t, errors);
            case no.sikt.graphitron.record.type.GraphitronType.InterfaceType t      -> validateInterfaceType(t, errors);
            case no.sikt.graphitron.record.type.GraphitronType.UnionType t          -> validateUnionType(t, errors);
        }
    }

    private void validateField(GraphitronField field, GraphitronSchema schema, List<ValidationError> errors) {
        switch (field) {
            case no.sikt.graphitron.record.field.QueryField.LookupQueryField f        -> validateLookupQueryField(f, errors);
            case no.sikt.graphitron.record.field.QueryField.TableQueryField f         -> validateTableQueryField(f, errors);
            case no.sikt.graphitron.record.field.QueryField.TableMethodQueryField f   -> validateTableMethodQueryField(f, errors);
            case no.sikt.graphitron.record.field.QueryField.NodeQueryField f          -> validateNodeQueryField(f, errors);
            case no.sikt.graphitron.record.field.QueryField.EntityQueryField f        -> validateEntityQueryField(f, errors);
            case no.sikt.graphitron.record.field.QueryField.TableInterfaceQueryField f -> validateTableInterfaceQueryField(f, errors);
            case no.sikt.graphitron.record.field.QueryField.InterfaceQueryField f     -> validateInterfaceQueryField(f, errors);
            case no.sikt.graphitron.record.field.QueryField.UnionQueryField f         -> validateUnionQueryField(f, errors);
            case no.sikt.graphitron.record.field.QueryField.ServiceQueryField f       -> validateServiceQueryField(f, errors);
            case no.sikt.graphitron.record.field.MutationField.InsertMutationField f     -> validateInsertMutationField(f, errors);
            case no.sikt.graphitron.record.field.MutationField.UpdateMutationField f     -> validateUpdateMutationField(f, errors);
            case no.sikt.graphitron.record.field.MutationField.DeleteMutationField f     -> validateDeleteMutationField(f, errors);
            case no.sikt.graphitron.record.field.MutationField.UpsertMutationField f     -> validateUpsertMutationField(f, errors);
            case no.sikt.graphitron.record.field.MutationField.ServiceMutationField f    -> validateServiceMutationField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.ColumnField f             -> validateColumnField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.ColumnReferenceField f    -> validateColumnReferenceField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.NodeIdField f             -> validateNodeIdField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.NodeIdReferenceField f    -> validateNodeIdReferenceField(f, schema, errors);
            case no.sikt.graphitron.record.field.ChildField.TableField f              -> validateTableField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.TableMethodField f        -> validateTableMethodField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.TableInterfaceField f     -> validateTableInterfaceField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.InterfaceField f          -> validateInterfaceField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.UnionField f              -> validateUnionField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.NestingField f            -> validateNestingField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.ConstructorField f        -> validateConstructorField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.ServiceField f            -> validateServiceField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.ComputedField f           -> validateComputedField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.PropertyField f           -> validatePropertyField(f, errors);
            case no.sikt.graphitron.record.field.ChildField.MultitableReferenceField f -> validateMultitableReferenceField(f, errors);
            case no.sikt.graphitron.record.field.GraphitronField.NotGeneratedField f       -> validateNotGeneratedField(f, errors);
            case no.sikt.graphitron.record.field.GraphitronField.UnclassifiedField f       -> validateUnclassifiedField(f, errors);
        }
    }

    // --- Type validators (stubs — filled in as test classes are added) ---

    private void validateTableType(no.sikt.graphitron.record.type.GraphitronType.TableType type, List<ValidationError> errors) {
        if (type.table() instanceof UnresolvedTable) {
            errors.add(new ValidationError(
                "Type '" + type.name() + "': table '" + type.tableName() + "' could not be resolved in the jOOQ catalog",
                type.location()
            ));
        }
        if (type.node() instanceof no.sikt.graphitron.record.type.NodeRef.NodeDirective nd) {
            for (var keyColumn : nd.keyColumns()) {
                if (keyColumn instanceof no.sikt.graphitron.record.type.KeyColumnRef.UnresolvedKeyColumn u) {
                    errors.add(new ValidationError(
                        "Type '" + type.name() + "': key column '" + u.name() + "' in @node could not be resolved in the jOOQ table",
                        type.location()
                    ));
                }
            }
        }
    }
    private void validateResultType(no.sikt.graphitron.record.type.GraphitronType.ResultType type, List<ValidationError> errors) {}
    private void validateRootType(no.sikt.graphitron.record.type.GraphitronType.RootType type, List<ValidationError> errors) {}
    private void validateTableInterfaceType(no.sikt.graphitron.record.type.GraphitronType.TableInterfaceType type, List<ValidationError> errors) {
        if (type.table() instanceof UnresolvedTable) {
            errors.add(new ValidationError(
                "Type '" + type.name() + "': table '" + type.tableName() + "' could not be resolved in the jOOQ catalog",
                type.location()
            ));
        }
    }
    private void validateInterfaceType(no.sikt.graphitron.record.type.GraphitronType.InterfaceType type, List<ValidationError> errors) {}
    private void validateUnionType(no.sikt.graphitron.record.type.GraphitronType.UnionType type, List<ValidationError> errors) {}

    // --- Field validators (stubs — filled in as test classes are added) ---

    private void validateLookupQueryField(no.sikt.graphitron.record.field.QueryField.LookupQueryField field, List<ValidationError> errors) {}
    private void validateTableQueryField(no.sikt.graphitron.record.field.QueryField.TableQueryField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.cardinality(), errors);
    }
    private void validateTableMethodQueryField(no.sikt.graphitron.record.field.QueryField.TableMethodQueryField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.cardinality(), errors);
    }
    private void validateNodeQueryField(no.sikt.graphitron.record.field.QueryField.NodeQueryField field, List<ValidationError> errors) {}
    private void validateEntityQueryField(no.sikt.graphitron.record.field.QueryField.EntityQueryField field, List<ValidationError> errors) {}
    private void validateTableInterfaceQueryField(no.sikt.graphitron.record.field.QueryField.TableInterfaceQueryField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.cardinality(), errors);
    }
    private void validateInterfaceQueryField(no.sikt.graphitron.record.field.QueryField.InterfaceQueryField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.cardinality(), errors);
    }
    private void validateUnionQueryField(no.sikt.graphitron.record.field.QueryField.UnionQueryField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.cardinality(), errors);
    }
    private void validateServiceQueryField(no.sikt.graphitron.record.field.QueryField.ServiceQueryField field, List<ValidationError> errors) {}
    private void validateInsertMutationField(no.sikt.graphitron.record.field.MutationField.InsertMutationField field, List<ValidationError> errors) {}
    private void validateUpdateMutationField(no.sikt.graphitron.record.field.MutationField.UpdateMutationField field, List<ValidationError> errors) {}
    private void validateDeleteMutationField(no.sikt.graphitron.record.field.MutationField.DeleteMutationField field, List<ValidationError> errors) {}
    private void validateUpsertMutationField(no.sikt.graphitron.record.field.MutationField.UpsertMutationField field, List<ValidationError> errors) {}
    private void validateServiceMutationField(no.sikt.graphitron.record.field.MutationField.ServiceMutationField field, List<ValidationError> errors) {}
    private void validateColumnField(no.sikt.graphitron.record.field.ChildField.ColumnField field, List<ValidationError> errors) {
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
    private void validateColumnReferenceField(no.sikt.graphitron.record.field.ChildField.ColumnReferenceField field, List<ValidationError> errors) {
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
    private void validateNodeIdField(no.sikt.graphitron.record.field.ChildField.NodeIdField field, List<ValidationError> errors) {
        if (field.node() instanceof no.sikt.graphitron.record.type.NodeRef.NoNode) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @nodeId requires the containing type to have @node",
                field.location()
            ));
        }
    }
    private void validateNodeIdReferenceField(no.sikt.graphitron.record.field.ChildField.NodeIdReferenceField field, GraphitronSchema schema, List<ValidationError> errors) {
        if (field.nodeType() instanceof UnresolvedNodeType) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': type '" + field.typeName() + "' does not exist in the schema or does not have @node",
                field.location()
            ));
            validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
            return;
        }

        // nodeType is ResolvedNodeType — look up the target type to get its jOOQ table
        var targetResolvedTable = resolvedTableFor(schema.type(field.typeName()));
        if (targetResolvedTable == null) {
            // Target type is not yet table-resolved; other validators will report that error
            validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
            return;
        }

        if (field.referencePath().isEmpty()) {
            // Implicit join: exactly one FK must exist between parent and target tables
            var parentResolvedTable = resolvedTableFor(schema.type(field.parentTypeName()));
            if (parentResolvedTable != null) {
                int fkCount = TableReflection.getNumberOfForeignKeysBetweenTables(
                    parentResolvedTable.javaFieldName(), targetResolvedTable.javaFieldName());
                if (fkCount == 0) {
                    errors.add(new ValidationError(
                        "Field '" + field.name() + "': no foreign key found between tables '"
                            + parentResolvedTable.table().getName() + "' and '"
                            + targetResolvedTable.table().getName()
                            + "'; add a @reference directive to specify the join path",
                        field.location()
                    ));
                } else if (fkCount > 1) {
                    errors.add(new ValidationError(
                        "Field '" + field.name() + "': multiple foreign keys found between tables '"
                            + parentResolvedTable.table().getName() + "' and '"
                            + targetResolvedTable.table().getName()
                            + "'; add a @reference directive to specify the join path",
                        field.location()
                    ));
                }
            }
        } else {
            // Explicit reference path: validate steps and check it leads to the TypeName's table
            validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
            validateReferenceLeadsToType(field.name(), field.location(), field.referencePath(), field.typeName(), targetResolvedTable, errors);
        }
    }

    private static ResolvedTable resolvedTableFor(GraphitronType type) {
        if (type instanceof TableType t && t.table() instanceof ResolvedTable rt) {
            return rt;
        }
        return null;
    }

    private void validateReferenceLeadsToType(String fieldName, SourceLocation location, List<ReferencePathElementRef> path, String typeName, ResolvedTable targetTable, List<ValidationError> errors) {
        var lastStep = path.getLast();
        ForeignKey<?, ?> fk = switch (lastStep) {
            case FkRef s             -> s.key();
            case FkWithConditionRef s -> s.key();
            default                   -> null;
        };
        if (fk == null) {
            return; // Can't check for condition-only or unresolved steps
        }
        var targetSqlName = targetTable.table().getName();
        if (!fk.getTable().getName().equalsIgnoreCase(targetSqlName) &&
            !fk.getKey().getTable().getName().equalsIgnoreCase(targetSqlName)) {
            errors.add(new ValidationError(
                "Field '" + fieldName + "': @reference path does not lead to the table of type '" + typeName + "'",
                location
            ));
        }
    }
    private void validateTableField(no.sikt.graphitron.record.field.ChildField.TableField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.condition() instanceof FieldConditionRef.UnresolvedFieldCondition u) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': condition method '" + u.qualifiedName() + "' could not be resolved",
                field.location()
            ));
        }
        validateCardinality(field.name(), field.location(), field.cardinality(), errors);
    }
    private void validateTableMethodField(no.sikt.graphitron.record.field.ChildField.TableMethodField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        validateCardinality(field.name(), field.location(), field.cardinality(), errors);
    }
    private void validateTableInterfaceField(no.sikt.graphitron.record.field.ChildField.TableInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.cardinality(), errors);
    }
    private void validateInterfaceField(no.sikt.graphitron.record.field.ChildField.InterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.cardinality(), errors);
    }
    private void validateUnionField(no.sikt.graphitron.record.field.ChildField.UnionField field, List<ValidationError> errors) {
        validateCardinality(field.name(), field.location(), field.cardinality(), errors);
    }
    private void validateNestingField(no.sikt.graphitron.record.field.ChildField.NestingField field, List<ValidationError> errors) {}
    private void validateConstructorField(no.sikt.graphitron.record.field.ChildField.ConstructorField field, List<ValidationError> errors) {}
    private void validateServiceField(no.sikt.graphitron.record.field.ChildField.ServiceField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
    }
    private void validateComputedField(no.sikt.graphitron.record.field.ChildField.ComputedField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
    }
    private void validatePropertyField(no.sikt.graphitron.record.field.ChildField.PropertyField field, List<ValidationError> errors) {}
    private void validateMultitableReferenceField(no.sikt.graphitron.record.field.ChildField.MultitableReferenceField field, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Field '" + field.name() + "': @multitableReference is not supported in record-based output",
            field.location()
        ));
    }
    private void validateNotGeneratedField(no.sikt.graphitron.record.field.GraphitronField.NotGeneratedField field, List<ValidationError> errors) {}
    private void validateUnclassifiedField(no.sikt.graphitron.record.field.GraphitronField.UnclassifiedField field, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Field '" + field.name() + "': could not be classified — missing or conflicting directives",
            field.location()
        ));
    }

    private void validateCardinality(String fieldName, SourceLocation location, no.sikt.graphitron.record.field.FieldCardinality cardinality, List<ValidationError> errors) {
        switch (cardinality) {
            case no.sikt.graphitron.record.field.FieldCardinality.Single ignored -> {}
            case no.sikt.graphitron.record.field.FieldCardinality.List l -> {
                if (l.defaultOrder() != null) {
                    validateOrderSpec(fieldName, location, l.defaultOrder().spec(), errors);
                }
                for (var enumValue : l.orderByValues()) {
                    validateOrderSpec(fieldName, location, enumValue.spec(), errors);
                }
            }
            case no.sikt.graphitron.record.field.FieldCardinality.Connection c -> {
                if (c.defaultOrder() != null) {
                    validateOrderSpec(fieldName, location, c.defaultOrder().spec(), errors);
                }
                for (var enumValue : c.orderByValues()) {
                    validateOrderSpec(fieldName, location, enumValue.spec(), errors);
                }
            }
        }
    }

    private void validateOrderSpec(String fieldName, SourceLocation location, no.sikt.graphitron.record.field.OrderSpec spec, List<ValidationError> errors) {
        switch (spec) {
            case no.sikt.graphitron.record.field.OrderSpec.IndexOrder ignored -> {}
            case no.sikt.graphitron.record.field.OrderSpec.FieldsOrder ignored -> {}
            case no.sikt.graphitron.record.field.OrderSpec.PrimaryKeyOrder ignored -> {}
            case no.sikt.graphitron.record.field.OrderSpec.UnresolvedIndexOrder u -> errors.add(new ValidationError(
                "Field '" + fieldName + "': index '" + u.indexName() + "' could not be resolved in the jOOQ catalog",
                location));
            case no.sikt.graphitron.record.field.OrderSpec.UnresolvedPrimaryKeyOrder ignored -> errors.add(new ValidationError(
                "Field '" + fieldName + "': primary key could not be resolved — the table may not have one",
                location));
        }
    }

    private void validateReferencePath(String fieldName, SourceLocation location, List<ReferencePathElementRef> path, List<ValidationError> errors) {
        for (var element : path) {
            switch (element) {
                case no.sikt.graphitron.record.field.ReferencePathElementRef.FkRef ignored -> {}
                case no.sikt.graphitron.record.field.ReferencePathElementRef.FkWithConditionRef ignored -> {}
                case no.sikt.graphitron.record.field.ReferencePathElementRef.ConditionOnlyRef ignored -> {}
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
