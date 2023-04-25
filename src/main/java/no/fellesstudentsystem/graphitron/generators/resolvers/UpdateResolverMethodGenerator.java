package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLRecord;
import no.fellesstudentsystem.graphitron.generators.dependencies.ContextDependency;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;
import no.fellesstudentsystem.kjerneapi.services.GeneratorService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.nCopies;
import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.generatedModelsPackage;
import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.NODE_TYPE;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for default update queries.
 */
public class UpdateResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    private static final String
            PARAM_ENV_NAME = "env",
            VARIABLE_ID_NAME = "ids",
            VARIABLE_RESULT_SUFFIX = "Result",
            VARIABLE_RESULT_PARAM = "result",
            VARIABLE_LIST_SUFFIX = "List",
            VARIABLE_ITERATE_PREFIX = "it",
            VARIABLE_GET_PREFIX = "get",
            VARIABLE_NODE_RESULT_NAME = "nodes",
            VARIABLE_SELECT_NAME = "select";

    private final ObjectField localField;

    public UpdateResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(processedSchema.getMutationType(), processedSchema);
        dependencySet.add(ContextDependency.getInstance());
        this.localField = localField;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var spec = getDefaultSpecBuilder(target.getName(), target.getFieldType().getWrappedTypeClass(processedSchema.getObjects()));

        var allInputs = processedSchema.getInputTypes();
        var specInputs = target.getInputFields();
        specInputs.forEach(input -> spec.addParameter(input.getFieldType().getWrappedTypeClass(allInputs), input.getName()));

        var code = CodeBlock
                .builder()
                .addStatement("var $L = new $T($N.getSelectionSet())", VARIABLE_SELECT_NAME, SELECTION_SETS.className, PARAM_ENV_NAME);
        var inputNames = declareRecords(specInputs, code);

        code
                .add("\n")
                .add(generateServiceCall(target, inputNames))
                .add(generateResponsesAndGetCalls(target))
                .add("\n")
                .addStatement("return $T.completedFuture($N)", COMPLETABLE_FUTURE.className, getExpectedResultName(target));

        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, PARAM_ENV_NAME)
                .addCode(declareAllServiceClasses())
                .addCode(code.build())
                .build();
    }

    @Nullable
    private String getExpectedResultName(ObjectField target) {
        var typeName = target.getTypeName();
        if (!processedSchema.isObject(typeName)) {
            return asResultName(target.getUnprocessedNameInput());
        }

        var nodeResultName = processedSchema.getObject(typeName).implementsInterface(NODE_TYPE.getName())
                ? asGetMethodVariableName(typeName, target.getName())
                : typeName;
        return target.getFieldType().isIterableWrapped() ? asListedName(nodeResultName) : uncapitalize(nodeResultName);
    }

    /**
     * @return Code that declares any service dependencies set for this generator.
     */
    private CodeBlock declareAllServiceClasses() {
        var code = CodeBlock.builder();
        dependencySet
                .stream()
                .filter(dep -> dep instanceof ServiceDependency) // Inelegant solution, but it should work for now.
                .distinct()
                .sorted()
                .map(dep -> (ServiceDependency) dep)
                .forEach(dep -> code.addStatement(dep.getDeclarationCode()));
        return code.build();
    }

    /**
     * @return List of variable names for the declared and fully set records.
     */
    @NotNull
    private List<String> declareRecords(List<InputField> specInputs, CodeBlock.Builder code) {
        var serviceInputs = new ArrayList<String>();

        var recordCode = CodeBlock.builder();
        for (var in : specInputs) {
            if (processedSchema.isInputType(in.getTypeName())) {
                serviceInputs.addAll(declareRecords(in, code, 0));
                recordCode.add(fillRecords(in, "", 0));
            } else {
                serviceInputs.add(in.getName());
            }
        }
        if (!recordCode.isEmpty()) {
            code.add("\n").add(recordCode.build());
        }
        return serviceInputs;
    }

    /**
     * @return List of variable names for the declared records.
     */
    @NotNull
    private List<String> declareRecords(InputField target, CodeBlock.Builder code, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target.getTypeName());
        if (!input.hasTable()) {
            return List.of();
        }
        var targetAsRecordName = SQLRecord.asRecordName(target.getName());

        var sqlRecordClassName = input.asSQLRecord().getGraphClassName();
        var isIterable = target.getFieldType().isIterableWrapped();
        if (isIterable) {
            code.addStatement("var $L = new $T<$T>()", asListedName(targetAsRecordName), ARRAY_LIST.className, sqlRecordClassName);
        } else {
            code.addStatement("var $L = new $T()", targetAsRecordName, sqlRecordClassName);
        }

        return Stream
                .concat(
                        Stream.of(isIterable ? asListedName(targetAsRecordName) : targetAsRecordName),
                        input
                                .getInputs()
                                .stream()
                                .filter(in -> processedSchema.isInputType(in.getTypeName()))
                                .flatMap(in -> declareRecords(in, code, recursion + 1).stream())
                )
                .collect(Collectors.toList());
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    @NotNull
    private CodeBlock fillRecords(InputField target, String previousRecord, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target.getTypeName());
        var hasTable = input.hasTable();
        var targetName = target.getName();
        var recordName = hasTable ? SQLRecord.asRecordName(targetName) : previousRecord;
        var sqlRecord = input.asSQLRecord();
        var iterableInputName = targetName;

        var code = CodeBlock.builder();

        String potentialArrayListName = "";
        var isIterable = target.getFieldType().isIterableWrapped();
        if (isIterable) {
            if (!hasTable) {
                return CodeBlock.of("");
            }

            potentialArrayListName = asListedName(recordName);
            iterableInputName = asIterable(iterableInputName);
            code
                    .add("\n")
                    .beginControlFlow("if ($N != null)", targetName)
                    .beginControlFlow("for (var $L : $N)", iterableInputName, targetName)
                    .addStatement("if ($N == null) continue", iterableInputName)
                    .addStatement("var $L = new $T()", recordName, sqlRecord.getGraphClassName());
        }

        for (var in : input.getInputs()) {
            if (processedSchema.isInputType(in.getTypeName())) {
                code
                        .addStatement("var $L = $N.get$L()", in.getName(), iterableInputName, capitalize(in.getName()))
                        .add(fillRecords(in, recordName, recursion + 1));
            } else {
                code.addStatement(
                        "$N" + in.getRecordSetCall("$N" + in.getMappingFromFieldName().asGetCall()),
                        recordName,
                        iterableInputName
                );
            }
        }

        if (isIterable) {
            code.addStatement("$N.add($N)", potentialArrayListName, recordName).endControlFlow().endControlFlow();
        }
        return code.isEmpty() || isIterable ? code.build() : CodeBlock
                .builder()
                .beginControlFlow("if ($N != null)", iterableInputName)
                .add(code.build())
                .endControlFlow()
                .build();
    }

    private CodeBlock generateServiceCall(ObjectField target, List<String> inputNames) {
        return CodeBlock
                .builder()
                .add("var $L = $N.$L(", asResultName(target.getUnprocessedNameInput()), uncapitalize(extractServiceName(target)), target.getName()) // Method name is expected to be the field's name.
                .add(String.join(", ", nCopies(inputNames.size(), "$N")), inputNames.toArray())
                .addStatement(")")
                .build();
    }

    @NotNull
    private String extractServiceName(ObjectField target) {
        var service = GeneratorService.valueOf(target.getServiceReference()).getService();
        var serviceName = service.getSimpleName();
        dependencySet.add(new ServiceDependency(serviceName, service.getPackageName()));
        return serviceName;
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    private CodeBlock generateResponsesAndGetCalls(ObjectField target) {
        var code = CodeBlock.builder();
        var typeName = target.getTypeName();
        if (!processedSchema.isObject(typeName)) {
            return code.build();
        }

        return code
                .add(generateResultUnpacking(target))
                .add(generateGetCalls(target, target, 0))
                .add(generateResponses(target, target, 0))
                .build();
    }

    /**
     * @return Code that unpacks the result structure from a service.
     */
    private CodeBlock generateResultUnpacking(ObjectField target) {
        var code = CodeBlock.builder();
        var service = GeneratorService.valueOf(target.getServiceReference()).getService();
        var serviceReturnType = getServiceReturnTypeClass(target, service);
        if (serviceReturnType.getEnclosingClass() != service) {
            return code.build();
        }

        return code.add(generateUnpacking(target, null, 0)).add("\n").build();
    }

    /**
     * @return Code that unpacks the result structure.
     */
    private CodeBlock generateUnpacking(ObjectField target, ObjectField previous, int recursion) {
        recursionCheck(recursion);

        var targetTypeName = target.getTypeName();
        var code = CodeBlock.builder();
        if (!processedSchema.isObject(targetTypeName)) {
            return code.build();
        }

        var responseObject = processedSchema.getObject(targetTypeName);
        if (responseObject.implementsInterface(NODE_TYPE.getName())) {
            return code.build();
        }

        if (previous != null) {
            var targetName = target.getUnprocessedNameInput();
            var previousName = previous.getUnprocessedNameInput();
            if (previous.getFieldType().isIterableWrapped()) {
                code.addStatement(
                        "var $L = $N.stream().flatMap(it -> it$L.stream()).collect($T.toList())",
                        asResultName(targetName),
                        asResultName(previousName),
                        target.getMappingFromColumn().asGetCall(),
                        COLLECTORS.className
                );
            } else {
                code.addStatement(
                        "var $L = $N$L",
                        asResultName(targetName),
                        asResultName(previousName),
                        target.getMappingFromColumn().asGetCall()
                );
            }
        }

        responseObject
                .getFields()
                .forEach(field -> code.add(generateUnpacking(field, target, recursion + 1)));

        return code.build();
    }

    /**
     * @return Code that calls and stores the result of any helper methods that should be called.
     */
    private CodeBlock generateGetCalls(ObjectField target, ObjectField previous, int recursion) {
        recursionCheck(recursion);

        var targetTypeName = target.getTypeName();

        var code = CodeBlock.builder();
        if (!processedSchema.isObject(targetTypeName)) {
            return code.build();
        }

        var responseObject = processedSchema.getObject(targetTypeName);
        if (responseObject.implementsInterface(NODE_TYPE.getName())) {
            return code
                    .addStatement(
                            "var $L = $N($N, $N)",
                            asGetMethodVariableName(previous.getTypeName(), target.getName()),
                            asGetMethodName(previous.getTypeName(), target.getName()),
                            asResultName(previous.getUnprocessedNameInput()),
                            VARIABLE_SELECT_NAME
                    )
                    .build();
        }

        responseObject
                .getFields()
                .forEach(field -> code.add(generateGetCalls(field, target, recursion + 1)));

        return code.build();
    }

    /**
     * @return Code for constructing any structure of response types.
     */
    private CodeBlock generateResponses(ObjectField target, ObjectField previous, int recursion) {
        recursionCheck(recursion);

        var code = CodeBlock.builder();
        var targetTypeName = target.getTypeName();

        if (!processedSchema.isObject(targetTypeName)) {
            return code.build();
        }

        var responseObject = processedSchema.getObject(targetTypeName);
        if (responseObject.implementsInterface(NODE_TYPE.getName())) {
            return code.build();
        }

        var responseClassName = responseObject.getGraphClassName();
        var isIterable = target.getFieldType().isIterableWrapped();
        var targetTypeNameLower = uncapitalize(targetTypeName);
        var responseListName = isIterable ? asListedName(targetTypeNameLower) : targetTypeNameLower;
        code.add("\n");
        if (isIterable) {
            code.addStatement("var $L = new $T<$T>()", responseListName, ARRAY_LIST.className, responseClassName);
            var previousIsIterable = previous.getFieldType().isIterableWrapped();
            var targetResultName = asIterableResultName(target.getUnprocessedNameInput());
            if (previousIsIterable && target != previous) {
                code.beginControlFlow(
                        "for (var $L : $N$L)",
                        targetResultName,
                        asIterableResultName(previous.getUnprocessedNameInput()),
                        target.getMappingFromColumn().asGetCall()
                );
            } else {
                code.beginControlFlow("for (var $L : $N)", targetResultName, asResultName(target.getUnprocessedNameInput()));
            }
        }

        code.addStatement("var $L = new $T()", targetTypeNameLower, responseClassName);

        for (var field : responseObject.getFields()) {
            code
                    .add(generateResponses(field, target, recursion + 1))
                    .add(mapToSetCall(field, target));
        }

        if (isIterable) {
            code.addStatement("$N.add($N)", responseListName, targetTypeNameLower).endControlFlow();
        }
        return code.build();
    }

    /**
     * @return Code for any set method calls for this response object.
     */
    private CodeBlock mapToSetCall(ObjectField field, ObjectField previousField) {
        var code = CodeBlock.builder();

        var fieldName = field.getName();
        var fieldMapping = field.getMappingFromFieldName();

        var previousTypeName = previousField.getTypeName();
        var previousIsIterable = previousField.getFieldType().isIterableWrapped();
        var targetTypeNameLower = uncapitalize(previousTypeName);
        if (processedSchema.isObject(field.getTypeName())) {
            var getVariable = asGetMethodVariableName(previousTypeName, fieldName);
            var isNode = processedSchema.getObject(field.getTypeName()).implementsInterface(NODE_TYPE.getName());
            code.add("$N", targetTypeNameLower);

            if (isNode) {
                if (previousIsIterable) {
                    code.addStatement(fieldMapping.asSetCall("$N.get($N.getId())"), getVariable, targetTypeNameLower);
                } else {
                    code.addStatement(fieldMapping.asSetCall("$N"), getVariable);
                }
            } else {
                code.addStatement(fieldMapping.asSetCall("$N"), field.getFieldType().isIterableWrapped() ? asListedName(fieldName) : fieldName);
            }
        } else {
            code.addStatement(
                    "$N" + fieldMapping.asSetCall("$N" + field.getMappingFromColumn().asGetCall()),
                    targetTypeNameLower,
                    previousIsIterable ? asIterableResultName(previousField.getUnprocessedNameInput()) : asResultName(previousField.getUnprocessedNameInput())
            );
        }
        return code.build();
    }

    /**
     * Set up the generation of all the helper get methods for this schema tree. Searches recursively.
     *
     * @return The code for any get helper methods to be made for this schema tree.
     */
    private List<MethodSpec> generateGetMethods(ObjectField target) {
        var responseObject = processedSchema.getObject(target.getTypeName());
        if (responseObject == null) {
            return List.of();
        }

        var service = GeneratorService.valueOf(target.getServiceReference()).getService();
        var serviceReturnType = getServiceReturnTypeClass(target, service);
        var classes = Arrays.stream(service.getClasses()).collect(Collectors.toSet());
        var extracted = extractType(serviceReturnType);

        return generateGetMethod(target, target, "", extracted, extracted, classes);
    }

    /**
     * @return The class object of the type returned by the service.
     */
    private Class<?> getServiceReturnTypeClass(ObjectField target, Class<?> service) {
        return getServiceReturnMethod(target, service).map(Method::getReturnType).orElse(null);
    }

    /**
     * @return The method object of the type returned by the service.
     */
    @NotNull
    private Optional<Method> getServiceReturnMethod(ObjectField target, Class<?> service) {
        var targetName = target.getName();
        var totalFields = countParams(target.getInputFields(), false);

        return Stream
                .of(service.getMethods())
                .filter(m -> m.getName().equals(targetName) && m.getParameterTypes().length == totalFields)
                .findFirst();
    }

    private int countParams(List<InputField> fields, boolean inRecord) {
        var numFields = 0;
        for (var input : fields) {
            if (processedSchema.isInputType(input.getTypeName())) {
                var object = processedSchema.getInputType(input.getTypeName());
                if (object.hasTable()) {
                    numFields++;
                }
                numFields += countParams(object.getInputs(), inRecord || object.hasTable());
            } else if (!inRecord) {
                numFields++;
            }
        }
        return numFields;
    }

    /**
     * @return The code for any get helper methods to be made for this schema tree.
     */
    @NotNull
    private List<MethodSpec> generateGetMethod(
            ObjectField target,
            ObjectField previous,
            String path,
            Class<?> returnTypeClass,
            Class<?> previousTypeClass,
            Set<Class<?>> classes
    ) {
        var typeName = target.getTypeName();
        var responseFieldClassName = ClassName.get(generatedModelsPackage(), typeName);

        if (!processedSchema.isObject(typeName)) {
            return List.of();
        }

        if (!processedSchema.getObject(typeName).implementsInterface(NODE_TYPE.getName())) {
            return flatMapSpecs(target, path, returnTypeClass, classes);
        }

        var previousIsIterable = previous.getFieldType().isIterableWrapped();
        var returnType = previousIsIterable
                ? ParameterizedTypeName.get(MAP.className, STRING.className, responseFieldClassName)
                : responseFieldClassName;
        var methodCode = createGetMethodCode(
                target,
                path.isEmpty() ? target.getName() : path,
                !classes.contains(previousTypeClass),
                previousIsIterable
        );
        return List.of(
                MethodSpec
                        .methodBuilder(asGetMethodName(previous.getTypeName(), target.getName()))
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(getServiceReturnParam(previousTypeClass.getName(), previousIsIterable))
                        .addParameter(SELECTION_SETS.className, VARIABLE_SELECT_NAME)
                        .returns(returnType)
                        .addCode(methodCode)
                        .build()
        );
    }

    @NotNull
    private List<MethodSpec> flatMapSpecs(ObjectField target, String path, Class<?> returnTypeClass, Set<Class<?>> classes) {
        var returnMethods = returnTypeClass.getDeclaredMethods();
        return processedSchema
                .getObject(target.getTypeName())
                .getFields()
                .stream()
                .flatMap(field -> {
                            var nextReturn = Arrays
                                    .stream(returnMethods)
                                    .filter(m -> m.getName().equals(field.getMappingFromColumn().asGet()))
                                    .findFirst();
                            return generateGetMethod(
                                    field,
                                    target,
                                    (path.isEmpty() ? "" : path + "/") + field.getName(),
                                    nextReturn.isPresent() ? extractType(nextReturn.get().getGenericReturnType()) : returnTypeClass,
                                    returnTypeClass,
                                    classes
                            ).stream();
                        }
                )
                .collect(Collectors.toList());
    }

    private Class<?> extractType(Type type) {
        return (Class<?>) (type instanceof ParameterizedType ? ((ParameterizedType) type).getActualTypeArguments()[0] : type);
    }

    /**
     * @return The code for a get helper method for a node type.
     */
    @NotNull
    private CodeBlock createGetMethodCode(ObjectField field, String selectionSetPath, boolean returnTypeIsRecord, boolean previousIsIterable) {
        var typeName = field.getTypeName();
        var code = CodeBlock.builder();

        code.beginControlFlow("if (!$N.contains($S))", VARIABLE_SELECT_NAME, selectionSetPath);
        if (previousIsIterable) {
            code.addStatement("return $T.of()", MAP.className);
        } else {
            code.addStatement("return null");
        }
        code.endControlFlow().add("\n");

        var querySource = asQueryClass(typeName);
        var querySourceName = uncapitalize(querySource);
        var queryMethod = asQueryNodeMethod(typeName);
        var idCall = returnTypeIsRecord ? ".getId()" : field.getMappingFromColumn().asGetCall() + ".getId()";
        if (previousIsIterable) {
            code.addStatement(
                    "var $L = $N.stream().map(it -> it$L).collect($T.toSet())",
                    VARIABLE_ID_NAME,
                    VARIABLE_RESULT_PARAM,
                    idCall,
                    COLLECTORS.className
            );
            code.addStatement(
                    "return $N.$L($N, $N.withPrefix($S))",
                    querySourceName,
                    queryMethod,
                    VARIABLE_ID_NAME,
                    VARIABLE_SELECT_NAME,
                    selectionSetPath
            );
        } else {
            code
                    .addStatement(
                            "var $L = $N.$L($T.of($N$L), $N.withPrefix($S))",
                            VARIABLE_NODE_RESULT_NAME,
                            querySourceName,
                            queryMethod,
                            SET.className,
                            VARIABLE_RESULT_PARAM,
                            idCall,
                            VARIABLE_SELECT_NAME,
                            selectionSetPath
                    )
                    .addStatement("return $N.values().stream().findFirst().orElse(null)", VARIABLE_NODE_RESULT_NAME);
        }
        dependencySet.add(new QueryDependency(querySource));
        return code.build();
    }

    /**
     * @return Inputs formatted as a get call, but without the get element of the string.
     */
    protected String asGetMethodVariableName(String fieldSourceTypeName, String fieldName) {
        return uncapitalize(fieldSourceTypeName) + capitalize(fieldName);
    }

    /**
     * @return Inputs formatted as a get call.
     */
    protected String asGetMethodName(String fieldSourceTypeName, String fieldName) {
        return VARIABLE_GET_PREFIX + fieldSourceTypeName + capitalize(fieldName);
    }

    /**
     * @return Field type formatted as a query method call.
     */
    protected String asQueryClass(String fieldType) {
        return fieldType + DBClassGenerator.FILE_NAME_SUFFIX;
    }

    /**
     * @return Field type formatted as a node interface method call.
     */
    protected String asQueryNodeMethod(String fieldType) {
        return "load" + fieldType + "ByIdsAs" + NODE_TYPE.getName();
    }

    /**
     * Look for class object of the type returned by the specified service. Throw exception if not found.
     */
    private void checkService(ObjectField target) {
        if (!localField.hasServiceReference()) {
            throw new IllegalStateException(
                    "Requested to generate a method for '"
                            + localField.getName()
                            + "' in type '"
                            + localObject.getName()
                            + "' without providing a service to call."
            );
        }

        var service = GeneratorService.valueOf(localField.getServiceReference()).getService();
        if (getServiceReturnMethod(target, service).isEmpty()) {
            var totalFields = countParams(target.getInputFields(), false);
            throw new IllegalStateException(
                    "Service '"
                            + service.getName()
                            + "' contains no method with the name '"
                            + target.getName()
                            + "' and "
                            + totalFields
                            + " parameter(s), which is required to generate the resolver."
            );
        }
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (localField.isGenerated()) {
            checkService(localField);
            return Stream.concat(Stream.of(generate(localField)), generateGetMethods(localField).stream()).collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public boolean generatesAll() {
        return localField.isGenerated() && localField.hasServiceReference();
    }

    /**
     * @return Input formatted as a list version of itself.
     */
    @NotNull
    private static String asListedName(String s) {
        return uncapitalize(s) + VARIABLE_LIST_SUFFIX;
    }

    /**
     * @return Input formatted as a result name.
     */
    @NotNull
    private static String asResultName(String s) {
        return uncapitalize(s) + VARIABLE_RESULT_SUFFIX;
    }

    /**
     * @return Input formatted as an iterable name.
     */
    @NotNull
    private static String asIterable(String s) {
        return VARIABLE_ITERATE_PREFIX + capitalize(s);
    }

    /**
     * @return Input formatted as an iterable result name.
     */
    @NotNull
    private static String asIterableResultName(String s) {
        return asIterable(asResultName(s));
    }

    /**
     * @return The spec for a parameter that holds the result of a query method call.
     */
    @NotNull
    private static ParameterSpec getServiceReturnParam(String serviceReturnTypeName, boolean isIterable) {
        var serviceReturnClassName = ClassName.get("", serviceReturnTypeName.replace("$", "."));
        var serviceReturnClassNameParam = isIterable
                ? ParameterizedTypeName.get(LIST.className, serviceReturnClassName)
                : serviceReturnClassName;

        return ParameterSpec.builder(serviceReturnClassNameParam, VARIABLE_RESULT_PARAM).build();
    }

    private static void recursionCheck(int recursion) {
        if (recursion == Integer.MAX_VALUE - 1) {
            throw new RuntimeException("Recursion depth has reached the integer max value.");
        }
    }
}
