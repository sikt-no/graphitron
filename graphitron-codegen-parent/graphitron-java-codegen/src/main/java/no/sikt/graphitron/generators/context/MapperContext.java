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
import org.jooq.Field;

import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.findKeyForResolverField;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.context.JooqRecordReferenceHelpers.getForeignKeyForNodeIdReference;
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
            isResolver,
            targetIsType,
            isInitContext,
            pastFieldOverrideExists,
            noRecordIterability,
            isSimpleIDMode;
    private final String sourceName, targetName, path, indexPath;
    private final MethodMapping getSourceMapping, setTargetMapping, lastRecordMapping;
    private final MapperContext previousContext;
    private final ProcessedSchema schema;

    // Initial context values. Only some of the initial values here are used.
    private MapperContext(boolean toRecord, boolean mapsJavaRecord, boolean isValidation, boolean isResolver, ProcessedSchema schema) {
        recursion = 0;
        path = "";
        indexPath = "";
        isIterable = false;
        wasIterable = false;
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
        this.isResolver = isResolver;
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
        this.isResolver = previousContext.isResolver;
        this.target = target;
        this.targetType = (RecordObjectDefinition<?, ?>)schema.getRecordType(target);

        pastFieldOverrideExists = previousContext.pastFieldOverrideExists || (previousContext.target != null && previousContext.target.hasSetFieldOverride());
        targetIsType = targetType != null;
        hasRecordReference = targetIsType && targetType.hasRecordReference();
        hasTable = targetIsType && targetType.hasTable();
        hasJavaRecordReference = targetIsType && targetType.hasJavaRecordReference();
        recursion = previousContext.recursion + 1;
        isIterable = isResolver ? target.isIterableWrapped() : (target.isIterableWrapped() && !hasJavaRecordReference || previousContext.isInitContext);
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
        noRecordIterability = targetIsType && sourceName.isEmpty() && target.isIterableWrapped();
    }

    private @Nullable MethodMapping getNextRecordMapping() {
        if (previousContext.isInitContext || (!target.hasSetFieldOverride() && previousContext.lastRecordMapping != null && pastFieldOverrideExists)) {
            return previousContext.lastRecordMapping;
        }

        // FS HACK - Account for get/set ID methods with an underscore at the end...
        if (previousContext.targetIsType && previousContext.targetType.hasTable() && recordUsesFSHack(previousContext.targetType.getTable().getName()) && target.getName().equals(NODE_ID.getName())) {
            return new MethodMapping(target.getName() + "_");
        }

        return mapsJavaRecord ? target.getMappingFromFieldOverride() : target.getMappingForRecordFieldOverride();
    }

    private String getSchemaNameToUse() {
        if (isResolver && toRecord) {
            return uncapitalize(target.getName());
        }

        if (previousContext.isInitContext || isResolver) {
            return uncapitalize(schema.isRecordType(target) ? schema.getRecordType(target).getName() : target.getTypeName());
        }

        if (mapsJavaRecord) {
            return uncapitalize(target.getName());
        }

        return uncapitalize(previousContext.targetType.getName() + "_" + uncapitalize(target.getName()));
    }

    private String getRecordName() {
        if (isResolver && !toRecord) {
            return uncapitalize(target.getTypeName());
        }

        return (mapsJavaRecord ? hasJavaRecordReference : hasTable)
                ? uncapitalize(targetType.asRecordName())
                : select(previousContext.targetName, asIterableIf(previousContext.sourceName, previousContext.isIterable));
    }

    private String getNextPath() {
        if (previousContext.isInitContext) {
            return isResolver && toRecord ? target.getName() : "";
        }

        if (previousContext.path.isEmpty()) {
            return target.getName();
        }

        return previousContext.path + "/" + target.getName();
    }

    private String getNextIndexPath() {
        if (previousContext.isInitContext) {
            return isResolver && toRecord ? target.getName() : "";
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

    public ProcessedSchema getSchema() {
        return schema;
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
        return asIndexName(asIterable(formatSourceForIndexLoop()));
    }

    private String formatSourceForIndexLoop() {
        return isValidation ? asListedName(sourceName) : sourceName;
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
        return asIterableIf(previousContext.sourceName, previousContext.isIterable).equals(asIterableIf(secondLastContext.sourceName, secondLastContext.isIterable));
    }

    public CodeBlock getSourceGetCallBlock() {
        if (isSimpleIDMode) {
            return CodeBlock.of(asRecordName(previousContext.targetName));
        } else if (!toRecord && schema.isNodeIdField(target)) {
            return createNodeIdBlockForRecord(schema.getRecordType(target.getContainerTypeName()), asIterableIf(previousContext.sourceName, previousContext.isIterable));
        }
        return getValue(asIterableIf(previousContext.sourceName, previousContext.isIterable), getSourceMapping);
    }

    public MapperContext iterateContext(GenerationField field) {
        recursionCheck(recursion);
        return new MapperContext(field, this);
    }

    public String getInputVariableName() {
        return select(targetType.getName(), targetType.asRecordName());
    }

    public String getOutputName() {
        return select(targetType.asRecordName(), targetType.getName());
    }

    public String getHelperVariableName() {
        return uncapitalize(mapsJavaRecord ? getSourceMapping.getName() : (previousContext.targetType.getName() + "_" + uncapitalize(setTargetMapping.getName())));
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
            code.declareIf(isResolver || isValidation, asIterable(sourceName), "$N.get($N)", formatSourceForIndexLoop(), getIndexName());

            if (!isValidation) {
                code.add(continueCheck(asIterable(sourceName)));

                if (!isResolver && !mapsJavaRecord && !targetEqualsPrevious) {
                    code.add(select(declareRecord(targetName, targetType, false, false), CodeBlock.declareNew(targetName, targetType.getGraphClassName())));
                }
            } else if (previousContext.isInitContext) {
                code.declareNew(VARIABLE_PATHS_FOR_PROPERTIES, ParameterizedTypeName.get(HASH_MAP.className, STRING.className, STRING.className));
            }
        }

        code
                .declareNewIf(!isValidation && mapsJavaRecord && !targetEqualsPrevious, targetName, targetType.asTargetClassName(toRecord))
                .add(fieldCode);

        if (isResolver) {
            return isIterable ? wrapForIndexed(sourceName, code.build()) : code.build();
        }

        if (hasSourceName()) {
            if (!isValidation && isIterable && (!mapsJavaRecord || hasRecordReference)) {
                code.add(addToList(targetName));
            } else if (isValidation && previousContext.isInitContext) {
                code.addStatement("$N.addAll($T.validatePropertiesAndGenerateGraphQLErrors($N, $N, $N))", VARIABLE_VALIDATION_ERRORS, RECORD_VALIDATOR.className, asIterableIf(sourceName, isIterable), VARIABLE_PATHS_FOR_PROPERTIES, VARIABLE_ENV);
            }
        }

        var forCode = CodeBlock.builder().add(isIterable && hasSourceName() ? (isValidation ? wrapForIndexed(asListedName(sourceName), code.build()): wrapFor(sourceName, code.build())) : code.build());
        if (isValidation || !previousContext.isInitContext && (toRecord || !mapsJavaRecord)) {
            return forCode.build();
        }

        if (!previousContext.isInitContext) {
            return forCode.add(getSetMappingBlock(targetName)).build();
        }

        return CodeBlock
                .builder()
                .add(hasSourceName() ? wrapNotNull(sourceName, forCode.build()) : forCode.build())
                .addIf(toRecord && !mapsJavaRecord, () -> applyGlobalTransforms(targetName, targetType.getRecordClassName(), TransformScope.ALL_MUTATIONS)) // Note: This is done after records are filled.
                .build();
    }

    public CodeBlock getSetMappingBlock(CodeBlock valueToSet) {
        if (schema.isNodeIdField(target) && toRecord && !mapsJavaRecord) {
            var nodeType = schema.getNodeType(target);
            var foreignKey = getForeignKeyForNodeIdReference(target, schema);

            return CodeBlock.statementOf("$N.$L($N, $L, $S, $L)",
                    NODE_ID_STRATEGY_NAME,
                    foreignKey.isPresent() ? METHOD_SET_RECORD_REFERENCE_ID : METHOD_SET_RECORD_ID,
                    previousContext.targetName,
                    valueToSet,
                    nodeType.getTypeId(),
                    foreignKey.isPresent() ? referenceNodeIdColumnsBlock(schema.getRecordType(target.getContainerTypeName()), nodeType, foreignKey.get()) : nodeIdColumnsBlock(nodeType)
            );
        }
        return setValue(previousContext.targetName, setTargetMapping, valueToSet, target.isResolver());
    }

    public CodeBlock getSetMappingBlock(String valueToSet) {
        return getSetMappingBlock(CodeBlock.of(valueToSet));
    }

    public CodeBlock getFieldSetMappingBlock() {
        return getSetMappingBlock(applyEnumConversion(target.getTypeName(), getSourceGetCallBlock()));
    }

    public CodeBlock getResolverKeySetMappingBlockForJooqRecord() {
        return getResolverKeySetMappingBlock(asIterableIf(previousContext.sourceName, previousContext.isIterable), false);
    }

    public CodeBlock getResolverKeySetMappingBlock(String varName) {
        return getResolverKeySetMappingBlock(varName, target.isIterableWrapped());
    }

    private CodeBlock getResolverKeySetMappingBlock(String varName, boolean isKeyList) {
        return getSetMappingBlock(
                CodeBlock.builder()
                        .addIf(isKeyList, "$N.stream().map($N -> ", varName, VARIABLE_INTERNAL_ITERATION)
                        .add(wrapRow(findKeyForResolverField(target, schema).key().getFields().stream()
                                .map(Field::getName)
                                .map(it -> new MethodMapping(toCamelCase(it)))
                                .map(it -> getValue(isKeyList ? VARIABLE_INTERNAL_ITERATION : varName, it))
                                .collect(CodeBlock.joining(", "))))
                        .addIf(isKeyList, ")$L", collectToList())
                        .build()
        );
    }

    public CodeBlock getRecordSetMappingBlock() {
        return getSetMappingBlock(transformRecord());
    }

    public CodeBlock applyEnumConversion(String typeName, CodeBlock getCall) {
        return schema.isEnum(typeName) ? toGraphEnumConverter(typeName, getCall, toRecord, schema) : getCall;
    }

    public CodeBlock getReturnBlock() {
        return returnWrap(asListedNameIf(select(targetType.getRecordReferenceName(), targetType.getName()), hasSourceName() || noRecordIterability));
    }

    /**
     * @return CodeBlock for the mapping of a record. Includes path for validation.
     */
    public CodeBlock transformInputRecord() {
        return CodeBlock.of(
                "$L$L$S$L)",
                recordTransformPart(sourceName, targetType.getName()),
                CodeBlock.ofIf(shouldMakeNodeStrategy(), "$N, ", NODE_ID_STRATEGY_NAME),
                path,
                CodeBlock.ofIf(recordValidationEnabled() && !hasJavaRecordReference, ", \"$L\"", indexPath)
        );
    }

    /**
     * @return CodeBlock for the mapping of a record.
     */
    private CodeBlock transformRecord() {
        return CodeBlock.of(
                "$L$L$N + $S$L)",
                recordTransformPart(
                        previousContext.hasSourceName() ? getHelperVariableName() : asRecordName(previousContext.getTargetName()),
                        uncapitalize(targetType.getName())
                ),
                CodeBlock.ofIf(shouldMakeNodeStrategy(), "$N, ", NODE_ID_STRATEGY_NAME),
                PATH_HERE_NAME,
                path,
                CodeBlock.ofIf(recordValidationEnabled() && !hasJavaRecordReference && toRecord, ", $N + $S", PATH_HERE_NAME, path) // This one may need more work. Does not actually include indices here, but not sure if needed.
        );
    }

    private CodeBlock recordTransformPart(String varName, String typeName) {
        return FormatCodeBlocks.recordTransformPart(TRANSFORMER_NAME, varName, typeName, hasJavaRecordReference, toRecord);
    }

    private boolean isMappingPossible() {
        return sourceHasRequiredMethod() && targetHasRequiredMethod();
    }

    private boolean sourceHasRequiredMethod() {
        // Assume the schema ones are OK anyway. It is done like this because these classes are not defined in tests.
        return toRecord || classHasMethod(toRecord ? previousContext.targetType.getClassReference() : previousContext.targetType.getRecordReference(), getSourceMapping.asGet());
    }

    private boolean targetHasRequiredMethod() {
        // Assume the schema ones are OK anyway. It is done like this because these classes are not defined in tests.
        return !toRecord || classHasMethod(toRecord ? previousContext.targetType.getRecordReference() : previousContext.targetType.getClassReference(), setTargetMapping.asSet());
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
