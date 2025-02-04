package no.sikt.graphitron.generators.resolvers.fetch;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.generators.dependencies.ServiceDependency;
import no.sikt.graphql.helpers.queries.LookupHelpers;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.MappingCodeBlocks.inputTransform;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.DATA_FETCHING_ENVIRONMENT;
import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_PAGE_INFO_NODE;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for default fetch queries with potential arguments or pagination.
 */
public class FetchResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    private static final String LOOKUP_KEYS_NAME = "keys", RESPONSE_NAME = "response";

    public FetchResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        if (!generationCondition(target)) {
            return MethodSpec.methodBuilder(target.getName()).build();
        }

        var parser = new InputParser(target, processedSchema);
        var methodCall = queryMethodCall(target, parser); // Note, do this before declaring services.
        return getSpecWithParams(target)
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addCode(transformInputs(target, parser))
                .addCode(declareAllServiceClasses())
                .addCode(methodCall)
                .build();
    }

    protected boolean generationCondition(GenerationField target) {
        if (processedSchema.isInterface(target) && target.getTypeName().equals(NODE_TYPE.getName())) {
            return false;
        }

        if (target.hasServiceReference()) {
            return !LookupHelpers.lookupExists((ObjectField) target, processedSchema);
        }

        return true;
    }

    private MethodSpec.Builder getSpecWithParams(ObjectField target) {
        var spec = getDefaultSpecBuilder(target.getName(), getReturnTypeName(target));

        var localObject = getLocalObject();
        if (!localObject.isOperationRoot()) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }

        target.getArguments().forEach(input -> spec.addParameter(iterableWrapType(input), input.getName()));
        if (target.hasForwardPagination()) {
            spec.addCode(declarePageSize(target.getFirstDefault()));
        }
        return spec;
    }

    /**
     * @return CodeBlock for declaring the transformer class and calling it on each record input.
     */
    private CodeBlock transformInputs(ObjectField field, InputParser parser) {
        if (!parser.hasRecords()) {
            if (field.hasServiceReference()) {
                return declareTransform();
            }

            return empty();
        }

        return inputTransform(field.getNonReservedArguments(), processedSchema);
    }

    private CodeBlock queryMethodCall(ObjectField target, InputParser parser) {
        var isService = target.hasServiceReference();
        var isRoot = localObject.isOperationRoot();
        var hasLookup = !isService && LookupHelpers.lookupExists(target, processedSchema);

        var dependency = isService ? createServiceDependency(target) : null;
        var methodName = isService ? dependency.getService().getMethodName() : asQueryMethodName(target.getName(), localObject.getName());
        var objectToCall = isService ? uncapitalize(dependency.getName()) : asQueryClass(localObject.getName());
        var queryFunction = queryFunction(objectToCall, methodName, parser.getInputParamString(), !isRoot || hasLookup, !isRoot && !hasLookup, isService);

        if (hasLookup) { // Assume all keys are correlated.
            return CodeBlock
                    .builder()
                    .add(declare(LOOKUP_KEYS_NAME, LookupHelpers.getLookupKeysAsList(target, processedSchema)))
                    .addStatement("return $L.$L($N, $L)", newDataFetcher(), "loadLookup", LOOKUP_KEYS_NAME, queryFunction)
                    .build();
        }
        return callQueryBlock(target, objectToCall, methodName, parser, queryFunction);
    }

    private CodeBlock callQueryBlock(ObjectField target, String objectToCall, String method, InputParser parser, CodeBlock queryFunction) {
        var isService = target.hasServiceReference();
        var queryMethodName = asQueryMethodName(target.getName(), localObject.getName());
        var dataBlock = CodeBlock
                .builder()
                .add(fetcherCodeInit(target, queryMethodName, localObject, isService ? newServiceDataFetcherWithTransform() : newDataFetcher()));

        var object = processedSchema.getObjectOrConnectionNode(target);
        var transformFunction = isService && object != null
                ? CodeBlock.of("($L, $L) -> $L", TRANSFORMER_NAME, RESPONSE_NAME, transformRecord(RESPONSE_NAME, object.getName(), "", object.hasJavaRecordReference()))
                : empty();
        var transformWrap = transformFunction.isEmpty() ? empty() : CodeBlock.of(",\n$L", transformFunction);
        if (!target.hasRequiredPaginationFields()) {
            if (!localObject.isOperationRoot()) {
                dataBlock.add("\n");
            }
            return dataBlock
                    .add("$L$L", queryFunction, transformWrap)
                    .unindent()
                    .unindent()
                    .addStatement(")")
                    .build();
        }

        var filteredInputs = parser
                .getMethodInputsWithOrderField()
                .keySet()
                .stream()
                .filter(it -> target.getOrderField().map(orderByField -> !orderByField.getName().equals(it)).orElse(true))
                .collect(Collectors.joining(", "));
        var inputsWithId = localObject.isOperationRoot() ? filteredInputs : (filteredInputs.isEmpty() ? IDS_NAME : IDS_NAME + ", " + filteredInputs);
        var countFunction = countFunction(objectToCall, method, inputsWithId, isService);
        var connectionFunction = connectionFunction(processedSchema.getConnectionObject(target), processedSchema.getObject(CONNECTION_PAGE_INFO_NODE.getName()));
        return dataBlock
                .add("$N, $L,\n$L,\n$L$L,\n$L", PAGE_SIZE_NAME, GeneratorConfig.getMaxAllowedPageSize(), queryFunction, countFunction, transformWrap, connectionFunction)
                .unindent()
                .unindent()
                .addStatement(")")
                .build();
    }

    private static CodeBlock fetcherCodeInit(ObjectField target, String queryMethodName, RecordObjectSpecification<?> localObject, CodeBlock fetcher) {
        var methodName = !localObject.isOperationRoot() && target.isIterableWrapped() && target.isNonNullable()
                ? "loadNonNullable"
                : target.hasForwardPagination() ? "loadPaginated" : "load";
        var code = CodeBlock
                .builder()
                .add("return $L.$L(\n", fetcher, methodName)
                .indent()
                .indent();
        if (!localObject.isOperationRoot()) {
            code.add("$S, $N.getId(), ", queryMethodName, uncapitalize(localObject.getName()));
        }
        return code.build();
    }

    /**
     * @return Code that declares any service dependencies set for this generator.
     */
    protected CodeBlock declareAllServiceClasses() {
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

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFields()
                .stream()
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(this::generationCondition)
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject()
                .getFields()
                .stream()
                .filter(this::generationCondition);
        return getLocalObject().isOperationRoot()
                ? fieldStream.allMatch(GenerationField::isGeneratedWithResolver)
                : fieldStream.allMatch(f -> (!f.isResolver() || f.isGeneratedWithResolver()));
    }
}
