package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.FieldType;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ServiceWrapper;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.context.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.context.UpdateContext.countParams;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.ERROR_TYPE;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for update queries with the service directive set.
 */
public class ServiceUpdateResolverMethodGenerator extends UpdateResolverMethodGenerator {
    private static final String
            FIELD_PATH = "path", // Hardcoded expected fields.
            FIELD_PATH_UPPER = capitalize(FIELD_PATH),
            FIELD_MESSAGE = "message", // Hardcoded expected fields.
            FIELD_MESSAGE_UPPER = capitalize(FIELD_MESSAGE),
            VARIABLE_CAUSE_NAME = "causeName",
            VARIABLE_SELECT = "select",
            VARIABLE_EXCEPTION = "e",
            VARIABLE_ERROR = "error",
            VARIABLE_CAUSE = "cause",
            METHOD_GET_CAUSE = "getCauseField", // Hardcoded method name. Perhaps it should be tied to an interface?
            VALUE_UNDEFINED = "undefined";


    public ServiceUpdateResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
    }

    protected CodeBlock generateUpdateMethodCall(ObjectField target) {
        var service = context.getService();
        var objectToCall = service.getServiceName();
        dependencySet.add(new ServiceDependency(service));

        if (!context.hasErrors()) {
            return generateSimpleServiceCall(target, objectToCall);
        }
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
            code.add(declareArrayList(definition.getName(), definition.getGraphClassName()));
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
                var exception = GeneratorConfig.getExternalExceptions().get(exc.getExceptionReference());
                code
                        .nextControlFlow("catch ($T $L)", ClassName.get(exception), VARIABLE_EXCEPTION)
                        .add(declareVariable(VARIABLE_ERROR, exc.getGraphClassName()))
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
                code.add(addToList(errorListName, VARIABLE_ERROR));
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
                        "var $L = $L.getOrDefault($N != null ? $N : \"\", $S)",
                        VARIABLE_CAUSE_NAME,
                        mapOf(CodeBlock.of(context.getFieldErrorNameSets(target))),
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
                .add(declareVariable(resolverResultName, processedSchema.getObject(target).getGraphClassName()));

        for (var error : context.getAllErrors()) {
            code
                    .add("$N", resolverResultName)
                    .addStatement(
                            error.getMappingFromFieldName().asSetCall("$N"),
                            asListedName(context.getErrorTypeDefinition(error.getTypeName()).getName())
                    );
        }

        return code
                .add(target.isIterableWrapped() ? returnCompletedFuture(listOf(resolverResultName)) : returnCompletedFuture(resolverResultName))
                .endControlFlow()
                .build();
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    protected CodeBlock generateResponsesAndGetCalls(ObjectField target) {
        var code = CodeBlock.builder();
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return code.build();
        }

        return code
                .add(generateResultUnpacking(target))
                .add(super.generateResponsesAndGetCalls(target))
                .add(returnCompletedFuture(getResolverResultName(target)))
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

        if (!fieldIsMappable(target)) {
            return CodeBlock.of("");
        }

        var responseObject = processedSchema.getObject(target);

        var code = CodeBlock.builder();
        if (previous != null) {
            var targetName = target.getUnprocessedNameInput();
            var previousName = previous.getUnprocessedNameInput();
            code.add("var $L = $N", asResultName(targetName), asResultName(previousName));
            if (previous.isIterableWrapped()) {
                code
                        .add(".stream().flatMap(it -> it$L.stream())", target.getMappingFromColumn().asGetCall())
                        .addStatement(collectToList());
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
    protected CodeBlock generateGetCalls(ObjectField target, ObjectField previous, int recursion) {
        recursionCheck(recursion);

        var code = CodeBlock.builder();
        if (!processedSchema.isObject(target)) {
            return code.build();
        }

        var responseObject = processedSchema.getObject(target);
        if (responseObject.implementsInterface(NODE_TYPE.getName())) {
            return code
                    .addStatement(
                            "var $L = $N($N, $N, $N)",
                            asGetMethodVariableName(previous.getTypeName(), target.getName()),
                            asGetMethodName(previous.getTypeName(), target.getName()),
                            Dependency.CONTEXT_NAME,
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
    protected CodeBlock generateResponses(ObjectField target, ObjectField previous, int recursion) {
        recursionCheck(recursion);

        if (!fieldIsMappable(target)) {
            return CodeBlock.of("");
        }

        var responseObject = processedSchema.getObject(target);

        var targetTypeName = target.getTypeName();
        var responseClassName = responseObject.getGraphClassName();
        var fieldIterable = target.isIterableWrapped();
        var responseListName = asListedName(targetTypeName);

        var code = CodeBlock.builder().add("\n");
        if (fieldIterable) {
            var iteration = previous.isIterableWrapped() && target != previous
                    ? CodeBlock.of("$N$L", asIterableResultName(previous.getUnprocessedNameInput()), target.getMappingFromColumn().asGetCall())
                    : CodeBlock.of("$N", asResultName(target.getUnprocessedNameInput()));

            code
                    .add(declareArrayList(targetTypeName, responseClassName))
                    .beginControlFlow("for (var $L : $L)", asIterableResultName(target.getUnprocessedNameInput()), iteration);
        }

        var targetTypeNameLower = uncapitalize(targetTypeName);
        code.add(declareVariable(targetTypeNameLower, responseClassName));

        for (var field : responseObject.getFields()) {
            code
                    .add(generateResponses(field, target, recursion + 1))
                    .add(mapToSetCall(field, target, recursion == 0)); // Only set exceptions on top layer, recursion isn't supported here.
        }

        if (fieldIterable) {
            code.add(addToList(responseListName, targetTypeNameLower)).endControlFlow();
        }

        return code.build();
    }

    /**
     * @return Code for any set method calls for this response object.
     */
    private CodeBlock mapToSetCall(ObjectField field, ObjectField previousField, boolean setExceptions) {
        if (setExceptions && processedSchema.isExceptionOrExceptionUnion(field.getTypeName())) {
            return mapToSimpleSetCall(field, uncapitalize(previousField.getTypeName()));
        }

        return processedSchema.isObject(field)
                ? mapToObjectSetCall(field, previousField)
                : mapToFieldSetCall(field, previousField);
    }

    @NotNull
    private CodeBlock mapToFieldSetCall(ObjectField field, ObjectField previousField) {
        var getCode = CodeBlock
                .builder()
                .add("$N", asIterableResultNameIf(previousField.getUnprocessedNameInput(), previousField.isIterableWrapped()));

        var service = context.getService();
        var returnIsMappable = service.getReturnType().getName().endsWith(RECORD_NAME_SUFFIX) || service.isReturnTypeInService();
        if (processedSchema.isObject(previousField) && returnIsMappable) {
            var getCall = field.getMappingFromColumn().asGetCall();
            if (field.isIterableWrapped() && !previousField.isIterableWrapped()) {
                var iterationName = asIterable(field.getName());
                getCode.add(".stream().map($L -> $N" + getCall + ").collect($T.toList())", iterationName, iterationName, COLLECTORS.className);
            } else {
                getCode.add(getCall);
            }
        }

        return CodeBlock
                .builder()
                .add("$N", uncapitalize(previousField.getTypeName()))
                .addStatement(field.getMappingFromFieldName().asSetCall("$L"), getCode.build())
                .build();
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

        if (processedSchema.getObject(field).implementsInterface(NODE_TYPE.getName())) {
            return code.add(mapToNodeSetCall(field, previousField)).build();
        }

        return code
                .addStatement(field.getMappingFromFieldName().asSetCall("$N"), asListedNameIf(field.getTypeName(), field.isIterableWrapped()))
                .build();
    }

    private CodeBlock mapToNodeSetCall(ObjectField field, ObjectField previousField) {
        var fieldMapping = field.getMappingFromFieldName();
        var previousTypeName = previousField.getTypeName();
        var getVariable = asGetMethodVariableName(previousTypeName, field.getName());

        var code = CodeBlock.builder();
        if (previousField.isIterableWrapped()) {
            var idSourceName = hasId(previousTypeName) ? uncapitalize(previousTypeName) : asIterableResultName(previousField.getUnprocessedNameInput());
            return code.addStatement(fieldMapping.asSetCall("$N.get($N.getId())"), getVariable, idSourceName).build();
        } else {
            var service = context.getService();
            if (previousField == localField && !service.isReturnTypeInService() && service.returnIsIterable()) {
                return code.addStatement(fieldMapping.asSetCall("new $T<>($N.values())"), ARRAY_LIST.className, getVariable).build();
            }
        }

        return code.addStatement(fieldMapping.asSetCall("$N"), getVariable).build();
    }

    private boolean hasId(String typeName) {
        return processedSchema.isObject(typeName) && processedSchema
                .getObject(typeName)
                .getFields()
                .stream()
                .map(AbstractField::getFieldType)
                .anyMatch(FieldType::isID);
    }

    /**
     * Set up the generation of all the helper get methods for this schema tree. Searches recursively.
     *
     * @return The code for any get helper methods to be made for this schema tree.
     */
    protected List<MethodSpec> generateGetMethods(ObjectField target) {
        if (!processedSchema.isObject(target)) {
            return List.of();
        }

        context = new UpdateContext(localField, processedSchema);
        var service = context.getService();
        var serviceReturnType = service.getReturnType();
        return generateGetMethod(target, target, "", serviceReturnType, serviceReturnType, service.getInternalClasses());
    }

    /**
     * Look for class object of the type returned by the specified service. Throw exception if not found.
     */
    private void checkService(ObjectField target) {
        Validate.isTrue(localField.hasServiceReference(),
                "Requested to generate a method for '%s' in type '%s' without providing a service to call.",
                localField.getName(), localObject.getName());

        var ref = localField.getServiceReference();
        var services = GeneratorConfig.getExternalServices();
        Validate.isTrue(services.contains(ref),
                "Requested to generate a method for '%s' that calls service '%s', but no such service was found.",
                localField.getName(), localField.getServiceReference());
        var generatorService = services.get(ref);

        var service = new ServiceWrapper(target.getName(), countParams(target.getInputFields(), false, processedSchema), generatorService);
        Validate.isTrue(service.getMethod() != null,
                "Service '%s' contains no method with the name '%s' and %d parameter(s), which is required to generate the resolver.",
                generatorService.getName(), target.getName(), service.getParamCount());
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (localField.isGenerated()) {
            if (localField.hasServiceReference()) {
                checkService(localField);
                return Stream.concat(Stream.of(generate(localField)), generateGetMethods(localField).stream()).collect(Collectors.toList());
            } else if (!localField.hasMutationType()) {
                throw new IllegalStateException("Mutation '" + localField.getName() + "' is set to generate, but has neither a service nor mutation type set.");
            }
        }
        return List.of();
    }
}
