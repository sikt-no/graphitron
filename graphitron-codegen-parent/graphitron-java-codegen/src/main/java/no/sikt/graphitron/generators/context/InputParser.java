package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ExceptionDefinition;
import no.sikt.graphitron.generators.codebuilding.VariablePrefix;
import no.sikt.graphitron.javapoet.ParameterSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.naming.GraphQLReservedName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.GeneratorConfig.getRecordValidation;
import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedRecordNameIf;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.iterableWrapType;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_PAGE_SIZE;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.contextFieldPrefix;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.mappings.JavaPoetClassName.INTEGER;
import static no.sikt.graphitron.mappings.JavaPoetClassName.STRING;
import static no.sikt.graphql.naming.GraphQLReservedName.ERROR_TYPE;
import static no.sikt.graphql.naming.GraphQLReservedName.PAGINATION_AFTER;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * A helper class for handling input type data for services and mutations.
 * Order of fields is as follows: Basic non-reserved fields -> ordering field -> pagination fields -> context fields
 */
public class InputParser {
    private final static List<ParameterSpec> FORWARD_PAGINATION_SPECS = List.of(
            ParameterSpec.of(INTEGER.className, VAR_PAGE_SIZE),
            ParameterSpec.of(STRING.className, inputPrefix(GraphQLReservedName.PAGINATION_AFTER.getName()))
    );
    private final static List<String> FORWARD_PAGINATION_INPUTS = List.of(VAR_PAGE_SIZE, inputPrefix(PAGINATION_AFTER.getName()));
    private final Map<String, InputField> methodInputs, recordInputs, jOOQInputs, orderField;
    private final Map<String, TypeName> contextInputs ;
    private final List<String> methodInputParams, contextInputNames, orderFieldParam;
    private final List<ObjectField> allErrors;
    private final ExceptionDefinition validationErrorException;
    private final ProcessedSchema schema;
    private final boolean hasForwardPagination;

    public InputParser(ObjectField target, ProcessedSchema schema) {
        this.schema = schema;
        this.hasForwardPagination = target.hasForwardPagination();
        methodInputs = parseInputs(target.getNonReservedArguments(), schema);
        orderField = target.getOrderField().map(it -> parseInputs(List.of(it), schema)).orElse(Map.of());
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
        methodInputParams = methodInputs.keySet().stream().map(VariablePrefix::inputPrefix).toList();
        orderFieldParam = orderField.keySet().stream().map(VariablePrefix::inputPrefix).toList();
        contextInputs = schema.getAllContextFields(target);
        contextInputNames = contextInputs.keySet().stream().map(VariablePrefix::contextFieldPrefix).toList();

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
     * @return List of all context input names that the field specifies.
     */
    public List<String> getContextFieldNames() {
        return contextInputNames;
    }

    /**
     * @return List of all input names that the field specifies.
     */
    public List<String> getMethodInputNames(boolean includeOrder, boolean includeForwardPagination, boolean includeContextFields) {
        var fieldList = new ArrayList<>(methodInputParams);
        if (includeOrder) {
            fieldList.addAll(orderFieldParam);
        }
        if (includeForwardPagination && hasForwardPagination) {
            fieldList.addAll(FORWARD_PAGINATION_INPUTS);
        }
        if (includeContextFields) {
            fieldList.addAll(contextInputNames);
        }
        return fieldList;
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

    public List<ParameterSpec> getMethodParameterSpecs(boolean includeOrder, boolean includeForwardPagination, boolean includeContextFields) {
        var specList = new ArrayList<>(getMethodParameterSpecs(methodInputs));
        if (includeOrder) {
            specList.addAll(getMethodParameterSpecs(orderField));
        }
        if (includeForwardPagination && hasForwardPagination) {
            specList.addAll(FORWARD_PAGINATION_SPECS);
        }
        if (includeContextFields) {
            specList.addAll(getContextParameterSpecs());
        }
        return specList;
    }

    private List<ParameterSpec> getMethodParameterSpecs(Map<String, InputField> inputs) {
        return inputs
                .entrySet()
                .stream()
                .map((it) -> ParameterSpec.of(iterableWrapType(it.getValue(), true, schema), inputPrefix(it.getKey())))
                .toList();
    }

    public List<ParameterSpec> getContextParameterSpecs() {
        return contextInputs
                .entrySet()
                .stream()
                .map((it) -> ParameterSpec.of(it.getValue(), contextFieldPrefix(it.getKey())))
                .toList();
    }
}
