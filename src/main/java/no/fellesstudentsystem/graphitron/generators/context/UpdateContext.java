package no.fellesstudentsystem.graphitron.generators.context;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.MutationType;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.AbstractObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ExceptionDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ServiceWrapper;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.context.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.ERROR_TYPE;

public class UpdateContext {
    private final ServiceWrapper service;
    private final MutationType mutationType;
    private final Map<String, InputField> serviceInputs, recordInputs;
    private final String serviceInputString;
    private final List<ObjectField> allErrors;
    private final ProcessedSchema processedSchema;
    private final boolean mutationReturnsNodes;

    public UpdateContext(ObjectField target, ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;

        if (target.hasServiceReference()) {
            var reference = target.getServiceReference();
            service = new ServiceWrapper(
                    target.getName(),
                    countParams(target.getInputFields(), false, processedSchema),
                    GeneratorConfig.getExternalServices().get(reference)
            );
        } else {
            service = null;
        }

        mutationType = target.hasMutationType() ? target.getMutationType() : null;

        mutationReturnsNodes = containsNodeField(target);
        serviceInputs = parseInputs(target.getInputFields());
        recordInputs = serviceInputs
                .entrySet()
                .stream()
                .filter(it -> processedSchema.isTableInputType(it.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        serviceInputString = String.join(", ", serviceInputs.keySet());
        if (processedSchema.isObject(target) && processedSchema.isInterface(ERROR_TYPE.getName())) {
            allErrors = getAllErrors(target.getTypeName());
        } else {
            allErrors = List.of();
        }
    }

    private boolean containsNodeField(ObjectField target) {
        if (!processedSchema.isObject(target)) {
            return false;
        }

        if (processedSchema.isTableObject(target)) {
            return true;
        }

        return processedSchema.getObject(target).getFields().stream().anyMatch(this::containsNodeField);
    }

    public static int countParams(List<InputField> fields, boolean inRecord, ProcessedSchema processedSchema) {
        var numFields = 0;
        for (var input : fields) {
            if (processedSchema.isInputType(input)) {
                var object = processedSchema.getInputType(input);
                if (object.hasTable()) {
                    numFields++;
                }
                numFields += countParams(object.getInputs(), inRecord || object.hasTable(), processedSchema);
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
                .getInputs()
                .stream()
                .filter(processedSchema::isTableInputType)
                .flatMap(in -> parseInputs(in, recursion + 1).entrySet().stream())
                .forEach(it -> serviceInputs.put(it.getKey(), it.getValue()));
        return serviceInputs;
    }

    @NotNull
    private List<ObjectField> getAllErrors(String typeName) {
        return processedSchema
                .getObject(typeName)
                .getFields()
                .stream()
                .filter(it -> processedSchema.isExceptionOrExceptionUnion(it.getTypeName()))
                .collect(Collectors.toList());
    }

    @NotNull
    public AbstractObjectDefinition<?> getErrorTypeDefinition(String name) {
        return processedSchema.isUnion(name) ? processedSchema.getUnion(name) : processedSchema.getException(name);
    }

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
                fields.putAll(getAllNestedInputFieldMappingsWithPaths(inputType.getInputs(), pathIteration + inputType.getName()));
            }
        }
        return fields;
    }

    public boolean hasService() {
        return service != null;
    }

    public ServiceWrapper getService() {
        return service;
    }

    public boolean hasMutationType() {
        return mutationType != null;
    }

    public MutationType getMutationType() {
        return mutationType;
    }

    public Map<String, InputField> getServiceInputs() {
        return serviceInputs;
    }

    public Map<String, InputField> getRecordInputs() {
        return recordInputs;
    }

    public String getServiceInputString() {
        return serviceInputString;
    }

    public boolean mutationReturnsNodes() {
        return mutationReturnsNodes;
    }

    public List<ObjectField> getAllErrors() {
        return allErrors;
    }

    public boolean hasErrors() {
        return !allErrors.isEmpty();
    }
}
