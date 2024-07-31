package no.fellesstudentsystem.graphitron.generators.resolvers.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.fields.OrderByEnumField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.InputParser;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphql.helpers.queries.LookupHelpers;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.MappingCodeBlocks.inputTransform;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.CONNECTION_PAGE_INFO_NODE;
import static org.apache.commons.lang3.StringUtils.capitalize;
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
        if (processedSchema.isInterface(target)) {
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
                .add(fetcherCodeInit(target, queryMethodName, localObject, isService ? newDataFetcherWithTransform() : newDataFetcher()));

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

        var orderField = target.getOrderField().map(AbstractField::getName);
        var filteredInputs = parser
                .getMethodInputsWithOrderField()
                .keySet()
                .stream()
                .filter(it -> orderField.map(orderByField -> !orderByField.equals(it)).orElse(true))
                .collect(Collectors.joining(", "));
        var inputsWithId = localObject.isOperationRoot() ? filteredInputs : (filteredInputs.isEmpty() ? IDS_NAME : IDS_NAME + ", " + filteredInputs);
        var countFunction = countFunction(objectToCall, method, inputsWithId, isService);
        var connectionFunction = connectionFunction(processedSchema.getConnectionObject(target), processedSchema.getObject(CONNECTION_PAGE_INFO_NODE.getName()));
        return dataBlock
                .add("$N, $L,\n$L,\n$L,\n$L$L,\n$L", PAGE_SIZE_NAME, GeneratorConfig.getMaxAllowedPageSize(), queryFunction, countFunction, getIDFunction(target), transformWrap, connectionFunction)
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
     * @return CodeBlock consisting of a function for a getId call.
     */
    public CodeBlock getIDFunction(ObjectField referenceField) {
        return referenceField
                .getOrderField()
                .map(orderInputField -> {
                    var objectNode = processedSchema.getObjectOrConnectionNode(referenceField);

                    var orderByFieldMapEntries = processedSchema.getOrderByFieldEnum(orderInputField)
                            .getFields()
                            .stream()
                            .map(orderByField -> CodeBlock.of("$S, $L -> $L",
                                    orderByField.getName(), VARIABLE_TYPE_NAME, createJoinedGetFieldAsStringCallBlock(orderByField, objectNode)))
                            .collect(CodeBlock.joining(",\n"));

                    return CodeBlock.builder()
                            .add("(it) -> $N == null ? it.getId() :\n", orderInputField.getName())
                            .indent()
                            .indent()
                            .add("$T.<$T, $T<$T, $T>>of(\n",
                                    MAP.className, STRING.className, FUNCTION.className, objectNode.getGraphClassName(), STRING.className)
                            .indent()
                            .indent()
                            .add("$L\n", orderByFieldMapEntries)
                            .unindent()
                            .unindent()
                            .add(").get($L.get$L().toString()).apply(it)", orderInputField.getName(), capitalize(GraphQLReservedName.ORDER_BY_FIELD.getName()))
                            .unindent()
                            .unindent()
                            .build();
                })
                .orElseGet(() -> CodeBlock.of("(it) -> it.getId()")); //Note: getID is FS-specific.
    }

    private CodeBlock createJoinedGetFieldAsStringCallBlock(OrderByEnumField orderByField, ObjectDefinition objectNode) {
        return orderByField
                .getSchemaFieldsWithPathForIndex(processedSchema, objectNode)
                .entrySet()
                .stream()
                .map(fieldWithPath -> createNullSafeGetFieldAsStringCall(fieldWithPath.getKey(), fieldWithPath.getValue()))
                .collect(CodeBlock.joining(" + \",\" + "));
    }

    private static CodeBlock createNullSafeGetFieldAsStringCall(ObjectField field,  List<String> path) {
        var getFieldCall = field.getMappingFromSchemaName().asGetCall();
        var fullCallBlock = CodeBlock.of("$L$L$L", VARIABLE_TYPE_NAME, path.stream().map(it -> new MethodMapping(it).asGetCall()).collect(CodeBlock.joining("")), getFieldCall);

        if (field.getTypeClass() != null &&
                (field.getTypeClass().isPrimitive() || field.getTypeClass().equals(STRING.className))) {
            return fullCallBlock;
        }
        return CodeBlock.of("$L == null ? null : $L.toString()", fullCallBlock, fullCallBlock);
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
                .filter(it -> !it.code.isEmpty())
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
