package no.sikt.graphitron.record;

import graphql.language.SourceLocation;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.ReferencePathElement;
import no.sikt.graphitron.record.field.FieldConditionStep;
import no.sikt.graphitron.record.field.UnresolvedColumn;
import no.sikt.graphitron.record.field.UnresolvedConditionStep;
import no.sikt.graphitron.record.field.UnresolvedKeyAndConditionStep;
import no.sikt.graphitron.record.field.UnresolvedKeyStep;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.UnresolvedTable;

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
        schema.types().values().forEach(type -> validateType(type, schema, errors));
        schema.fields().forEach((coords, field) -> validateField(field, schema, errors));
        return List.copyOf(errors);
    }

    private void validateType(GraphitronType type, GraphitronSchema schema, List<ValidationError> errors) {
        switch (type) {
            case no.sikt.graphitron.record.type.TableType t          -> validateTableType(t, errors);
            case no.sikt.graphitron.record.type.ResultType t         -> validateResultType(t, errors);
            case no.sikt.graphitron.record.type.RootType t           -> validateRootType(t, errors);
            case no.sikt.graphitron.record.type.TableInterfaceType t -> validateTableInterfaceType(t, errors);
            case no.sikt.graphitron.record.type.InterfaceType t      -> validateInterfaceType(t, schema, errors);
            case no.sikt.graphitron.record.type.UnionType t          -> validateUnionType(t, schema, errors);
        }
    }

    private void validateField(GraphitronField field, GraphitronSchema schema, List<ValidationError> errors) {
        switch (field) {
            case no.sikt.graphitron.record.field.LookupQueryField f        -> validateLookupQueryField(f, errors);
            case no.sikt.graphitron.record.field.TableQueryField f         -> validateTableQueryField(f, errors);
            case no.sikt.graphitron.record.field.TableMethodQueryField f   -> validateTableMethodQueryField(f, errors);
            case no.sikt.graphitron.record.field.NodeQueryField f          -> validateNodeQueryField(f, errors);
            case no.sikt.graphitron.record.field.EntityQueryField f        -> validateEntityQueryField(f, errors);
            case no.sikt.graphitron.record.field.TableInterfaceQueryField f -> validateTableInterfaceQueryField(f, errors);
            case no.sikt.graphitron.record.field.InterfaceQueryField f     -> validateInterfaceQueryField(f, errors);
            case no.sikt.graphitron.record.field.UnionQueryField f         -> validateUnionQueryField(f, errors);
            case no.sikt.graphitron.record.field.ServiceQueryField f       -> validateServiceQueryField(f, errors);
            case no.sikt.graphitron.record.field.InsertMutationField f     -> validateInsertMutationField(f, errors);
            case no.sikt.graphitron.record.field.UpdateMutationField f     -> validateUpdateMutationField(f, errors);
            case no.sikt.graphitron.record.field.DeleteMutationField f     -> validateDeleteMutationField(f, errors);
            case no.sikt.graphitron.record.field.UpsertMutationField f     -> validateUpsertMutationField(f, errors);
            case no.sikt.graphitron.record.field.ServiceMutationField f    -> validateServiceMutationField(f, errors);
            case no.sikt.graphitron.record.field.ColumnField f             -> validateColumnField(f, errors);
            case no.sikt.graphitron.record.field.ColumnReferenceField f    -> validateColumnReferenceField(f, errors);
            case no.sikt.graphitron.record.field.NodeIdField f             -> validateNodeIdField(f, errors);
            case no.sikt.graphitron.record.field.NodeIdReferenceField f    -> validateNodeIdReferenceField(f, errors);
            case no.sikt.graphitron.record.field.TableField f              -> validateTableField(f, schema, errors);
            case no.sikt.graphitron.record.field.TableMethodField f        -> validateTableMethodField(f, errors);
            case no.sikt.graphitron.record.field.TableInterfaceField f     -> validateTableInterfaceField(f, errors);
            case no.sikt.graphitron.record.field.InterfaceField f          -> validateInterfaceField(f, errors);
            case no.sikt.graphitron.record.field.UnionField f              -> validateUnionField(f, errors);
            case no.sikt.graphitron.record.field.NestingField f            -> validateNestingField(f, schema, errors);
            case no.sikt.graphitron.record.field.ConstructorField f        -> validateConstructorField(f, errors);
            case no.sikt.graphitron.record.field.ServiceField f            -> validateServiceField(f, errors);
            case no.sikt.graphitron.record.field.ComputedField f           -> validateComputedField(f, errors);
            case no.sikt.graphitron.record.field.PropertyField f           -> validatePropertyField(f, errors);
            case no.sikt.graphitron.record.field.MultitableReferenceField f -> validateMultitableReferenceField(f, errors);
            case no.sikt.graphitron.record.field.NotGeneratedField f       -> validateNotGeneratedField(f, errors);
            case no.sikt.graphitron.record.field.UnclassifiedField f       -> validateUnclassifiedField(f, errors);
        }
    }

    // --- Type validators (stubs — filled in as test classes are added) ---

    private void validateTableType(no.sikt.graphitron.record.type.TableType type, List<ValidationError> errors) {
        if (type.table() instanceof UnresolvedTable) {
            errors.add(new ValidationError(
                "Type '" + type.name() + "': table '" + type.tableName() + "' could not be resolved in the jOOQ catalog",
                type.location()
            ));
        }
    }
    private void validateResultType(no.sikt.graphitron.record.type.ResultType type, List<ValidationError> errors) {}
    private void validateRootType(no.sikt.graphitron.record.type.RootType type, List<ValidationError> errors) {}
    private void validateTableInterfaceType(no.sikt.graphitron.record.type.TableInterfaceType type, List<ValidationError> errors) {
        if (type.table() instanceof UnresolvedTable) {
            errors.add(new ValidationError(
                "Type '" + type.name() + "': table '" + type.tableName() + "' could not be resolved in the jOOQ catalog",
                type.location()
            ));
        }
    }
    private void validateInterfaceType(no.sikt.graphitron.record.type.InterfaceType type, GraphitronSchema schema, List<ValidationError> errors) {}
    private void validateUnionType(no.sikt.graphitron.record.type.UnionType type, GraphitronSchema schema, List<ValidationError> errors) {}

    // --- Field validators (stubs — filled in as test classes are added) ---

    private void validateLookupQueryField(no.sikt.graphitron.record.field.LookupQueryField field, List<ValidationError> errors) {}
    private void validateTableQueryField(no.sikt.graphitron.record.field.TableQueryField field, List<ValidationError> errors) {}
    private void validateTableMethodQueryField(no.sikt.graphitron.record.field.TableMethodQueryField field, List<ValidationError> errors) {}
    private void validateNodeQueryField(no.sikt.graphitron.record.field.NodeQueryField field, List<ValidationError> errors) {}
    private void validateEntityQueryField(no.sikt.graphitron.record.field.EntityQueryField field, List<ValidationError> errors) {}
    private void validateTableInterfaceQueryField(no.sikt.graphitron.record.field.TableInterfaceQueryField field, List<ValidationError> errors) {}
    private void validateInterfaceQueryField(no.sikt.graphitron.record.field.InterfaceQueryField field, List<ValidationError> errors) {}
    private void validateUnionQueryField(no.sikt.graphitron.record.field.UnionQueryField field, List<ValidationError> errors) {}
    private void validateServiceQueryField(no.sikt.graphitron.record.field.ServiceQueryField field, List<ValidationError> errors) {}
    private void validateInsertMutationField(no.sikt.graphitron.record.field.InsertMutationField field, List<ValidationError> errors) {}
    private void validateUpdateMutationField(no.sikt.graphitron.record.field.UpdateMutationField field, List<ValidationError> errors) {}
    private void validateDeleteMutationField(no.sikt.graphitron.record.field.DeleteMutationField field, List<ValidationError> errors) {}
    private void validateUpsertMutationField(no.sikt.graphitron.record.field.UpsertMutationField field, List<ValidationError> errors) {}
    private void validateServiceMutationField(no.sikt.graphitron.record.field.ServiceMutationField field, List<ValidationError> errors) {}
    private void validateColumnField(no.sikt.graphitron.record.field.ColumnField field, List<ValidationError> errors) {
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
    private void validateColumnReferenceField(no.sikt.graphitron.record.field.ColumnReferenceField field, List<ValidationError> errors) {
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
    private void validateNodeIdField(no.sikt.graphitron.record.field.NodeIdField field, List<ValidationError> errors) {}
    private void validateNodeIdReferenceField(no.sikt.graphitron.record.field.NodeIdReferenceField field, List<ValidationError> errors) {
        if (field.referencePath().isEmpty()) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': @reference path is required",
                field.location()
            ));
        } else {
            validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        }
    }
    private void validateTableField(no.sikt.graphitron.record.field.TableField field, GraphitronSchema schema, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
        if (field.condition() instanceof FieldConditionStep.UnresolvedFieldCondition u) {
            errors.add(new ValidationError(
                "Field '" + field.name() + "': condition method '" + u.qualifiedName() + "' could not be resolved",
                field.location()
            ));
        }
    }
    private void validateTableMethodField(no.sikt.graphitron.record.field.TableMethodField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
    }
    private void validateTableInterfaceField(no.sikt.graphitron.record.field.TableInterfaceField field, List<ValidationError> errors) {}
    private void validateInterfaceField(no.sikt.graphitron.record.field.InterfaceField field, List<ValidationError> errors) {}
    private void validateUnionField(no.sikt.graphitron.record.field.UnionField field, List<ValidationError> errors) {}
    private void validateNestingField(no.sikt.graphitron.record.field.NestingField field, GraphitronSchema schema, List<ValidationError> errors) {}
    private void validateConstructorField(no.sikt.graphitron.record.field.ConstructorField field, List<ValidationError> errors) {}
    private void validateServiceField(no.sikt.graphitron.record.field.ServiceField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
    }
    private void validateComputedField(no.sikt.graphitron.record.field.ComputedField field, List<ValidationError> errors) {
        validateReferencePath(field.name(), field.location(), field.referencePath(), errors);
    }
    private void validatePropertyField(no.sikt.graphitron.record.field.PropertyField field, List<ValidationError> errors) {}
    private void validateMultitableReferenceField(no.sikt.graphitron.record.field.MultitableReferenceField field, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Field '" + field.name() + "': @multitableReference is not supported in record-based output",
            field.location()
        ));
    }
    private void validateNotGeneratedField(no.sikt.graphitron.record.field.NotGeneratedField field, List<ValidationError> errors) {}
    private void validateUnclassifiedField(no.sikt.graphitron.record.field.UnclassifiedField field, List<ValidationError> errors) {
        errors.add(new ValidationError(
            "Field '" + field.name() + "': could not be classified — missing or conflicting directives",
            field.location()
        ));
    }

    private void validateReferencePath(String fieldName, SourceLocation location, List<ReferencePathElement> path, List<ValidationError> errors) {
        for (var element : path) {
            switch (element) {
                case no.sikt.graphitron.record.field.FkStep ignored -> {}
                case no.sikt.graphitron.record.field.FkWithConditionStep ignored -> {}
                case no.sikt.graphitron.record.field.ConditionOnlyStep ignored -> {}
                case UnresolvedKeyStep u -> errors.add(new ValidationError(
                    "Field '" + fieldName + "': key '" + u.keyName() + "' could not be resolved in the jOOQ catalog",
                    location));
                case UnresolvedConditionStep u -> errors.add(new ValidationError(
                    "Field '" + fieldName + "': condition method '" + u.qualifiedName() + "' could not be resolved",
                    location));
                case UnresolvedKeyAndConditionStep u -> {
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
