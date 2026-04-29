package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.mappings.RoutineReflection;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.Parameter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.mappings.TableReflection.getJavaFieldName;
import static no.sikt.graphitron.mappings.TableReflection.tableExists;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphitron.validation.messages.ProcedureCallError.*;
import static no.sikt.graphql.directives.GenerationDirective.*;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_QUERY;

/**
 * Validates usage of the {@code @experimental_procedureCall} directive.
 */
class ProcedureCallValidator extends AbstractSchemaValidator {

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
        checkContext(field, object);
        rejectIllegalDirectiveCombinations(field);

        var call = field.getProcedureCall();
        var procedureName = call.procedureName();
        var argumentMap = call.argumentMap();

        // Every mapped column must exist on the surrounding table. Requires a surrounding table to even attempt.
        if (schema.hasTableObjectForObject(object)) {
            var tableJavaName = schema.getPreviousTableObjectForObject(object).getTable().getMappingName();
            if (tableExists(tableJavaName)) {
                argumentMap
                        .entrySet()
                        .stream()
                        .filter(entry -> getJavaFieldName(tableJavaName, entry.getValue()).isEmpty())
                        .forEach(entry -> addErrorMessage(NONEXISTENT_COLUMN, field.formatPath(), entry.getKey(), entry.getValue(), tableJavaName));
            }
        }

        // Routine exists and is unique.
        var matches = RoutineReflection.resolveRoutine(procedureName);
        if (matches.size() > 1) {
            var candidateSchemas = matches.stream().map(RoutineReflection::schemaFromProcedure).sorted().collect(Collectors.joining(", "));
            addErrorMessage(AMBIGUOUS_ROUTINE, field.formatPath(), procedureName, candidateSchemas);
            return;
        }
        if (matches.isEmpty()) {
            addErrorMessage(UNKNOWN_ROUTINE, field.formatPath(), procedureName);
            return;
        }
        var resolvedRoutineName = matches.get(0);

        // Must be a function (has return value) and have no OUT params.
        var isFunction = RoutineReflection.isFunction(resolvedRoutineName);
        var outParams = RoutineReflection.getOutParameters(resolvedRoutineName);
        if (!isFunction || !outParams.isEmpty()) {
            addErrorMessage(NOT_A_FUNCTION, field.formatPath(), procedureName);
            return;
        }

        // Argument map symmetry against IN parameters.
        var paramNames = RoutineReflection
                .getInParameters(resolvedRoutineName)
                .stream()
                .map(Parameter::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        var invalidArguments = argumentMap
                .keySet()
                .stream()
                .filter(it -> !paramNames.contains(it))
                .toList();
        if (!invalidArguments.isEmpty()) {
            var joinedParams = String.join(", ", paramNames);
            invalidArguments.forEach(it -> addErrorMessage(UNKNOWN_PARAMETER, field.formatPath(), it, procedureName, joinedParams));
        }
        paramNames
                .stream()
                .filter(it -> !argumentMap.containsKey(it))
                .forEach(it -> addErrorMessage(MISSING_PARAMETER, field.formatPath(), it, procedureName));

        // Return-type compatibility with the GraphQL field type.
        var returnTypeOptional = RoutineReflection.getReturnType(resolvedRoutineName);
        if (returnTypeOptional.isEmpty()) {
            return;
        }

        var returnType = returnTypeOptional.get();
        var fieldType = field.getTypeClass();
        if (!isCompatibleReturnType(fieldType, returnType)) {
            addErrorMessage(
                    RETURN_TYPE_MISMATCH,
                    field.formatPath(),
                    procedureName,
                    returnType.getName(),
                    field.getTypeName(),
                    fieldType.toString()
            );
        }
    }

    private void checkContext(ObjectField field, ObjectDefinition object) {
        if (object.getName().equals(SCHEMA_QUERY.getName())) {
            addErrorMessage(ON_ROOT_OPERATION, field.formatPath(), SCHEMA_QUERY.getName());
        }
        if (object.getName().equals(SCHEMA_MUTATION.getName())) {
            addErrorMessage(ON_ROOT_OPERATION, field.formatPath(), SCHEMA_MUTATION.getName());
        }
        if (!schema.isScalar(field.getTypeName())) {
            addErrorMessage(ON_NON_SCALAR_FIELD_TYPE, field.formatPath(), field.getTypeName());
        }
        if (!schema.hasTableObjectForObject(object)) {
            addErrorMessage(MISSING_TABLE, field.formatPath());
        }
    }

    private void rejectIllegalDirectiveCombinations(ObjectField field) {
        if (field.hasFieldDirective()) {
            addErrorMessage(ILLEGAL_COMBINATION, field.formatPath(), FIELD.getName());
        }
        if (field.isExternalField()) {
            addErrorMessage(ILLEGAL_COMBINATION, field.formatPath(), EXTERNAL_FIELD.getName());
        }
        if (!field.getFieldReferences().isEmpty()) {
            addErrorMessage(ILLEGAL_COMBINATION, field.formatPath(), REFERENCE.getName());
        }
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
