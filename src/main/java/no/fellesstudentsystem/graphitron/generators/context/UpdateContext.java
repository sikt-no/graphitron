package no.fellesstudentsystem.graphitron.generators.context;

import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.MutationType;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ExceptionDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ServiceWrapper;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;

import java.util.*;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.getRecordValidation;
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
            service = new ServiceWrapper(reference, countParams(target.getArguments(), false, processedSchema));
        } else {
            service = null;
        }

        mutationType = target.hasMutationType() ? target.getMutationType() : null;

        mutationReturnsNodes = processedSchema.containsNodeField(target);
        mutationInputs = processedSchema.parseInputs(target.getArguments());
        recordInputs = mutationInputs
                .entrySet()
                .stream()
                .filter(it -> processedSchema.isTableInputType(it.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        serviceInputString = String.join(", ", mutationInputs.keySet());
        if (processedSchema.isObject(target) && processedSchema.isInterface(ERROR_TYPE.getName())) {
            allErrors = processedSchema.getAllErrors(target.getTypeName());
        } else {
            allErrors = List.of();
        }

        validationErrorException = getRecordValidation().getSchemaErrorType().flatMap(errorTypeName ->
                allErrors.stream()
                        .map(it -> processedSchema.getExceptionDefinitions(it.getTypeName()))
                        .flatMap(Collection::stream)
                        .filter(it -> errorTypeName.equals(it.getName()))
                        .findFirst()
        ).orElse(null);
    }

    /**
     * @return Count the number of parameters this mutation will have to use for its service call.
     */
    public static int countParams(List<? extends InputField> fields, boolean inRecord, ProcessedSchema processedSchema) {
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

    public ProcessedSchema getProcessedSchema() {
        return processedSchema;
    }
}
