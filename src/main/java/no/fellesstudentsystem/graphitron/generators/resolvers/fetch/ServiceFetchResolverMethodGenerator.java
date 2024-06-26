package no.fellesstudentsystem.graphitron.generators.resolvers.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.ServiceWrapper;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.RecordObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.codebuilding.ServiceCodeBlocks;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.helpers.queries.LookupHelpers;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ServiceCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.DATA_FETCHING_ENVIRONMENT;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for queries with the {@link GenerationDirective#SERVICE} directive set.
 */
public class ServiceFetchResolverMethodGenerator extends FetchResolverMethodGenerator {
    private static final String RESPONSE_NAME = "response";
    private ServiceWrapper service;

    public ServiceFetchResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        service = target.hasServiceReference() ? new ServiceWrapper(target, processedSchema) : null;
        if (LookupHelpers.lookupExists(target, processedSchema) || service == null) {
            return MethodSpec.methodBuilder(target.getName()).build();
        }

        var dependency = new ServiceDependency(service.getServiceClassName());
        dependencySet.add(dependency);
        var spec = getDefaultSpecBuilder(target.getName(), getReturnTypeName(target));

        var localObject = getLocalObject();
        if (!localObject.isOperationRoot()) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }

        var objectToCall = uncapitalize(dependency.getName());
        var serviceMethod = service.getMethod();
        var methodName = uncapitalize(serviceMethod != null ? serviceMethod.getName() : target.getName());

        var allQueryInputs = getQueryInputs(spec, target);
        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addCode(declareAllServiceClasses())
                .addCode("\n")
                .addCode(transformInputs(target.getNonReservedArgumentsWithOrderField()))
                .addCode(queryMethodCalls(target, objectToCall, methodName, allQueryInputs))
                .build();
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
                .forEach(dep -> code.add(dep.getDeclarationCode()));
        return code.build();
    }

    @NotNull
    private CodeBlock queryMethodCalls(ObjectField target, String objectToCall, String serviceMethod, ArrayList<String> allQueryInputs) {
        var localObject = getLocalObject();
        var isRoot = localObject.isOperationRoot();

        var inputString = String.join(", ", allQueryInputs);
        var queryFunction = queryFunction(objectToCall, serviceMethod, inputString, !isRoot, !isRoot, true);
        var object = processedSchema.getObjectOrConnectionNode(target);
        var transformFunction = object != null
                ? CodeBlock.of("($L, $L) -> $L", TRANSFORMER_NAME, RESPONSE_NAME, transformRecord(RESPONSE_NAME, object.getName(), "", object.hasJavaRecordReference()))
                : empty();
        return callQueryBlock(target, objectToCall, serviceMethod, allQueryInputs, localObject, queryFunction, transformFunction, true, processedSchema);
    }

    /**
     * @return CodeBlock for declaring the transformer class and calling it on each record input.
     */
    @NotNull
    protected CodeBlock transformInputs(List<? extends InputField> specInputs) {
        if (specInputs.stream().filter(processedSchema::isInputType).map(processedSchema::getInputType).anyMatch(RecordObjectDefinition::hasTable)) {
            return empty();
        }

        return inputTransform(specInputs, processedSchema);
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    protected CodeBlock generateSchemaOutputs(ObjectField target) {
        var code = CodeBlock.builder();
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return code.build();
        }

        var mapperContext = MapperContext.createResolverContext(target, false, processedSchema);
        return code
                .add(ServiceCodeBlocks.generateSchemaOutputs(mapperContext, false, service, processedSchema)) // Errors not handled for fetch yet.
                .add(returnCompletedFuture(getResolverResultName(target, processedSchema)))
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it))
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(GenerationField::hasServiceReference)
                .map(this::generate)
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it))
                .filter(GenerationField::hasServiceReference);
        return getLocalObject().isOperationRoot()
                ? fieldStream.allMatch(GenerationField::isGeneratedWithResolver)
                : fieldStream.filter(GenerationField::hasServiceReference).allMatch(f -> !f.isResolver() || f.isGeneratedWithResolver());
    }

}
