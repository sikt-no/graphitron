package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.definitions.fields.FieldType;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.InputDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ServiceWrapper;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphitron.generators.db.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.dependencies.ContextDependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.codegenenums.GeneratorService;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.generatedModelsPackage;
import static no.fellesstudentsystem.graphitron.definitions.objects.ServiceWrapper.extractType;
import static no.fellesstudentsystem.graphitron.definitions.objects.ServiceWrapper.getServiceReturnClassName;
import static no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator.FILE_NAME_SUFFIX;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.context.UpdateContext.countParams;
import static no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.ERROR_TYPE;
import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for default update queries.
 */
public class UpdateResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    private static final String
            PARAM_ENV = "env",
            VARIABLE_ID = "ids",
            VARIABLE_RESULT_PARAM = "result",
            VARIABLE_NODE_RESULT = "nodes",
            VARIABLE_SELECT = "select",
            VARIABLE_EXCEPTION = "e",
            VARIABLE_ERROR = "error",
            VARIABLE_CAUSE_NAME = "causeName",
            VARIABLE_CAUSE = "cause",
            VARIABLE_RECORD_LIST = "recordList",
            VALUE_UNDEFINED = "undefined",
            FIELD_MESSAGE = "message", // Hardcoded expected fields.
            FIELD_MESSAGE_UPPER = capitalize(FIELD_MESSAGE),
            FIELD_PATH = "path", // Hardcoded expected fields.
            FIELD_PATH_UPPER = capitalize(FIELD_PATH),
            METHOD_GET_CAUSE = "getCauseField"; // Hardcoded method name. Perhaps it should be tied to an interface?

    private final Map<String, Class<?>> exceptionOverrides, serviceOverrides;

    private final ObjectField localField;
    private UpdateContext context;

    public UpdateResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        this(localField, processedSchema, Map.of(), Map.of());
    }

    public UpdateResolverMethodGenerator(
            ObjectField localField,
            ProcessedSchema processedSchema,
            Map<String, Class<?>> exceptionOverrides,
            Map<String, Class<?>> serviceOverrides
    ) {
        super(processedSchema.getMutationType(), processedSchema);
        dependencySet.add(ContextDependency.getInstance());
        this.localField = localField;
        this.exceptionOverrides = exceptionOverrides;
        this.serviceOverrides = serviceOverrides;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var spec = getDefaultSpecBuilder(target.getName(), target.getFieldType().getWrappedTypeClass(processedSchema.getObjects()));

        var allInputs = processedSchema.getInputTypes();
        var specInputs = target.getInputFields();
        specInputs.forEach(input -> spec.addParameter(input.getFieldType().getWrappedTypeClass(allInputs), input.getName()));

        context = new UpdateContext(target, processedSchema, exceptionOverrides, serviceOverrides);
        var code = CodeBlock.builder();
        if (target.hasServiceReference()) {
            code.addStatement("var $L = new $T($N.getSelectionSet())", VARIABLE_SELECT, SELECTION_SETS.className, PARAM_ENV);
        }
        code
                .add(declareRecords(specInputs))
                .add("\n")
                .add(generateServiceCall(target))
                .add(generateResponsesAndGetCalls(target));

        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, PARAM_ENV)
                .addCode(declareAllServiceClasses())
                .addCode(code.build())
                .build();
    }

    @Nullable
    private String getResolverResultName(ObjectField target) {
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
    private CodeBlock declareRecords(List<InputField> specInputs) {
        var code = CodeBlock.builder();
        var recordCode = CodeBlock.builder();

        var inputObjects = specInputs.stream().filter(it -> processedSchema.isInputType(it.getTypeName())).collect(Collectors.toList());
        for (var in : inputObjects) {
            code.add(declareRecords(in, 0));
            recordCode.add(fillRecords(in, "", 0));
        }
        if (!recordCode.isEmpty()) {
            code.add("\n").add(recordCode.build());
        } else if (!context.hasService()) {
            throw new UnsupportedOperationException("Must have at least one record reference when generating resolvers with queries. Mutation '" + localField.getName() + "' has no records attached.");
        }
        return code.build();
    }

    /**
     * @return List of variable names for the declared records.
     */
    @NotNull
    private CodeBlock declareRecords(InputField target, int recursion) {
        recursionCheck(recursion);

        var code = CodeBlock.builder();
        var input = processedSchema.getInputType(target.getTypeName());
        if (!input.hasTable()) {
            return code.build();
        }
        var targetAsRecordName = asRecordName(target.getName());

        var sqlRecordClassName = input.asSQLRecord().getGraphClassName();
        var isIterable = target.getFieldType().isIterableWrapped();
        if (isIterable) {
            code.addStatement("var $L = new $T<$T>()", asListedName(targetAsRecordName), ARRAY_LIST.className, sqlRecordClassName);
        } else {
            code.add(declareRecord(targetAsRecordName, sqlRecordClassName));
        }

        input
                .getInputs()
                .stream()
                .filter(in -> processedSchema.isInputType(in.getTypeName()))
                .forEach(in -> code.add(declareRecords(in, recursion + 1)));

        return code.build();
    }

    private static CodeBlock declareRecord(String recordName, ClassName sqlRecordClassName) {
        return CodeBlock
                .builder()
                .addStatement("var $L = new $T()", recordName, sqlRecordClassName)
                .addStatement("$N.attach($N.configuration())", recordName, Dependency.CONTEXT_NAME)
                .build();
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
        var recordName = hasTable ? asRecordName(targetName) : previousRecord;

        var code = CodeBlock.builder();

        var iterableInputName = targetName;
        String potentialArrayListName = "";
        var isIterable = target.getFieldType().isIterableWrapped();
        if (isIterable) {
            if (!hasTable) {
                return code.build();
            }

            potentialArrayListName = asListedName(recordName);
            iterableInputName = asIterable(iterableInputName);
            code
                    .add("\n")
                    .beginControlFlow("if ($N != null)", targetName)
                    .beginControlFlow("for (var $L : $N)", iterableInputName, targetName)
                    .addStatement("if ($N == null) continue", iterableInputName)
                    .add(declareRecord(recordName, input.asSQLRecord().getGraphClassName()));
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

        if (!code.isEmpty()) {
            code.addStatement(
                    "$T.setPersonKeysFromPlattformIds($N, $N)",
                    FIELD_HELPERS.className,
                    ContextDependency.CONTEXT_NAME,
                    isIterable ? potentialArrayListName : recordName
            );
        }

        return code.isEmpty() || isIterable ? code.build() : CodeBlock
                .builder()
                .beginControlFlow("if ($N != null)", iterableInputName)
                .add(code.build())
                .endControlFlow()
                .build();
    }

    private CodeBlock generateServiceCall(ObjectField target) {
        var useGeneratedQuery = !context.hasService();
        String objectToCall;
        if (useGeneratedQuery) {
            objectToCall = target.getName() + FILE_NAME_SUFFIX;
            dependencySet.add(new QueryDependency(capitalize(objectToCall), UpdateDBClassGenerator.SAVE_DIRECTORY_NAME));
        } else {
            var service = context.getService();
            objectToCall = service.getServiceName();
            dependencySet.add(new ServiceDependency(objectToCall, service.getPackageName()));
        }

        if (!context.hasErrors() || useGeneratedQuery) {
            return generateSimpleServiceCall(target, objectToCall);
        }

        var service = context.getService();
        var serviceResultName = asResultName(target.getUnprocessedNameInput());
        return CodeBlock
                .builder()
                .addStatement("$T $L = null", service.getReturnTypeName(), serviceResultName)
                .add(defineErrorLists())
                .beginControlFlow("try")
                .addStatement("$N = $N.$L($L)", serviceResultName, uncapitalize(objectToCall), target.getName(), context.getServiceInputString())
                .add(createCatchBlocks(target))
                .add("\n")
                .add(generateNullReturn(target))
                .add("\n")
                .build();
    }

    @NotNull
    private CodeBlock generateSimpleServiceCall(ObjectField target, String serviceObjectName) {
        return CodeBlock
                .builder()
                .addStatement(
                        "var $L = $N.$L($L)",
                        asResultName(target.getUnprocessedNameInput()),
                        uncapitalize(serviceObjectName),
                        target.getName(),
                        context.getServiceInputString()
                ) // Method name is expected to be the field's name.
                .build();
    }

    private CodeBlock defineErrorLists() {
        var code = CodeBlock.builder();
        for (var errorField : context.getAllErrors()) {
            var definition = context.getErrorTypeDefinition(errorField.getTypeName());
            code.addStatement(
                    "var $L = new $T<$T>()",
                    asListedName(definition.getName()),
                    ARRAY_LIST.className,
                    definition.getGraphClassName()
            );
        }
        return code.build();
    }

    private CodeBlock createCatchBlocks(ObjectField target) {
        var errorInterface = processedSchema.getInterface(ERROR_TYPE.getName());
        var hasPathField = errorInterface.hasField(FIELD_PATH);
        var preparedErrorsMap = hasPathField ? getFieldErrorMap(target) : CodeBlock.of("");
        var preparedCode = errorInterface.hasField(FIELD_MESSAGE) ? createPreparedMessageCode() : CodeBlock.of("");

        var code = CodeBlock.builder();
        for (var errorField : context.getAllErrors()) {
            var errorListName = asListedName(context.getErrorTypeDefinition(errorField.getTypeName()).getName());
            for (var exc : context.getExceptionDefinitions(errorField.getTypeName())) {
                var exception = context.getExceptionClass(exc.getExceptionReference());
                var exceptionJavaClassName = ClassName.get(exception.getPackageName(), exception.getSimpleName());
                code
                        .nextControlFlow("catch ($T $L)", exceptionJavaClassName, VARIABLE_EXCEPTION)
                        .addStatement("var $N = new $T()", VARIABLE_ERROR, exc.getGraphClassName())
                        .add(preparedCode);
                if (hasPathField) {
                    if (Stream.of(exception.getMethods()).map(Method::getName).anyMatch(it -> it.equals(METHOD_GET_CAUSE))) {
                        code
                                .add(preparedErrorsMap)
                                .addStatement(
                                        "$N.set$L($T.of(($S + $N).split($S)))",
                                        VARIABLE_ERROR,
                                        FIELD_PATH_UPPER,
                                        LIST.className,
                                        localObject.getName() + "." + target.getName() + ".",
                                        VARIABLE_CAUSE_NAME,
                                        "\\."
                                );
                    } else {
                        code.addStatement("$N.set$L($T.of($S))", VARIABLE_ERROR, FIELD_PATH_UPPER, LIST.className, target.getName());
                    }
                }
                code.addStatement("$N.add($N)", errorListName, VARIABLE_ERROR);
            }
        }

        return code.endControlFlow().build();
    }

    @NotNull
    private CodeBlock getFieldErrorMap(ObjectField target) {
        return CodeBlock
                .builder()
                .addStatement("var $L = $N.$L()", VARIABLE_CAUSE, VARIABLE_EXCEPTION, METHOD_GET_CAUSE)
                .addStatement(
                        "var $L = $T.of($L).getOrDefault($N != null ? $N : \"\", $S)",
                        VARIABLE_CAUSE_NAME,
                        MAP.className,
                        context.getFieldErrorNameSets(target),
                        VARIABLE_CAUSE,
                        VARIABLE_CAUSE,
                        VALUE_UNDEFINED
                )
                .build();
    }

    @NotNull
    private CodeBlock createPreparedMessageCode() {
        return CodeBlock
                .builder()
                .addStatement(
                    "$N.set$L($N.get$L())",
                        VARIABLE_ERROR,
                        FIELD_MESSAGE_UPPER,
                        VARIABLE_EXCEPTION,
                        FIELD_MESSAGE_UPPER
                )
                .build();
    }

    @NotNull
    private CodeBlock generateNullReturn(ObjectField target) {
        var resolverResultName = getResolverResultName(target);
        var code = CodeBlock
                .builder()
                .beginControlFlow("if ($N == null)", asResultName(target.getUnprocessedNameInput()))
                .addStatement("var $L = new $T()", resolverResultName, processedSchema.getObject(target.getTypeName()).getGraphClassName());

        for (var error : context.getAllErrors()) {
            code
                    .add("$N", resolverResultName)
                    .addStatement(
                            error.getMappingFromFieldName().asSetCall("$N"),
                            asListedName(context.getErrorTypeDefinition(error.getTypeName()).getName())
                    );
        }

        if (target.getFieldType().isIterableWrapped()) {
            code.addStatement("return $T.completedFuture($T.of($N))", COMPLETABLE_FUTURE.className, LIST.className, resolverResultName);
        } else {
            code.addStatement("return $T.completedFuture($N)", COMPLETABLE_FUTURE.className, resolverResultName);
        }

        return code.endControlFlow().build();
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    private CodeBlock generateResponsesAndGetCalls(ObjectField target) {
        var code = CodeBlock.builder();
        var typeName = target.getTypeName();
        if (processedSchema.isExceptionOrExceptionUnion(typeName)) {
            return code.build();
        }


        var hasService = context.hasService();
        if (hasService) {
            code
                    .add(generateResultUnpacking(target))
                    .add(generateGetCalls(target, target, 0));
        }

        var recordInputs = context
                .getServiceInputs()
                .entrySet()
                .stream()
                .filter(it -> Optional.ofNullable(processedSchema.getInputType(it.getValue().getName())).map(InputDefinition::hasTable).orElse(false))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var returnValue = !hasService && !processedSchema.isObject(target.getTypeName())
                ? CodeBlock.of("$L", getIDMappingCode(target, target, recordInputs))
                : CodeBlock.of("$N", getResolverResultName(target));
        return code
                .add(generateResponses(target, target, recordInputs, 0))
                .add("\n")
                .addStatement("return $T.completedFuture($L)", COMPLETABLE_FUTURE.className, returnValue)
                .build();
    }

    /**
     * @return Code that unpacks the result structure from a service.
     */
    private CodeBlock generateResultUnpacking(ObjectField target) {
        var code = CodeBlock.builder();

        if (!context.getService().isReturnTypeInService()) {
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
        if (!processedSchema.isObject(targetTypeName) || processedSchema.isExceptionOrExceptionUnion(targetTypeName)) {
            return code.build();
        }

        var responseObject = processedSchema.getObject(targetTypeName);
        if (responseObject.implementsInterface(NODE_TYPE.getName())) {
            return code.build();
        }

        if (previous != null) {
            var targetName = target.getUnprocessedNameInput();
            var previousName = previous.getUnprocessedNameInput();
            code.add("var $L = $N", asResultName(targetName), asResultName(previousName));
            if (previous.getFieldType().isIterableWrapped()) {
                code.addStatement(
                        ".stream().flatMap(it -> it$L.stream()).collect($T.toList())",
                        target.getMappingFromColumn().asGetCall(),
                        COLLECTORS.className
                );
            } else {
                code.addStatement("$L", target.getMappingFromColumn().asGetCall());
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
                            VARIABLE_SELECT
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
    private CodeBlock generateResponses(ObjectField target, ObjectField previous, Map<String, FieldType> recordInputs, int recursion) {
        recursionCheck(recursion);

        var code = CodeBlock.builder();
        var targetTypeName = target.getTypeName();
        if (!processedSchema.isObject(targetTypeName) || processedSchema.isExceptionOrExceptionUnion(targetTypeName)) {
            return code.build();
        }

        var responseObject = processedSchema.getObject(targetTypeName);
        if (responseObject.implementsInterface(NODE_TYPE.getName())) {
            return code.build();
        }

        var useGeneratedQuery = !context.hasService();
        var responseClassName = responseObject.getGraphClassName();
        var isIterable = target.getFieldType().isIterableWrapped();
        var targetTypeNameLower = uncapitalize(targetTypeName);
        var responseListName = asListedName(targetTypeNameLower);

        code.add("\n");

        if (isIterable) {
            CodeBlock iteration;
            String iterableName;
            if (useGeneratedQuery) {
                var iterationTarget = recordInputs.size() == 1 ? recordInputs.entrySet().stream().findFirst().get().getKey() : VARIABLE_RECORD_LIST;
                iteration = CodeBlock.of("$N", iterationTarget);
                iterableName = asIterable(iterationTarget);
            } else {
                iteration = previous.getFieldType().isIterableWrapped() && target != previous
                        ? CodeBlock.of("$N$L", asIterableResultName(previous.getUnprocessedNameInput()), target.getMappingFromColumn().asGetCall())
                        : CodeBlock.of("$N", asResultName(target.getUnprocessedNameInput()));
                iterableName = asIterableResultName(target.getUnprocessedNameInput());
            }

            code
                    .addStatement("var $L = new $T<$T>()", responseListName, ARRAY_LIST.className, responseClassName)
                    .beginControlFlow("for (var $L : $L)", iterableName, iteration);
        }

        code.addStatement("var $L = new $T()", targetTypeNameLower, responseClassName);

        if (useGeneratedQuery) {
            for (var field : responseObject.getFields()) {
                code.add(mapToRecordSetCall(field, target, recordInputs));
            }
        } else {
            for (var field : responseObject.getFields()) {
                code
                        .add(generateResponses(field, target, recordInputs, recursion + 1))
                        .add(mapToSetCall(field, target, recursion == 0)); // Only set exceptions on top layer, recursion isn't supported here.
            }
        }

        if (isIterable) {
            code.addStatement("$N.add($N)", responseListName, targetTypeNameLower).endControlFlow();
        }

        return code.build();
    }

    /**
     * @return Code for any set method calls for this response object.
     */
    private CodeBlock mapToRecordSetCall(ObjectField field, ObjectField previousField, Map<String, FieldType> recordInputs) {
        if (!field.getFieldType().isID()) {
            return CodeBlock.of(""); // mapToSimpleSetCall(field, previousTypeNameLower); // Not supported.
        }

        return CodeBlock
                .builder()
                .add("$N", uncapitalize(previousField.getTypeName()))
                .addStatement(field.getMappingFromFieldName().asSetCall("$L"), getIDMappingCode(field, previousField, recordInputs))
                .build();
    }

    @NotNull
    private CodeBlock getIDMappingCode(ObjectField field, ObjectField containerField, Map<String, FieldType> recordInputs) {
        var inputSource = localField
                .getInputFields()
                .stream()
                .filter(it -> it.getFieldType().isID())
                .findFirst();

        boolean containerIsIterable = containerField.getFieldType().isIterableWrapped(),
                isIterable = field.getFieldType().isIterableWrapped(),
                shouldMap;
        String idSource;
        if (inputSource.isPresent()) {
            var source = inputSource.get();
            shouldMap = isIterable || processedSchema.isInputType(source.getTypeName());
            idSource = source.getName();
        } else {
            var recordSource = recordInputs
                    .entrySet()
                    .stream()
                    .filter(it -> processedSchema.getInputType(it.getValue().getName()).getInputs().stream().anyMatch(f -> f.getFieldType().isID()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Could not find a suitable ID to return for '" + containerField.getName() + "'."));
            shouldMap = true;
            idSource = containerIsIterable && processedSchema.isObject(containerField.getTypeName()) ? asIterable(recordSource.getKey()) : recordSource.getKey();
        }

        var code = CodeBlock.builder().add("$N", idSource);
        if (shouldMap) {
            if (isIterable) {
                code.add(".stream().map(it -> it.getId()).collect($T.toList())", COLLECTORS.className);
            } else {
                code.add(".getId()");
            }
        }

        return code.build();
    }

    /**
     * @return Code for any set method calls for this response object.
     */
    private CodeBlock mapToSetCall(ObjectField field, ObjectField previousField, boolean setExceptions) {
        var fieldTypeName = field.getTypeName();
        var previousTypeNameLower = uncapitalize(previousField.getTypeName());

        if (setExceptions && processedSchema.isExceptionOrExceptionUnion(fieldTypeName)) {
            return mapToSimpleSetCall(field, previousTypeNameLower);
        }

        var code = CodeBlock.builder();
        if (processedSchema.isObject(fieldTypeName)) {
            code.add(mapToObjectSetCall(field, previousField));
        } else {
            code.addStatement(
                    "$N" + field.getMappingFromFieldName().asSetCall("$N" + field.getMappingFromColumn().asGetCall()),
                    previousTypeNameLower,
                    previousField.getFieldType().isIterableWrapped()
                            ? asIterableResultName(previousField.getUnprocessedNameInput())
                            : asResultName(previousField.getUnprocessedNameInput())
            );
        }
        return code.build();
    }

    @NotNull
    private CodeBlock mapToSimpleSetCall(ObjectField field, String previousTypeNameLower) {
        return CodeBlock
                .builder()
                .addStatement(
                        "$N" + field.getMappingFromFieldName().asSetCall("$N"),
                        previousTypeNameLower,
                        asListedName(context.getErrorTypeDefinition(field.getTypeName()).getName())
                )
                .build();
    }

    private CodeBlock mapToObjectSetCall(ObjectField field, ObjectField previousField) {
        var code = CodeBlock.builder().add("$N", uncapitalize(previousField.getTypeName()));

        var fieldName = field.getName();
        if (context.hasService() && processedSchema.getObject(field.getTypeName()).implementsInterface(NODE_TYPE.getName())) {
            code.add(mapToNodeSetCall(field, previousField));
        } else {
            code.addStatement(field.getMappingFromFieldName().asSetCall("$N"), field.getFieldType().isIterableWrapped() ? asListedName(fieldName) : fieldName);
        }
        return code.build();
    }

    private CodeBlock mapToNodeSetCall(ObjectField field, ObjectField previousField) {
        var code = CodeBlock.builder();
        var fieldMapping = field.getMappingFromFieldName();
        var previousTypeName = previousField.getTypeName();
        var getVariable = asGetMethodVariableName(previousTypeName, field.getName());

        if (previousField.getFieldType().isIterableWrapped()) {
            code.addStatement(
                    fieldMapping.asSetCall("$N.get($N.getId())"),
                    getVariable,
                    hasId(previousTypeName) ? uncapitalize(previousTypeName) : asIterableResultName(previousField.getUnprocessedNameInput())
            );
        } else {
            var service = context.getService();
            if (previousField == localField && !service.isReturnTypeInService() && service.returnIsIterable()) {
                code.addStatement(fieldMapping.asSetCall("new $T<>($N.values())"), ARRAY_LIST.className, getVariable);
            } else {
                code.addStatement(fieldMapping.asSetCall("$N"), getVariable);
            }
        }
        return code.build();
    }

    private boolean hasId(String typeName) {
        return processedSchema.isObject(typeName) && processedSchema
                .getObject(typeName)
                .getFields()
                .stream()
                .anyMatch(it -> it.getName().equalsIgnoreCase("Id"));
    }

    /**
     * Set up the generation of all the helper get methods for this schema tree. Searches recursively.
     *
     * @return The code for any get helper methods to be made for this schema tree.
     */
    private List<MethodSpec> generateGetMethods(ObjectField target) {
        if (!target.hasServiceReference()) {
            return List.of();
        }

        context = new UpdateContext(localField, processedSchema, exceptionOverrides, serviceOverrides);
        var responseObject = processedSchema.getObject(target.getTypeName());
        if (responseObject == null) {
            return List.of();
        }

        var service = context.getService();
        var serviceReturnType = service.getReturnType();
        return generateGetMethod(target, target, "", serviceReturnType, serviceReturnType, service.getInternalClasses());
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

        var service = context.getService();
        var previousIsIterable = previous.getFieldType().isIterableWrapped()
                || previous == localField && !service.isReturnTypeInService() && service.returnIsIterable();
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
                        .addParameter(
                                ParameterSpec
                                        .builder(getServiceReturnClassName(previousTypeClass.getName(), previousIsIterable), VARIABLE_RESULT_PARAM)
                                        .build()
                        )
                        .addParameter(SELECTION_SETS.className, VARIABLE_SELECT)
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

    /**
     * @return The code for a get helper method for a node type.
     */
    @NotNull
    private CodeBlock createGetMethodCode(ObjectField field, String selectionSetPath, boolean returnTypeIsRecord, boolean isIterable) {
        var typeName = field.getTypeName();
        var code = CodeBlock.builder();

        code.beginControlFlow("if (!$N.contains($S) || $N == null)", VARIABLE_SELECT, selectionSetPath, VARIABLE_RESULT_PARAM);
        if (isIterable) {
            code.addStatement("return $T.of()", MAP.className);
        } else {
            code.addStatement("return null");
        }
        code.endControlFlow().add("\n");

        var querySource = asQueryClass(typeName);
        var querySourceName = uncapitalize(querySource);
        var queryMethod = asQueryNodeMethod(typeName);
        var idCall = returnTypeIsRecord ? ".getId()" : field.getMappingFromColumn().asGetCall() + ".getId()";
        if (isIterable) {
            code.addStatement(
                    "var $L = $N.stream().map(it -> it$L).collect($T.toSet())",
                    VARIABLE_ID,
                    VARIABLE_RESULT_PARAM,
                    idCall,
                    COLLECTORS.className
            );
            code.addStatement(
                    "return $N.$L($N, $N.withPrefix($S))",
                    querySourceName,
                    queryMethod,
                    VARIABLE_ID,
                    VARIABLE_SELECT,
                    selectionSetPath
            );
        } else {
            code
                    .addStatement(
                            "var $L = $N.$L($T.of($N$L), $N.withPrefix($S))",
                            VARIABLE_NODE_RESULT,
                            querySourceName,
                            queryMethod,
                            SET.className,
                            VARIABLE_RESULT_PARAM,
                            idCall,
                            VARIABLE_SELECT,
                            selectionSetPath
                    )
                    .addStatement("return $N.values().stream().findFirst().orElse(null)", VARIABLE_NODE_RESULT);
        }
        dependencySet.add(new QueryDependency(querySource, SAVE_DIRECTORY_NAME));
        return code.build();
    }

    /**
     * Look for class object of the type returned by the specified service. Throw exception if not found.
     */
    private void checkService(ObjectField target) {
        Validate.isTrue(localField.hasServiceReference(),
                "Requested to generate a method for '%s' in type '%s' without providing a service to call.",
                localField.getName(), localObject.getName());

        var ref = localField.getServiceReference();
        Class<?> generatorService;
        if (!serviceOverrides.containsKey(ref)) {
            var service = EnumUtils.getEnum(GeneratorService.class, ref);
            Validate.isTrue(service != null,
                    "Requested to generate a method for '%s' that calls service '%s', but no such service was found in '%s'",
                    localField.getName(), localField.getServiceReference(), GeneratorService.class.getName());
            generatorService = service.getService();
        } else {
            generatorService = serviceOverrides.get(ref);
        }

        var service = new ServiceWrapper(target.getName(), countParams(target.getInputFields(), false, processedSchema), generatorService);
        Validate.isTrue(service.getMethod() != null,
                "Service '%s' contains no method with the name '%s' and %d parameter(s), which is required to generate the resolver.",
                generatorService.getName(), target.getName(), service.getParamCount());
    }

    @Override
    public List<MethodSpec> generateAll() {
        var hasService = localField.hasServiceReference();
        var isGenerated = localField.isGenerated();
        if (isGenerated && (localField.hasMutationType() || hasService)) {
            if (hasService) {
                checkService(localField);
            }
            return Stream.concat(Stream.of(generate(localField)), generateGetMethods(localField).stream()).collect(Collectors.toList());
        } else if (isGenerated) {
            throw new IllegalStateException("Mutation '" + localField.getName() + "' is set to generate, but has neither a service nor mutation type set.");
        }
        return List.of();
    }

    @Override
    public boolean generatesAll() {
        return localField.isGenerated() && (localField.hasMutationType() || localField.hasServiceReference());
    }

    private static void recursionCheck(int recursion) {
        if (recursion == Integer.MAX_VALUE - 1) {
            throw new RuntimeException("Recursion depth has reached the integer max value.");
        }
    }
}
