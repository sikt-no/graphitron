package no.fellesstudentsystem.graphitron.generators.resolvers.update;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.COLLECTORS;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.LIST;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.ERROR_TYPE;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for update queries with the {@link GenerationDirective#SERVICE}  directive set.
 */
public class ServiceUpdateResolverMethodGenerator extends UpdateResolverMethodGenerator {
    private static final String
            FIELD_PATH = "path", // Hardcoded expected fields.
            FIELD_PATH_UPPER = capitalize(FIELD_PATH),
            FIELD_MESSAGE = "message", // Hardcoded expected fields.
            FIELD_MESSAGE_UPPER = capitalize(FIELD_MESSAGE),
            VARIABLE_CAUSE_NAME = "causeName",
            VARIABLE_EXCEPTION = "e",
            VARIABLE_ERROR = "error",
            VARIABLE_CAUSE = "cause",
            VALUE_UNDEFINED = "undefined";


    public ServiceUpdateResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
    }

    protected CodeBlock generateUpdateMethodCall(ObjectField target) {
        var service = context.getService();
        var dependency = new ServiceDependency(service.getServiceClassName());
        dependencySet.add(dependency);

        var objectToCall = uncapitalize(dependency.getName());
        var serviceResultName = asResultName(target.getName());
        var serviceMethod = service.getMethod();
        var methodName = uncapitalize(serviceMethod != null ? serviceMethod.getName() : target.getName());

        if (!context.hasErrorsToHandle()) {
            return declare(serviceResultName, generateServiceCall(methodName, objectToCall));
        }
        return CodeBlock
                .builder()
                .addStatement("$T $L = null", service.getReturnTypeName(), serviceResultName)
                .add(defineErrorLists())
                .beginControlFlow("try")
                .addStatement("$N = $L", serviceResultName, generateServiceCall(methodName, objectToCall))
                .add(createCatchBlocks(target))
                .add("\n")
                .add(generateNullReturn(target))
                .add("\n")
                .build();
    }

    @NotNull
    private CodeBlock generateServiceCall(String methodName, String serviceObjectName) {
        return CodeBlock.of("$N.$L($L)", serviceObjectName, methodName, context.getServiceInputString());
    }

    private CodeBlock defineErrorLists() {
        var code = CodeBlock.builder();
        for (var errorField : context.getAllErrors()) {
            var definition = context.getProcessedSchema().getErrorTypeDefinition(errorField.getTypeName());
            code.add(declareArrayList(definition.getName(), definition.getGraphClassName()));
        }
        return code.build();
    }

    private CodeBlock createCatchBlocks(ObjectField target) {
        var errorInterface = processedSchema.getInterface(ERROR_TYPE.getName());
        var hasPathField = errorInterface.hasField(FIELD_PATH);
        var preparedCode = errorInterface.hasField(FIELD_MESSAGE) ? createPreparedMessageCode() : empty();
        var externalReferences = GeneratorConfig.getExternalReferences();

        var code = CodeBlock.builder();
        for (var errorField : context.getAllErrors()) {
            var errorListName = asListedName(context.getProcessedSchema().getErrorTypeDefinition(errorField.getTypeName()).getName());
            for (var exc : context.getProcessedSchema().getExceptionDefinitions(errorField.getTypeName())) {
                var reference = exc.getExceptionReference();

                if (reference == null) break; //TODO tmp solution to skip exceptions handled by "MutationExceptionStrategy"

                var exception = externalReferences.getClassFrom(reference);
                code
                        .nextControlFlow("catch ($T $L)", ClassName.get(exception), VARIABLE_EXCEPTION)
                        .add(declareVariable(VARIABLE_ERROR, exc.getGraphClassName()))
                        .add(preparedCode);
                if (hasPathField) {
                    if (Stream.of(exception.getMethods()).map(Method::getName).anyMatch(it -> it.equals(reference.getMethodName()))) {
                        code
                                .add(getFieldErrorMap(target, reference.getMethodName()))
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
    private CodeBlock getFieldErrorMap(ObjectField target, String methodName) {
        return CodeBlock
                .builder()
                .add(declare(VARIABLE_CAUSE, asMethodCall(VARIABLE_EXCEPTION, methodName)))
                .add(
                        declare(
                                VARIABLE_CAUSE_NAME,
                                CodeBlock.of(
                                        "$L.getOrDefault($N != null ? $N : \"\", $S)",
                                        mapOf(CodeBlock.of(context.getProcessedSchema().getFieldErrorNameSets(target))),
                                        VARIABLE_CAUSE,
                                        VARIABLE_CAUSE,
                                        VALUE_UNDEFINED

                                )
                        )
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
                .beginControlFlow("if ($N == null)", asResultName(target.getName()))
                .add(declareVariable(resolverResultName, processedSchema.getObject(target).getGraphClassName()));

        for (var error : context.getAllErrors()) {
            code.add(
                    setValue(
                            resolverResultName,
                            error.getMappingFromSchemaName(),
                            asListedName(context.getProcessedSchema().getErrorTypeDefinition(error.getTypeName()).getName())
                    )
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
    protected CodeBlock generateResponses(ObjectField target) {
        var code = CodeBlock.builder();
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return code.build();
        }

        return code
                .add(generateResponses(MapperContext.createResolverContext(target, false, processedSchema)))
                .add(returnCompletedFuture(getResolverResultName(target)))
                .build();
    }

    /**
     * @return Code for adding error types and calling transform methods.
     */
    protected CodeBlock generateResponses(MapperContext mapperContext) {
        if (!mapperContext.targetIsType()) {
            return empty();
        }

        var code = CodeBlock.builder();
        if (mapperContext.hasRecordReference() && mapperContext.isTopLevelContext()) {
            code.add(declare(asListedNameIf(mapperContext.getTargetName(), mapperContext.isIterable()), mapperContext.getRecordTransform(mapperContext.getTarget().getName())));
        } else if (!mapperContext.hasRecordReference()) {
            code.add(declareVariable(mapperContext.getTargetName(), mapperContext.getTargetType().getGraphClassName()));
        }

        code.add("\n");

        for (var innerField : mapperContext.getTargetType().getFields()) {
            var innerContext = mapperContext.iterateContext(innerField);
            var previousTarget = innerContext.getPreviousContext().getTarget();

            var innerCode = CodeBlock.builder();
            if (innerContext.shouldUseException()) {
                if (context.hasErrorsToHandle()) {
                    innerCode.add(innerContext.getSetMappingBlock(asListedName(processedSchema.getErrorTypeDefinition(innerField.getTypeName()).getName())));
                }
            } else if (!innerField.isExplicitlyNotGenerated() && !innerContext.getPreviousContext().hasRecordReference()) {
                if (!innerContext.targetIsType()) {
                    innerCode.add(innerContext.getSetMappingBlock(getFieldSetContent((ObjectField) innerField, (ObjectField) previousTarget)));
                } else if (innerContext.shouldUseStandardRecordFetch()) {
                    innerCode.add(innerContext.getRecordSetMappingBlock(previousTarget.getName()));
                } else if (innerContext.hasRecordReference()) {
                    innerCode.add(innerContext.getSetMappingBlock(createIdFetch(innerField, previousTarget.getName(), innerContext.getPath(), true))); // TODO: Should be done outside for? Preferably devise some general dataloader-like solution applying to query classes.
                } else {
                    innerCode.add(generateResponses(innerContext));
                }
            }

            if (!innerCode.isEmpty()) {
                code
                        .beginControlFlow("if ($N != null && $L)", previousTarget.getName(), selectionSetLookup(innerContext.getPath(), true, false))
                        .add(innerCode.build())
                        .endControlFlow()
                        .add("\n");
            }
        }

        return code.add("\n").build();
    }

    @NotNull
    private CodeBlock getFieldSetContent(ObjectField field, ObjectField previousField) {
        var resultName = asIterableResultNameIf(previousField.getUnprocessedFieldOverrideInput(), previousField.isIterableWrapped());

        var service = context.getService();
        var returnIsMappable = service.getReturnType().getName().endsWith(RECORD_NAME_SUFFIX) || service.returnsJavaRecord();
        if (processedSchema.isObject(previousField) && returnIsMappable) {
            var getMapping = field.getMappingForJOOQFieldOverride();
            var extractValue = field.isIterableWrapped() && !previousField.isIterableWrapped();
            if (extractValue) {
                var iterationName = asIterable(field.getName());
                return CodeBlock.of("$N.stream().map($L -> $L).collect($T.toList())", resultName, iterationName, getValue(iterationName, getMapping), COLLECTORS.className);
            } else {
                return getValue(resultName, getMapping);
            }
        }

        return CodeBlock.of("$N", resultName);
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (localField.isGenerated()) {
            if (localField.hasServiceReference()) {
                return List.of(generate(localField));
            } else if (!localField.hasMutationType()) {
                throw new IllegalStateException("Mutation '" + localField.getName() + "' is set to generate, but has neither a service nor mutation type set.");
            }
        }
        return List.of();
    }
}
