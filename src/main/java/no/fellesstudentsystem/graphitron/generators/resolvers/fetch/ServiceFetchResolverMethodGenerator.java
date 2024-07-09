package no.fellesstudentsystem.graphitron.generators.resolvers.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.ServiceWrapper;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.context.InputParser;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.helpers.queries.LookupHelpers;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.MappingCodeBlocks.callQueryBlock;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.TRANSFORMER_NAME;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.DATA_FETCHING_ENVIRONMENT;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for queries with the {@link GenerationDirective#SERVICE} directive set.
 */
public class ServiceFetchResolverMethodGenerator extends FetchResolverMethodGenerator {
    private static final String RESPONSE_NAME = "response";

    public ServiceFetchResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        parser = new InputParser(target, processedSchema);
        if (LookupHelpers.lookupExists(target, processedSchema) || !target.hasServiceReference()) {
            return MethodSpec.methodBuilder(target.getName()).build();
        }

        var service = new ServiceWrapper(target, processedSchema);
        var dependency = new ServiceDependency(service.getServiceClassName());
        dependencySet.add(dependency);
        var spec = getSpecWithParams(target);

        var objectToCall = uncapitalize(dependency.getName());
        var serviceMethod = service.getMethod();
        var methodName = uncapitalize(serviceMethod != null ? serviceMethod.getName() : target.getName());

        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addCode(transformInputs(target))
                .addCode(declareAllServiceClasses())
                .addCode(queryMethodCalls(target, objectToCall, methodName))
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
    private CodeBlock queryMethodCalls(ObjectField target, String objectToCall, String serviceMethod) {
        var localObject = getLocalObject();
        var isRoot = localObject.isOperationRoot();

        var queryFunction = queryFunction(objectToCall, serviceMethod, parser.getInputParamString(), !isRoot, !isRoot, true);
        var object = processedSchema.getObjectOrConnectionNode(target);
        var transformFunction = object != null
                ? CodeBlock.of("($L, $L) -> $L", TRANSFORMER_NAME, RESPONSE_NAME, transformRecord(RESPONSE_NAME, object.getName(), "", object.hasJavaRecordReference()))
                : empty();
        return callQueryBlock(target, objectToCall, serviceMethod, parser, localObject, queryFunction, transformFunction, true, processedSchema);
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
