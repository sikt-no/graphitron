package no.fellesstudentsystem.graphitron.generators.context;

import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.MutationType;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.AbstractObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ExceptionDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ServiceWrapper;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.getRecordValidation;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.*;
import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.ERROR_TYPE;

/**
 * A helper class for handling mutation data. Information that may be needed in creating a single mutation can be precalculated here.
 */
public class UpdateContext {
    private final ServiceWrapper service;
    private final MutationType mutationType;
    private final Map<String, InputField> mutationInputs, recordInputs;
    private final String serviceInputString;
    private final List<ObjectField> allErrors;
    private final ProcessedSchema processedSchema;
    private final boolean mutationReturnsNodes;
    private final ExceptionDefinition validationErrorException;

    public UpdateContext(ObjectField target, ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;

        if (target.hasServiceReference()) {
            var reference = target.getServiceReference();
            service = new ServiceWrapper(reference, countParams(target.getInputFields(), false, processedSchema));
        } else {
            service = null;
        }

        mutationType = target.hasMutationType() ? target.getMutationType() : null;

        mutationReturnsNodes = containsNodeField(target);
        mutationInputs = parseInputs(target.getInputFields());
        recordInputs = mutationInputs
                .entrySet()
                .stream()
                .filter(it -> processedSchema.isTableInputType(it.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        serviceInputString = String.join(", ", mutationInputs.keySet());
        if (processedSchema.isObject(target) && processedSchema.isInterface(ERROR_TYPE.getName())) {
            allErrors = getAllErrors(target.getTypeName());
        } else {
            allErrors = List.of();
        }

        validationErrorException = getRecordValidation().getSchemaErrorType().flatMap(errorTypeName ->
                allErrors.stream()
                        .map(it -> getExceptionDefinitions(it.getTypeName()))
                        .flatMap(Collection::stream)
                        .filter(it -> errorTypeName.equals(it.getName()))
                        .findFirst()
        ).orElse(null);
    }

    /**
     * @return Does this field point to a type that contains a node field?
     */
    private boolean containsNodeField(ObjectField target) {
        if (!processedSchema.isObject(target)) {
            return false;
        }

        if (processedSchema.isTableObject(target)) {
            return true;
        }

        return processedSchema.getObject(target).getFields().stream().anyMatch(this::containsNodeField);
    }

    /**
     * @return Count the number of parameters this mutation will have to use for its service call.
     */
    public static int countParams(List<InputField> fields, boolean inRecord, ProcessedSchema processedSchema) {
        var numFields = 0;
        for (var input : fields) {
            if (processedSchema.isInputType(input)) {
                var object = processedSchema.getInputType(input);
                if (object.hasTable()) {
                    numFields++;
                }
                numFields += countParams(object.getFields(), inRecord || object.hasTable(), processedSchema);
            } else if (!inRecord) {
                numFields++;
            }
        }
        return numFields;
    }

    /**
     * @return Map of variable names and types for the declared and fully set records.
     */
    @NotNull
    private Map<String, InputField> parseInputs(List<InputField> specInputs) {
        var serviceInputs = new LinkedHashMap<String, InputField>();

        for (var in : specInputs) {
            if (processedSchema.isTableInputType(in)) {
                serviceInputs.putAll(parseInputs(in, 0));
            } else {
                serviceInputs.put(in.getName(), in);
            }
        }
        return serviceInputs;
    }

    /**
     * @return Map of variable names and types for the declared records.
     */
    @NotNull
    private Map<String, InputField> parseInputs(InputField target, int recursion) {
        recursionCheck(recursion);

        var serviceInputs = new LinkedHashMap<String, InputField>();
        serviceInputs.put(asListedRecordNameIf(target.getName(), target.isIterableWrapped()), target);
        processedSchema
                .getInputType(target)
                .getFields()
                .stream()
                .filter(processedSchema::isTableInputType)
                .flatMap(in -> parseInputs(in, recursion + 1).entrySet().stream())
                .forEach(it -> serviceInputs.put(it.getKey(), it.getValue()));
        return serviceInputs;
    }

    /**
     * @return List of all error types this type contains.
     */
    @NotNull
    private List<ObjectField> getAllErrors(String typeName) {
        return processedSchema
                .getObject(typeName)
                .getFields()
                .stream()
                .filter(it -> processedSchema.isExceptionOrExceptionUnion(it.getTypeName()))
                .collect(Collectors.toList());
    }

    /**
     * @return The error type or union of error types with this name if it exists.
     */
    @NotNull
    public AbstractObjectDefinition<?, ?> getErrorTypeDefinition(String name) {
        return processedSchema.isUnion(name) ? processedSchema.getUnion(name) : processedSchema.getException(name);
    }

    /**
     * @return List of exception definitions that exists for this type name. If it is not a union, the list will only have one element.
     */
    @NotNull
    public List<ExceptionDefinition> getExceptionDefinitions(String name) {
        if (!processedSchema.isUnion(name)) {
            return List.of(processedSchema.getException(name));
        }

        return processedSchema
                .getUnion(name)
                .getFieldTypeNames()
                .stream()
                .map(processedSchema::getException)
                .collect(Collectors.toList());
    }

    /**
     * @return Comma separated list of field names with paths that may be the cause of an error for the inputs of this field.
     */
    @NotNull
    public String getFieldErrorNameSets(ObjectField target) {
        return getAllNestedInputFieldMappingsWithPaths(target.getInputFields(), "")
                .entrySet()
                .stream()
                .flatMap(it -> Stream.of(it.getKey(), it.getValue()))
                .collect(Collectors.joining("\", \"", "\"", "\""));
    }

    private Map<String, String> getAllNestedInputFieldMappingsWithPaths(List<InputField> targets, String path) {
        var fields = new HashMap<String, String>();
        var pathIteration = path.isEmpty() ? path : path + ".";
        for (var field : targets) {
            if (!processedSchema.isInputType(field)) {
                fields.put(field.getUpperCaseName(), pathIteration + field.getName());
            } else {
                var inputType = processedSchema.getInputType(field);
                fields.putAll(getAllNestedInputFieldMappingsWithPaths(inputType.getFields(), pathIteration + inputType.getName()));
            }
        }
        return fields;
    }

    /**
     * @return Does this mutation use a service? In other words, does it have the {@link GenerationDirective#SERVICE} directive set?
     */
    public boolean hasService() {
        return service != null;
    }

    /**
     * @return The ServiceWrapper for the service use in this mutation.
     */
    public ServiceWrapper getService() {
        return service;
    }

    /**
     * @return Does this mutation have the {@link GenerationDirective#MUTATION} directive set?
     */
    public boolean hasMutationType() {
        return mutationType != null;
    }

    /**
     * @return The mutation type for this mutation.
     */
    public MutationType getMutationType() {
        return mutationType;
    }

    /**
     * @return Map of inputs that the mutation field specifies.
     */
    public Map<String, InputField> getMutationInputs() {
        return mutationInputs;
    }

    /**
     * @return Map of inputs that the mutation field specifies that correspond to records.
     */
    public Map<String, InputField> getRecordInputs() {
        return recordInputs;
    }

    /**
     * @return The inputs this mutation's service will require formatted as a comma separated string.
     */
    public String getServiceInputString() {
        return serviceInputString;
    }

    /**
     * @return Does this mutation return any node types?
     */
    public boolean mutationReturnsNodes() {
        return mutationReturnsNodes;
    }

    /**
     * @return List of all error types this mutation has specified in the schema.
     */
    public List<ObjectField> getAllErrors() {
        return allErrors;
    }

    /**
     * @return Does this mutation type return any errors?
     */
    public boolean hasErrors() {
        return !allErrors.isEmpty();
    }

    /*
     * @return ExceptionDefinition used for validation errors. If it's configured and present in the schema as a returnable error for this mutation.
     */
    public Optional<ExceptionDefinition> getValidationErrorException() {
        return  Optional.ofNullable(validationErrorException);
    }
}
