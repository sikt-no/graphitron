package no.sikt.graphitron.generators.abstractions;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.helpers.ServiceWrapper;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.generators.dependencies.ServiceDependency;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.MappingCodeBlocks.inputTransform;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_PAGE_INFO_NODE;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class contains common information and operations shared by resolver method generators.
 */
abstract public class ResolverMethodGenerator extends AbstractSchemaMethodGenerator<ObjectField, ObjectDefinition> {
    protected static final String LOOKUP_KEYS_NAME = "keys", RESPONSE_NAME = "response";

    public ResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    protected TypeName getReturnTypeName(ObjectField referenceField) {
        if (!referenceField.hasForwardPagination()) {
            return wrapListIf(getTypeName(referenceField), referenceField.isIterableWrapped());
        }
        return processedSchema.getConnectionObject(referenceField).getGraphClassName();
    }

    private TypeName getTypeName(GenerationField field) {
        if (processedSchema.isRecordType(field)) {
            return processedSchema.getRecordType(field).getGraphClassName();
        }
        if (processedSchema.isInterface(field)) {
            return processedSchema.getInterface(field).getGraphClassName();
        }
        if (processedSchema.isUnion(field)) {
            return processedSchema.getUnion(field).getGraphClassName();
        }
        if (processedSchema.isEnum(field)) {
            return processedSchema.getEnum(field).getGraphClassName();
        }
        return field.getTypeClass();
    }

    protected ServiceDependency createServiceDependency(GenerationField target) {
        var dependency = new ServiceDependency(new ServiceWrapper(target, processedSchema.getObject(target)));
        dependencySet.add(dependency);
        return dependency;
    }

    protected CodeBlock queryMethodCall(ObjectField target, InputParser parser) {
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
                    .add(join(queryFunction, transformWrap))
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
                .add(" $N, $L,\n$L,\n$L$L,\n$L", PAGE_SIZE_NAME, GeneratorConfig.getMaxAllowedPageSize(), queryFunction, countFunction, transformWrap, connectionFunction)
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
            code.add("$S, $N.getId(),", queryMethodName, uncapitalize(localObject.getName()));
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

    /**
     * @return CodeBlock for declaring the transformer class and calling it on each record input.
     */
    protected CodeBlock transformInputs(ObjectField field, InputParser parser) {
        if (!parser.hasRecords()) {
            if (field.hasServiceReference()) {
                return declareTransform();
            }

            return empty();
        }

        return inputTransform(field.getNonReservedArguments(), processedSchema);
    }

    /**
     * @return CodeBlock for declaring the transformer class and calling it on each record input.
     */
    protected CodeBlock transformInputs(List<? extends InputField> inputs, boolean hasRecords) {
        if (!hasRecords) {
            return declareTransform();
        }

        return inputTransform(inputs, processedSchema);
    }
}
