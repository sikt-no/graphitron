package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.helpers.ProcedureCall;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.mappings.RoutineReflection;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.Parameter;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.mappings.TableReflection.getJavaFieldName;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphitron.validation.messages.ProcedureCallError.*;
import static no.sikt.graphql.directives.GenerationDirective.*;

/**
 * Validates usage of the {@code @experimental_procedureCall} directive.
 * <p>
 * The directive has two modes, distinguished by the presence of the {@code target} parameter.
 * If it is set, the input is GraphQL arguments, else it is jOOQ columns.
 */
class ProcedureCallValidator extends AbstractSchemaValidator {
    private final static Map<GenerationDirective, Function<GenerationField, Boolean>> ILLEGAL_COMBINATIONS = new LinkedHashMap<>(
            Map.of(
                    FIELD, GenerationField::hasFieldDirective,
                    EXTERNAL_FIELD, GenerationField::isExternalField,
                    REFERENCE, (it) -> !it.getFieldReferences().isEmpty()
            )
    );

    ProcedureCallValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        schema
                .getObjects()
                .values()
                .forEach(
                        it -> it
                                .getFields()
                                .stream()
                                .filter(ObjectField::hasProcedureCall)
                                .forEach(field -> validateProcedureCallField(field, it))
                );

        schema
                .getInterfaces()
                .values()
                .forEach(
                        it -> it
                                .getFields()
                                .stream()
                                .filter(ObjectField::hasProcedureCall)
                                .forEach(field -> addErrorMessage(ON_INTERFACE_DECLARATION, field.formatPath(), it.getName()))
                );
    }

    private void validateProcedureCallField(ObjectField field, ObjectDefinition object) {
        var fieldPath = field.formatPath();
        ILLEGAL_COMBINATIONS
                .entrySet()
                .stream()
                .filter(it -> it.getValue().apply(field))
                .forEach(it -> directiveCombinationError(fieldPath, it.getKey()));

        var call = field.getProcedureCall();
        if (call.hasTarget()) {
            validateTarget(field, call);
        } else {
            validateInline(field, object, call);
        }
    }

    private void validateInline(ObjectField field, ObjectDefinition object, ProcedureCall call) {
        var fieldPath = field.formatPath();
        if (object.isOperationRoot()) {
            addErrorMessage(ON_ROOT_OPERATION, fieldPath, object.getName());
        }

        if (requireTable(field, object)) {
            checkInputs(field, call, schema.getPreviousTableObjectForObject(object).getTable().getMappingName());
        }

        if (!schema.isScalar(field.getTypeName())) {
            addErrorMessage(ON_NON_SCALAR_FIELD_TYPE, fieldPath, field.getTypeName());
        }

        validateRoutine(fieldPath, call, field.getTypeClass(), field.getTypeName());
    }

    private void validateTarget(ObjectField field, ProcedureCall call) {
        var targetName = call.targetField();

        if (!field.createsDataFetcher() && !field.isRootField()) {
            addErrorMessage(TARGET_MODE_REQUIRES_DATA_FETCHER, field.formatPath(), targetName);
            return;
        }

        if (!schema.isObject(field)) {
            addErrorMessage(TARGET_MODE_REQUIRES_OBJECT_RETURN_TYPE, field.formatPath(), targetName, field.getTypeName());
            return;
        }

        var returnTypeObject = schema.getObject(field);
        requireTable(field, returnTypeObject);

        checkInputs(field, call, null);

        var fieldPath = field.formatPath();
        var targetField = returnTypeObject.getFieldByName(targetName);
        var returnName = returnTypeObject.getName();
        if (targetField == null) {
            addErrorMessage(UNKNOWN_TARGET_FIELD, fieldPath, targetName, returnName);
            return;
        }

        ILLEGAL_COMBINATIONS
                .entrySet()
                .stream()
                .filter(it -> it.getValue().apply(targetField))
                .forEach(it -> targetDirectiveCombinationError(fieldPath, targetName, returnName, it.getKey()));
        if (targetField.hasProcedureCall()) {
            targetDirectiveCombinationError(fieldPath, targetName, returnName, PROCEDURE_CALL);
        }

        if (!schema.isScalar(targetField.getTypeName())) {
            addErrorMessage(TARGET_FIELD_NOT_SCALAR, fieldPath, targetName, returnName, targetField.getTypeName());
        }

        validateRoutine(fieldPath, call, targetField.getTypeClass(), targetField.getTypeName());
    }

    private static void checkInputs(ObjectField field, ProcedureCall call, String tableJavaName) {
        var isTarget = call.hasTarget();
        var message = isTarget
                ? "no such GraphQL argument on field '" + field.getName() + "'"
                : "no such column on table '" + tableJavaName + "'";
        var argNames = !isTarget ? Set.of() : field
                .getArguments()
                .stream()
                .map(AbstractField::getName)
                .collect(Collectors.toSet());
        call
                .argumentMap()
                .entrySet()
                .stream()
                .filter(entry -> isTarget ? !argNames.contains(entry.getValue()) : getJavaFieldName(tableJavaName, entry.getValue()).isEmpty())
                .forEach(entry -> addErrorMessage(
                        ARGUMENT_NOT_FOUND,
                        field.formatPath(),
                        entry.getKey(),
                        entry.getValue(),
                        message
                ));
    }

    private void validateRoutine(String fieldPath, ProcedureCall call, TypeName expectedFieldType, String expectedFieldTypeName) {
        var procedureName = call.procedureName();

        var matches = RoutineReflection.resolveRoutine(procedureName);
        if (matches.size() > 1) {
            var candidateSchemas = matches.stream().map(RoutineReflection::schemaFromProcedure).sorted().collect(Collectors.joining(", "));
            addErrorMessage(AMBIGUOUS_ROUTINE, fieldPath, procedureName, candidateSchemas);
            return;
        }
        if (matches.isEmpty()) {
            addErrorMessage(UNKNOWN_ROUTINE, fieldPath, procedureName);
            return;
        }
        var resolvedRoutineName = matches.get(0);

        var isFunction = RoutineReflection.isFunction(resolvedRoutineName);
        var outParams = RoutineReflection.getOutParameters(resolvedRoutineName);
        if (!isFunction || !outParams.isEmpty()) {
            addErrorMessage(NOT_A_FUNCTION, fieldPath, procedureName);
            return;
        }

        var paramNames = RoutineReflection
                .getInParameters(resolvedRoutineName)
                .stream()
                .map(Parameter::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        var argumentMap = call.argumentMap();
        var invalidArguments = argumentMap
                .keySet()
                .stream()
                .filter(it -> !paramNames.contains(it))
                .toList();
        if (!invalidArguments.isEmpty()) {
            var joinedParams = String.join(", ", paramNames);
            invalidArguments.forEach(it -> addErrorMessage(UNKNOWN_PARAMETER, fieldPath, it, procedureName, joinedParams));
        }
        paramNames
                .stream()
                .filter(it -> !argumentMap.containsKey(it))
                .forEach(it -> addErrorMessage(MISSING_PARAMETER, fieldPath, it, procedureName));

        var returnTypeOptional = RoutineReflection.getReturnType(resolvedRoutineName);
        if (returnTypeOptional.isEmpty()) {
            return;
        }

        var returnType = returnTypeOptional.get();
        if (!isCompatibleReturnType(expectedFieldType, returnType)) {
            addErrorMessage(
                    RETURN_TYPE_MISMATCH,
                    fieldPath,
                    procedureName,
                    returnType.getName(),
                    expectedFieldTypeName,
                    expectedFieldType.toString()
            );
        }
    }

    private static void targetDirectiveCombinationError(String fieldPath, String targetName, String returnName, GenerationDirective illegal) {
        addErrorMessage(TARGET_FIELD_HAS_ILLEGAL_DIRECTIVE, fieldPath, targetName, returnName, illegal.getName());
    }

    private static void directiveCombinationError(String fieldPath, GenerationDirective illegal) {
        addErrorMessage(ILLEGAL_COMBINATION, fieldPath, illegal.getName());
    }

    private boolean requireTable(ObjectField field, ObjectDefinition object) {
        if (schema.hasTableObjectForObject(object)) {
            return true;
        }

        addErrorMessage(MISSING_TABLE, field.formatPath());
        return false;
    }

    /**
     * The jOOQ-returned Java type must match the field's javapoet {@link TypeName} by canonical name.
     */
    private static boolean isCompatibleReturnType(TypeName fieldType, Class<?> jooqReturnType) {
        if (fieldType == null || jooqReturnType == null) {
            return true; // be permissive if we somehow cannot determine
        }
        return fieldType.equals(ClassName.get(jooqReturnType));
    }
}
