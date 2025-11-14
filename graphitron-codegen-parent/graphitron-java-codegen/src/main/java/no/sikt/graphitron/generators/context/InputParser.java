package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ExceptionDefinition;
import no.sikt.graphitron.generators.codebuilding.VariablePrefix;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.GeneratorConfig.getRecordValidation;
import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedRecordNameIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_PAGE_SIZE;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphql.naming.GraphQLReservedName.ERROR_TYPE;
import static no.sikt.graphql.naming.GraphQLReservedName.PAGINATION_AFTER;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * A helper class for handling input type data for services and mutations.
 * Order of fields is as follows: Basic non-reserved fields -> ordering field -> pagination fields -> context fields
 */
public class InputParser {
    private final Map<String, InputField> methodInputsWithOrderField, methodInputs, recordInputs, jOOQInputs;
    private final Map<String, TypeName> contextInputs ;
    private final List<String> inputParams, contextInputNames;
    private final List<ObjectField> allErrors;
    private final ExceptionDefinition validationErrorException;

    public InputParser(ObjectField target, ProcessedSchema schema) {
        methodInputs = parseInputs(target.getNonReservedArguments(), schema);
        var orderField = target.getOrderField().map(it -> parseInputs(List.of(it), schema)).orElse(Map.of());
        methodInputsWithOrderField = Stream.concat(methodInputs.entrySet().stream(), orderField.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new));
        recordInputs = methodInputs
                .entrySet()
                .stream()
                .filter(it -> schema.hasRecord(it.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new));
        jOOQInputs = recordInputs
                .entrySet()
                .stream()
                .filter(it -> schema.hasJOOQRecord(it.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new));
        inputParams = new ArrayList<>(methodInputsWithOrderField.keySet().stream().map(VariablePrefix::inputPrefix).toList());
        if (target.hasForwardPagination()) {
            inputParams.add(VAR_PAGE_SIZE);
            inputParams.add(inputPrefix(PAGINATION_AFTER.getName()));
        }
        contextInputs = schema.getAllContextFields(target);
        contextInputNames = contextInputs.keySet().stream().map(VariablePrefix::contextFieldPrefix).toList();
        inputParams.addAll(contextInputNames);

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
        var inputs = new LinkedHashMap<String, InputField>();

        for (var in : specInputs) {
            var inType = schema.getInputType(in);
            if (inType == null) {
                inputs.put(uncapitalize(in.getName()), in);
            } else if (inType.hasTable() && !inType.hasJavaRecordReference()) {
                inputs.putAll(parseInputs(in, schema, 0));
            } else if (inType.hasJavaRecordReference()) {
                inputs.put(asListedRecordNameIf(in.getName(), in.isIterableWrapped()), in);
            } else {
                inputs.put(uncapitalize(in.getName()), in);
            }
        }
        return inputs;
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
     * @return Map of context inputs for the field.
     */
    public Map<String, TypeName> getContextInputs() {
        return contextInputs;
    }

    /**
     * @return List of all input names that the field specifies.
     */
    public List<String> getMethodInputAndContextFieldNames() {
        return Stream.concat(methodInputs.keySet().stream().map(VariablePrefix::inputPrefix), contextInputNames.stream()).toList();
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
     * @return The inputs this the method call will require.
     */
    public List<String> getMethodInputParams() {
        return inputParams;
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
