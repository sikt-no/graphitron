package no.fellesstudentsystem.graphitron.generators.context;

import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ExceptionDefinition;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.getRecordValidation;
import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asListedRecordNameIf;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.ERROR_TYPE;

/**
 * A helper class for handling input type data for services and mutations.
 */
public class InputParser {
    private final Map<String, InputField> methodInputs, recordInputs, jOOQInputs;
    private final String serviceInputString;
    private final List<ObjectField> allErrors;
    private final ExceptionDefinition validationErrorException;

    public InputParser(ObjectField target, ProcessedSchema processedSchema) {
        methodInputs = parseInputs(target.getArguments(), processedSchema);
        recordInputs = methodInputs
                .entrySet()
                .stream()
                .filter(it -> processedSchema.isRecordType(it.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        jOOQInputs = recordInputs
                .entrySet()
                .stream()
                .filter(it -> processedSchema.isTableInputType(it.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        serviceInputString = String.join(", ", methodInputs.keySet());
        if (processedSchema.isObject(target) && processedSchema.isInterface(ERROR_TYPE.getName())) {
            allErrors = processedSchema.getAllErrors(target.getTypeName());
        } else {
            allErrors = List.of();
        }

        validationErrorException = !recordValidationEnabled() ? null : getRecordValidation().getSchemaErrorType().flatMap(errorTypeName ->
                allErrors.stream()
                        .map(it -> processedSchema.getExceptionDefinitions(it.getTypeName()))
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
                serviceInputs.put(in.getName(), in);
            } else if (inType.hasTable() && !inType.hasJavaRecordReference()) {
                serviceInputs.putAll(parseInputs(in, schema, 0));
            } else if (inType.hasJavaRecordReference()) {
                serviceInputs.put(asListedRecordNameIf(in.getName(), in.isIterableWrapped()), in);
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
                .filter(schema::isTableInputType)
                .flatMap(in -> parseInputs(in, schema, recursion + 1).entrySet().stream())
                .forEach(it -> serviceInputs.put(it.getKey(), it.getValue()));
        return serviceInputs;
    }

    /**
     * @return Map of inputs that the mutation field specifies.
     */
    public Map<String, InputField> getMethodInputs() {
        return methodInputs;
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
    public String getServiceInputString() {
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
