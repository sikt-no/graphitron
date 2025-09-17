package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ExceptionDefinition;
import no.sikt.graphitron.generators.codebuilding.NameFormat;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.GeneratorConfig.getRecordValidation;
import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedRecordNameIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.PAGE_SIZE_NAME;
import static no.sikt.graphql.naming.GraphQLReservedName.ERROR_TYPE;
import static no.sikt.graphql.naming.GraphQLReservedName.PAGINATION_AFTER;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * A helper class for handling input type data for services and mutations.
 */
public class InputParser {
    private final Map<String, InputField> methodInputsWithOrderField, methodInputs, recordInputs, jOOQInputs;
    private final String serviceInputString;
    private final List<ObjectField> allErrors;
    private final ExceptionDefinition validationErrorException;

    public InputParser(ObjectField target, ProcessedSchema schema) {
        methodInputs = parseInputs(target.getNonReservedArguments(), schema);
        methodInputsWithOrderField = parseInputs(target.getNonReservedArgumentsWithOrderField(), schema);
        recordInputs = methodInputsWithOrderField
                .entrySet()
                .stream()
                .filter(it -> schema.hasRecord(it.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new));
        jOOQInputs = recordInputs
                .entrySet()
                .stream()
                .filter(it -> schema.hasJOOQRecord(it.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new));
        var inputsJoined = String.join(", ", methodInputsWithOrderField.keySet());
        var contextParams = String.join(", ", schema.getAllContextFields(target).keySet().stream().map(NameFormat::asContextFieldName).toList());
        if (target.hasForwardPagination()) {
            var contextParamsOrEmpty = contextParams.isEmpty() ? "" : ", " + contextParams;
            serviceInputString = (!inputsJoined.isEmpty() ? inputsJoined + ", " : inputsJoined) + PAGE_SIZE_NAME + ", " + PAGINATION_AFTER.getName() + contextParamsOrEmpty;
        } else {
            serviceInputString = inputsJoined.isEmpty() ? contextParams : (contextParams.isEmpty() ? inputsJoined : inputsJoined + ", " + contextParams);
        }

        if (schema.isObject(target) && schema.isInterface(ERROR_TYPE.getName())) {
            allErrors = schema.getAllErrors(target.getTypeName());
        } else {
            allErrors = List.of();
        }

        validationErrorException = !recordValidationEnabled() ? null : getRecordValidation().getSchemaErrorType().flatMap(errorTypeName ->
                allErrors.stream()
                        .map(it -> schema.getExceptionDefinitions(it.getTypeName()))
                        .flatMap(Collection::stream)
                        .filter(it -> errorTypeName.equals(it.getName()))
                        .findFirst()
        ).orElse(null);
    }

    /**
     * @return Map of variable names and types for the declared and fully set records.
     */
    @NotNull
    private Map<String, InputField> parseInputs(List<? extends InputField> specInputs, ProcessedSchema schema) {
        var serviceInputs = new LinkedHashMap<String, InputField>();

        for (var in : specInputs) {
            var inType = schema.getInputType(in);
            if (inType == null) {
                serviceInputs.put(uncapitalize(in.getName()), in);
            } else if (inType.hasTable() && !inType.hasJavaRecordReference()) {
                serviceInputs.putAll(parseInputs(in, schema, 0));
            } else if (inType.hasJavaRecordReference()) {
                serviceInputs.put(asListedRecordNameIf(in.getName(), in.isIterableWrapped()), in);
            } else {
                serviceInputs.put(uncapitalize(in.getName()), in);
            }
        }
        return serviceInputs;
    }

    /**
     * @return Map of variable names and types for the declared records.
     */
    @NotNull
    private Map<String, InputField> parseInputs(InputField target, ProcessedSchema schema, int recursion) {
        recursionCheck(recursion);

        var serviceInputs = new LinkedHashMap<String, InputField>();
        serviceInputs.put(asListedRecordNameIf(target.getName(), target.isIterableWrapped()), target);
        schema
                .getInputType(target)
                .getFields()
                .stream()
                .filter(schema::hasJOOQRecord)
                .flatMap(in -> parseInputs(in, schema, recursion + 1).entrySet().stream())
                .forEach(it -> serviceInputs.put(it.getKey(), it.getValue()));
        return serviceInputs;
    }

    /**
     * @return Map of inputs that the field specifies.
     */
    public Map<String, InputField> getMethodInputs() {
        return methodInputs;
    }

    /**
     * @return List of input names that the field specifies.
     */
    public List<String> getMethodInputNames() {
        return new ArrayList<>(methodInputs.keySet());
    }

    /**
     * @return Map of inputs that the field specifies.
     */
    public Map<String, InputField> getMethodInputsWithOrderField() {
        return methodInputsWithOrderField;
    }

    /**
     * @return Map of inputs that the field specifies that correspond to any records.
     */
    public Map<String, InputField> getRecords() {
        return recordInputs;
    }

    /**
     * @return Map of inputs that the field specifies that correspond to jOOQ records.
     */
    public Map<String, InputField> getJOOQRecords() {
        return jOOQInputs;
    }

    /**
     * @return Are there any records among the inputs?
     */
    public boolean hasRecords() {
        return !recordInputs.isEmpty();
    }

    /**
     * @return Are there any jOOQ records among the inputs?.
     */
    public boolean hasJOOQRecords() {
        return !jOOQInputs.isEmpty();
    }

    /**
     * @return The inputs this service will require formatted as a comma separated string.
     */
    public String getInputParamString() {
        return serviceInputString;
    }

    /**
     * @return List of all error types this operation has specified in the schema.
     */
    public List<ObjectField> getAllErrors() {
        return allErrors;
    }

    /**
     * @return ExceptionDefinition used for validation errors. If it's configured and present in the schema as a returnable error for this operation.
     */
    public Optional<ExceptionDefinition> getValidationErrorException() {
        return Optional.ofNullable(validationErrorException);
    }
}
