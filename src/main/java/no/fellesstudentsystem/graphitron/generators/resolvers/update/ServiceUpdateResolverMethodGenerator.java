package no.fellesstudentsystem.graphitron.generators.resolvers.update;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.codebuilding.ServiceCodeBlocks;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asListedName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asResultName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ServiceCodeBlocks.getResolverResultName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.LIST;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.ERROR_TYPE;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for update queries with the {@link GenerationDirective#SERVICE} directive set.
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
        var returnType = serviceMethod != null ? ClassName.get(serviceMethod.getGenericReturnType()) : service.getReturnType();

        if (!context.hasErrorsToHandle()) {
            return declare(serviceResultName, generateServiceCall(methodName, objectToCall));
        }
        return CodeBlock
                .builder()
                .addStatement("$T $L = null", returnType, serviceResultName)
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
        var resolverResultName = getResolverResultName(target, processedSchema);
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
    protected CodeBlock generateSchemaOutputs(ObjectField target) {
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return empty();
        }

        var mapperContext = MapperContext.createResolverContext(target, false, processedSchema);
        registerQueryDependencies(mapperContext);
        return CodeBlock
                .builder()
                .add(ServiceCodeBlocks.generateSchemaOutputs(mapperContext, context.hasErrorsToHandle(), context.getService(), processedSchema))
                .add(returnCompletedFuture(getResolverResultName(target, processedSchema)))
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (localField.isGeneratedWithResolver()) {
            if (localField.hasServiceReference()) {
                return List.of(generate(localField));
            } else if (!localField.hasMutationType()) {
                throw new IllegalStateException("Mutation '" + localField.getName() + "' is set to generate, but has neither a service nor mutation type set.");
            }
        }
        return List.of();
    }
}
