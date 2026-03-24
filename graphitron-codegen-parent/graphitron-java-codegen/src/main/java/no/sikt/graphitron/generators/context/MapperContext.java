package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.configuration.externalreferences.TransformScope;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.objects.RecordObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.Nullable;

import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.extractKeyAsTableRecord;
import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.getKeyTableRecordTypeName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asRecordName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.namedIteratorPrefixIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.*;
import static no.sikt.graphitron.generators.context.NodeIdReferenceHelpers.isNodeIdReferenceField;
import static no.sikt.graphitron.generators.context.NodeIdReferenceHelpers.resolveColumnNamesForNodeIdField;
import static no.sikt.graphitron.javapoet.CodeBlock.declareNew;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.ReflectionHelpers.classHasMethod;
import static no.sikt.graphitron.mappings.TableReflection.recordUsesFSHack;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_ID;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class MapperContext {
    private final GenerationField target;
    private final int recursion;
    private final RecordObjectDefinition<?, ?> targetType;
    private final boolean
            hasRecordReference,
            hasJavaRecordReference,
            hasTable,
            isIterable,
            wasIterable,
            toRecord,
            mapsJavaRecord,
            isValidation,
            createsDataFetchers,
            targetIsType,
            isInitContext,
            pastFieldOverrideExists,
            noRecordIterability,
            isSimpleIDMode,
            lastInputDeclarationWasIterable;
    private final String sourceName, targetName, path, indexPath, lastIterableIndexName;
    private final MethodMapping getSourceMapping, setTargetMapping, lastRecordMapping;
    private final MapperContext previousContext;
    private final ProcessedSchema schema;

    // Initial context values. Only some of the initial values here are used.
    private MapperContext(boolean toRecord, boolean mapsJavaRecord, boolean isValidation, boolean createsDataFetchers, ProcessedSchema schema) {
        recursion = 0;
        path = "";
        indexPath = "";
        lastIterableIndexName = null;
        isIterable = false;
        wasIterable = false;
        lastInputDeclarationWasIterable = true;
        targetIsType = false;
        sourceName = "";
        targetName = "";
        lastRecordMapping = null;
        getSourceMapping = null;
        setTargetMapping = null;
        previousContext = null;
        targetType = null;
        hasRecordReference = false;
        hasJavaRecordReference = false;
        hasTable = false;
        isSimpleIDMode = false;
        target = null;
        this.toRecord = toRecord;
        this.mapsJavaRecord = mapsJavaRecord;
        this.isValidation = isValidation;
        this.createsDataFetchers = createsDataFetchers;
        this.schema = schema;

        isInitContext = true;
        pastFieldOverrideExists = false;
        noRecordIterability = false;
    }

    private MapperContext(GenerationField target, MapperContext previousContext) {
        isInitContext = false;
        this.previousContext = previousContext;
        this.schema = previousContext.schema;
        this.mapsJavaRecord = previousContext.mapsJavaRecord;
        this.toRecord = previousContext.toRecord;
        this.isValidation = previousContext.isValidation;
        this.createsDataFetchers = previousContext.createsDataFetchers;
        this.target = target;
        this.targetType = (RecordObjectDefinition<?, ?>)schema.getRecordType(target);

        pastFieldOverrideExists = previousContext.pastFieldOverrideExists || (previousContext.target != null && previousContext.target.hasSetFieldOverride());
        targetIsType = targetType != null;
        hasRecordReference = targetIsType && targetType.hasRecordReference();
        hasTable = targetIsType && targetType.hasTable();
        hasJavaRecordReference = targetIsType && targetType.hasJavaRecordReference();
        recursion = previousContext.recursion + 1;
        isIterable = createsDataFetchers ? target.isIterableWrapped() : (target.isIterableWrapped() && !hasJavaRecordReference || previousContext.isInitContext);
        wasIterable = isIterable || previousContext.wasIterable;
        isSimpleIDMode = !previousContext.hasRecordReference && !toRecord && !target.isInput() && target.isID(); // Special case, may become unsupported in the future.

        var schemaNameToUse = getSchemaNameToUse();
        var recordName = getRecordName();

        sourceName = select(schemaNameToUse, recordName);
        targetName = select(recordName, schemaNameToUse);

        var schemaMethodMapping = target.getMappingFromSchemaName();
        var recordMappingHere = getNextRecordMapping();
        lastRecordMapping = recordMappingHere;
        getSourceMapping = select(schemaMethodMapping, recordMappingHere);
        setTargetMapping = select(recordMappingHere, schemaMethodMapping);

        path = getNextPath();
        indexPath = getNextIndexPath();
        lastIterableIndexName = isIterable ? getIndexName() : previousContext.lastIterableIndexName;
        noRecordIterability = targetIsType && sourceName.isEmpty() && target.isIterableWrapped();

        lastInputDeclarationWasIterable = previousContext.lastInputDeclarationWasIterable && !target.isIterableWrapped() || previousContext.isInitContext;
    }

    private @Nullable MethodMapping getNextRecordMapping() {
        if (previousContext.isInitContext || (!target.hasSetFieldOverride() && previousContext.lastRecordMapping != null && pastFieldOverrideExists)) {
            return previousContext.lastRecordMapping;
        }

        // FS HACK - Account for get/set ID methods with an underscore at the end...
        if (previousContext.targetIsType && previousContext.targetType.hasTable() && recordUsesFSHack(previousContext.targetType.getTable().getName()) && target.getName().equals(NODE_ID.getName())) {
            return new MethodMapping(target.getName() + "_");
        }

        return target.getJavaRecordMethodMapping(mapsJavaRecord);
    }

    private String getSchemaNameToUse() {
        if (createsDataFetchers && toRecord) {
            return uncapitalize(target.getName());
        }

        if (previousContext.isInitContext || createsDataFetchers) {
            return uncapitalize(schema.isRecordType(target) ? schema.getRecordType(target).getName() : target.getTypeName());
        }

        if (mapsJavaRecord) {
            return uncapitalize(target.getName());
        }

        return uncapitalize(previousContext.targetType.getName() + "_" + uncapitalize(target.getName()));
    }

    private String getRecordName() {
        if (createsDataFetchers && !toRecord) {
            return uncapitalize(target.getTypeName());
        }

        return (mapsJavaRecord ? hasJavaRecordReference : hasTable)
                ? uncapitalize(targetType.asRecordName())
                : select(previousContext.targetName, previousContext.sourceName);
    }

    private String getNextPath() {
        if (previousContext.isInitContext) {
            return createsDataFetchers && toRecord ? target.getName() : "";
        }

        if (previousContext.path.isEmpty()) {
            return target.getName();
        }

        return previousContext.path + "/" + target.getName();
    }

    private String getNextIndexPath() {
        if (previousContext.isInitContext) {
            return createsDataFetchers && toRecord ? target.getName() : "";
        }

        if (previousContext.indexPath.isEmpty() && !previousContext.isIterable) {
            return target.getName();
        }

        var indexElement = previousContext.isIterable ? (previousContext.indexPath.isEmpty() ? "" : "/\" + ") + previousContext.getIndexName() + " + \"/" : "/";
        return previousContext.indexPath + indexElement + target.getName();
    }

    public RecordObjectDefinition<?, ?> getTargetType() {
        return targetType;
    }

    public boolean hasRecordReference() {
        return hasRecordReference;
    }

    public boolean hasJavaRecordReference() {
        return hasJavaRecordReference;
    }

    public boolean hasTable() {
        return hasTable;
    }

    public GenerationField getTarget() {
        return target;
    }

    public MapperContext getPreviousContext() {
        return previousContext;
    }

    public boolean isIterable() {
        return isIterable;
    }

    public boolean wasIterable() {
        return wasIterable;
    }

    public boolean isTopLevelContext() {
        return previousContext != null && previousContext.isInitContext;
    }

    public boolean targetIsType() {
        return targetIsType;
    }

    public String getPath() {
        return path;
    }

    public String getFieldName() {
        return target.getName();
    }

    public String getIndexPath() {
        return indexPath;
    }

    public String getSourceName() {
        return sourceName;
    }

    public boolean hasSourceName() {
        return !sourceName.isEmpty();
    }

    public String getTargetName() {
        return targetName;
    }

    private String getIndexName() {
        return namedIndexIteratorPrefix(sourceName);
    }

    public boolean targetCanNotBeMapped() {
        return previousContext.hasRecordReference && !isMappingPossible() && (!targetIsType || targetType.hasRecordReference());
    }

    public boolean variableNotAlreadyDeclared() {
        return targetIsType && !sourceIsEqualToPreviousSource() && sourceHasRequiredMethod();
    }

    private boolean sourceIsEqualToPreviousSource() {
        var secondLastContext = previousContext.previousContext;
        if (secondLastContext == null) {
            return false;
        }
        return previousContext.sourceName.equals(secondLastContext.sourceName);
    }

    public CodeBlock getSourceGetCallBlock() {
        if (isSimpleIDMode) {
            return CodeBlock.of(inputPrefix(asRecordName(previousContext.targetName)));
        }

        if (!toRecord && schema.isNodeIdField(target)) {
            var nodeConfig = schema.getNodeConfigurationForTypeOrThrow(target.getContainerTypeName());
            return createNodeIdBlockForRecord(nodeConfig, namedIteratorPrefixIf(previousContext.sourceName, previousContext.isIterable));
        }

        return getValue(
                (!isValidation || toRecord) && previousContext.isIterable || !toRecord && previousContext.lastInputDeclarationWasIterable
                        ? namedIteratorPrefix(previousContext.sourceName)
                        : inputPrefix(previousContext.sourceName), getSourceMapping
        );
    }

    public MapperContext iterateContext(GenerationField field) {
        recursionCheck(recursion);
        return new MapperContext(field, this);
    }

    public String getInputVariableName() {
        return inputPrefix(select(targetType.getName(), targetType.asRecordName()));
    }

    public String getOutputName() {
        return select(targetType.asRecordName(), targetType.getName());
    }

    public String getHelperVariableName() {
        return mapsJavaRecord ? getSourceMapping.getName() : (previousContext.targetType.getName() + "_" + uncapitalize(setTargetMapping.getName()));
    }

    public ClassName getReturnType() {
        return targetType.asTargetClassName(toRecord);
    }

    public CodeBlock wrapFields(CodeBlock fieldCode) {
        if (fieldCode.isEmpty()) {
            return CodeBlock.empty();
        }

        var targetEqualsPrevious = targetName.equals(previousContext.targetName);
        var code = CodeBlock.builder();
        if (isIterable && hasSourceName()) {
            code.declareIf(createsDataFetchers || isValidation || toRecord, namedIteratorPrefix(sourceName), "$N.get($N)", inputPrefix(sourceName), getIndexName());

            if (toRecord && !createsDataFetchers && !isValidation) {
                code.declare(VAR_ARGS, "$N.$L($N)", VAR_ARG_PRESENCE, METHOD_ITEM_AT, getIndexName());
            }

            if (!isValidation) {
                code.add(continueCheck(namedIteratorPrefix(sourceName)));

                if (!createsDataFetchers && !mapsJavaRecord && !targetEqualsPrevious) {
                    code.add(select(declareRecord(outputPrefix(targetName), targetType, false, false), declareNew(outputPrefix(targetName), targetType.getGraphClassName())));
                }
            } else if (previousContext.isInitContext) {
                code.declareNew(VAR_PATHS_FOR_PROPERTIES, ParameterizedTypeName.get(HASH_MAP.className, STRING.className, STRING.className));
            }
        }

        code
                .declareNewIf(!isValidation && mapsJavaRecord && !targetEqualsPrevious, outputPrefix(targetName), targetType.asTargetClassName(toRecord))
                .add(fieldCode);

        if (createsDataFetchers) {
            return isIterable ? wrapForIndexed(sourceName, code.build()) : code.build();
        }

        if (hasSourceName()) {
            if (!isValidation && isIterable && (!mapsJavaRecord || hasRecordReference)) {
                code.add(CodeBlock.statementOf("$N.add($N)", listedOutputPrefix(targetName), outputPrefix(targetName)));
            } else if (isValidation && previousContext.isInitContext) {
                code.addStatement("$N.addAll($T.validatePropertiesAndGenerateGraphQLErrors($N, $N, $N))", VAR_VALIDATION_ERRORS, RECORD_VALIDATOR.className, namedIteratorPrefixIf(sourceName, isIterable), VAR_PATHS_FOR_PROPERTIES, VAR_ENV);
            }
        }

        var forCode = CodeBlock.builder().add(isIterable && hasSourceName() ? (isValidation || toRecord ? wrapForIndexed(sourceName, code.build()) : wrapFor(sourceName, code.build())) : code.build());
        if (isValidation || !previousContext.isInitContext && (toRecord || !mapsJavaRecord)) {
            return forCode.build();
        }

        if (!previousContext.isInitContext) {
            return forCode.add(getSetMappingBlock(outputPrefix(targetName))).build();
        }

        return CodeBlock
                .builder()
                .add(hasSourceName() ? wrapNotNull(inputPrefix(sourceName), forCode.build()) : forCode.build())
                .addIf(toRecord && !mapsJavaRecord, () -> applyGlobalTransforms(targetName, targetType.getRecordClassName(), TransformScope.ALL_MUTATIONS)) // Note: This is done after records are filled.
                .build();
    }

    public CodeBlock getSetMappingBlock(CodeBlock valueToSet) {
        if (schema.isNodeIdField(target) && toRecord && !mapsJavaRecord) {
            var nodeConfiguration = schema.getNodeConfigurationForNodeIdFieldOrThrow(target);
            var currentTable = schema.getRecordType(target.getContainerTypeName()).getTable();
            var isReference = isNodeIdReferenceField(target, schema, currentTable);
            var nodeIdFields = tableFieldsWithStaticTableInstanceBlock(
                    currentTable.getName(),
                    resolveColumnNamesForNodeIdField(target, schema, currentTable)
            );

            return CodeBlock.statementOf("$N.$L($N, $L, $S, $L)",
                    VAR_NODE_STRATEGY,
                    isReference ? METHOD_SET_RECORD_REFERENCE_ID : METHOD_SET_RECORD_ID,
                    outputPrefix(previousContext.targetName),
                    valueToSet,
                    nodeConfiguration.typeId(),
                    nodeIdFields
            );
        }
        return setValue(outputPrefix(previousContext.targetName), setTargetMapping, valueToSet, target.createsDataFetcher());
    }

    public CodeBlock getSetMappingBlock(String valueToSet) {
        return getSetMappingBlock(CodeBlock.of(valueToSet));
    }

    public CodeBlock getFieldSetMappingBlock() {
        CodeBlock valueToSet = applyEnumConversion(target.getTypeName(), getSourceGetCallBlock());
        if (isIterable && !targetIsType && !mapsJavaRecord) { // Array field
            valueToSet = toRecord
                    ? CodeBlock.of("$L.stream().toArray($T[]::new)", valueToSet, target.getTypeClass())
                    : CodeBlock.of("$T.of($L)", LIST.className, valueToSet);
        }
        return getSetMappingBlock(valueToSet);
    }

    public CodeBlock getResolverKeySetMappingBlockForJooqRecord() {
        return getResolverKeySetMappingBlock(namedIteratorPrefixIf(previousContext.sourceName, previousContext.isIterable), false);
    }

    public CodeBlock getResolverKeySetMappingBlock(String varName) {
        return getResolverKeySetMappingBlock(varName, target.isIterableWrapped());
    }

    private CodeBlock getResolverKeySetMappingBlock(String varName, boolean isKeyList) {
        var recordClass = getKeyTableRecordTypeName(target, schema);

        if (isKeyList) {
            return getSetMappingBlock(CodeBlock.of("$N.stream().map($N -> $L)$L",
                    varName, VAR_ITERATOR, extractKeyAsTableRecord(VAR_ITERATOR, recordClass), collectToList())
            );
        }

        return getSetMappingBlock(extractKeyAsTableRecord(varName, recordClass));
    }

    public CodeBlock getRecordSetMappingBlock() {
        return getSetMappingBlock(transformRecord());
    }

    public CodeBlock applyEnumConversion(String typeName, CodeBlock getCall) {
        return schema.isEnum(typeName) ? toGraphEnumConverter(typeName, getCall, toRecord, schema) : getCall;
    }

    public CodeBlock getReturnBlock() {
        var name = select(targetType.getRecordReferenceName(), targetType.getName());
        return returnWrap(hasSourceName() || noRecordIterability ? listedOutputPrefix(name) : outputPrefix(name));
    }

    /**
     * @return CodeBlock for the mapping of a record. Includes path for validation.
     */
    public CodeBlock transformInputRecord() {
        return CodeBlock.of(
                "$L$L$L$S$L)",
                recordTransformPart(sourceName, targetType.getName()),
                CodeBlock.ofIf(shouldMakeNodeStrategy(), "$N, ", VAR_NODE_STRATEGY),
                CodeBlock.of("$N.$L().$L($S), ", VAR_TRANSFORMER, METHOD_ARG_PRESENCE_NAME, METHOD_CHILD, target.getName()),
                path,
                CodeBlock.ofIf(recordValidationEnabled() && !hasJavaRecordReference, ", \"$L\"", indexPath)
        );
    }

    /**
     * @return CodeBlock for the mapping of a record.
     */
    private CodeBlock transformRecord() {
        var recordPart = recordTransformPart(
                previousContext.hasSourceName() ? getHelperVariableName() : asRecordName(previousContext.getTargetName()),
                uncapitalize(targetType.getName())
        );
        var nodeStrategyPart = CodeBlock.ofIf(shouldMakeNodeStrategy(), "$N, ", VAR_NODE_STRATEGY);
        var argPresencePart = toRecord
                ? CodeBlock.of("$N.$L($S), ", VAR_ARGS, METHOD_CHILD, target.getName())
                : CodeBlock.empty();
        var pathPart = toRecord && previousContext.lastIterableIndexName != null
                ? CodeBlock.of("$N + $S + $N + $S", VAR_PATH_NAME, "[", previousContext.lastIterableIndexName, "]/" + path)
                : CodeBlock.of("$N + $S", VAR_PATH_HERE, path);
        var validationPart = CodeBlock.ofIf(recordValidationEnabled() && !hasJavaRecordReference && toRecord, ", $N + $S", VAR_PATH_HERE, path);
        return CodeBlock.join(recordPart, nodeStrategyPart, argPresencePart, pathPart, validationPart, CodeBlock.of(")"));
    }

    private CodeBlock recordTransformPart(String varName, String typeName) {
        return FormatCodeBlocks.recordTransformPart(VAR_TRANSFORMER, inputPrefix(varName), typeName, hasJavaRecordReference, toRecord);
    }

    private boolean isMappingPossible() {
        return sourceHasRequiredMethod() && targetHasRequiredMethod();
    }

    private boolean sourceHasRequiredMethod() {
        // Assume the schema ones are OK anyway. It is done like this because these classes are not defined in tests.
        return toRecord || classHasMethod(previousContext.targetType.getRecordReference(), getSourceMapping.asGet());
    }

    private boolean targetHasRequiredMethod() {
        // Assume the schema ones are OK anyway. It is done like this because these classes are not defined in tests.
        return !toRecord || classHasMethod(previousContext.targetType.getRecordReference(), setTargetMapping.asSet());
    }

    /**
     * @return The object that should be used based on whether this mapper maps to or from a record.
     */
    protected <O> O select(O obj0, O obj1) {
        return toRecord ? obj0 : obj1;
    }

    public static MapperContext createContext(GenerationField target, boolean toRecord, boolean mapsJavaRecord, ProcessedSchema schema) {
        return new MapperContext(toRecord, mapsJavaRecord, false, false, schema).iterateContext(target);
    }

    public static MapperContext createResolverContext(GenerationField target, boolean toRecord, ProcessedSchema schema) {
        return new MapperContext(toRecord, false, false, true, schema).iterateContext(target);
    }

    public static MapperContext createValidationContext(GenerationField target, ProcessedSchema schema) {
        return new MapperContext(false, false, true, false, schema).iterateContext(target);
    }
}
