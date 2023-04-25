package no.fellesstudentsystem.graphitron.generators.context;

import no.fellesstudentsystem.codegenenums.GeneratorException;
import no.fellesstudentsystem.codegenenums.GeneratorService;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.AbstractObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ExceptionDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ServiceWrapper;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asListedName;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asRecordName;
import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.ERROR_TYPE;

public class UpdateContext {
    private final ServiceWrapper service;
    private final List<String> serviceInputNames;
    private final String serviceInputString;
    private final List<ObjectField> allErrors;
    private final ProcessedSchema processedSchema;
    private final Map<String, Class<?>> exceptionOverrides;

    public UpdateContext(
            ObjectField target,
            ProcessedSchema processedSchema,
            Map<String, Class<?>> exceptionOverrides,
            Map<String, Class<?>> serviceOverrides
    ) {
        this.processedSchema = processedSchema;
        this.exceptionOverrides = exceptionOverrides;

        if (target.hasServiceReference()) {
            var reference = target.getServiceReference();
            service = new ServiceWrapper(
                    target.getName(),
                    countParams(target.getInputFields(), false, processedSchema),
                    serviceOverrides.containsKey(reference) ? serviceOverrides.get(reference) :  GeneratorService.valueOf(reference).getService()
            );
        } else {
            service = null;
        }

        serviceInputNames = parseInputs(target.getInputFields());
        serviceInputString = String.join(", ", serviceInputNames);
        if (processedSchema.isObject(target.getTypeName()) && processedSchema.isInterface(ERROR_TYPE.getName())) {
            allErrors = getAllErrors(target.getTypeName());
        } else {
            allErrors = List.of();
        }
    }

    public static int countParams(List<InputField> fields, boolean inRecord, ProcessedSchema processedSchema) {
        var numFields = 0;
        for (var input : fields) {
            if (processedSchema.isInputType(input.getTypeName())) {
                var object = processedSchema.getInputType(input.getTypeName());
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
     * @return List of variable names for the declared and fully set records.
     */
    @NotNull
    private List<String> parseInputs(List<InputField> specInputs) {
        var serviceInputs = new ArrayList<String>();

        for (var in : specInputs) {
            if (processedSchema.isInputType(in.getTypeName())) {
                serviceInputs.addAll(parseInputs(in, 0));
            } else {
                serviceInputs.add(in.getName());
            }
        }
        return serviceInputs;
    }

    /**
     * @return List of variable names for the declared records.
     */
    @NotNull
    private List<String> parseInputs(InputField target, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target.getTypeName());
        if (!input.hasTable()) {
            return List.of();
        }

        var targetAsRecordName = asRecordName(target.getName());
        return Stream
                .concat(
                        Stream.of(target.getFieldType().isIterableWrapped() ? asListedName(targetAsRecordName) : targetAsRecordName),
                        input
                                .getInputs()
                                .stream()
                                .filter(in -> processedSchema.isInputType(in.getTypeName()))
                                .flatMap(in -> parseInputs(in, recursion + 1).stream())
                )
                .collect(Collectors.toList());
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
    public Class<?> getExceptionClass(String key) {
        return exceptionOverrides.containsKey(key) ? exceptionOverrides.get(key) : GeneratorException.valueOf(key).getException();
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
            if (!processedSchema.isInputType(field.getTypeName())) {
                fields.put(field.getUpperCaseName(), pathIteration + field.getName());
            } else {
                var inputType = processedSchema.getInputType(field.getTypeName());
                fields.putAll(getAllNestedInputFieldMappingsWithPaths(inputType.getInputs(), pathIteration + inputType.getName()));
            }
        }
        return fields;
    }

    private boolean hasService() {
        return service != null;
    }

    public ServiceWrapper getService() {
        return service;
    }

    public List<String> getServiceInputNames() {
        return serviceInputNames;
    }

    public String getServiceInputString() {
        return serviceInputString;
    }

    public List<ObjectField> getAllErrors() {
        return allErrors;
    }

    public boolean hasErrors() {
        return !allErrors.isEmpty();
    }

    private static void recursionCheck(int recursion) {
        if (recursion == Integer.MAX_VALUE - 1) {
            throw new RuntimeException("Recursion depth has reached the integer max value.");
        }
    }
}
