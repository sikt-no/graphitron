package no.fellesstudentsystem.graphitron.generators.context;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.TransformScope;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.RecordObjectDefinition;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.Nullable;

import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphitron.mappings.ReflectionHelpers.classHasMethod;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class MapperContext {
    private final GenerationField target;
    private final int recursion;
    private final RecordObjectDefinition<?, ?> targetType;
    private final boolean hasRecordReference, hasJavaRecordReference, hasTable, isIterable, wasIterable, toRecord, mapsJavaRecord, isValidation, isResolver, targetIsType, isInitContext;
    private final String sourceName, targetName, path, indexPath;
    private final MethodMapping recordMappingHere, getSourceMapping, setTargetMapping;
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
        recordMappingHere = null;
        getSourceMapping = null;
        setTargetMapping = null;
        previousContext = null;
        targetType = null;
        hasRecordReference = false;
        hasJavaRecordReference = false;
        hasTable = false;
        target = null;
        this.toRecord = toRecord;
        this.mapsJavaRecord = mapsJavaRecord;
        this.isValidation = isValidation;
        this.isResolver = isResolver;
        this.schema = schema;

        isInitContext = true;
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
        this.targetType = schema.getTableType(target);

        targetIsType = targetType != null;
        hasRecordReference = targetIsType && targetType.hasRecordReference();
        hasTable = targetIsType && targetType.hasTable();
        hasJavaRecordReference = targetIsType && targetType.hasJavaRecordReference();
        recursion = previousContext.recursion + 1;
        isIterable = isResolver ? target.isIterableWrapped() : (target.isIterableWrapped() && !hasJavaRecordReference || previousContext.isInitContext);
        wasIterable = isIterable || previousContext.wasIterable;

        var schemaNameToUse = getSchemaNameToUse();
        var recordName = getRecordName();

        sourceName = select(schemaNameToUse, recordName);
        targetName = select(recordName, schemaNameToUse);

        var schemaMethodMapping = target.getMappingFromSchemaName();
        recordMappingHere = getNextRecordMapping();
        getSourceMapping = select(schemaMethodMapping, recordMappingHere);
        setTargetMapping = select(recordMappingHere, schemaMethodMapping);

        path = getNextPath();
        indexPath = getNextIndexPath();
    }

    private @Nullable MethodMapping getNextRecordMapping() {
        if (previousContext.isInitContext || (!target.hasSetFieldOverride() && previousContext.recordMappingHere != null)) {
            return previousContext.recordMappingHere;
        }

        return mapsJavaRecord ? target.getMappingFromFieldOverride() : target.getMappingForJOOQFieldOverride();
    }

    private String getSchemaNameToUse() {
        if (isResolver && toRecord) {
            return uncapitalize(target.getName());
        }

        if (previousContext.isInitContext || isResolver) {
            return uncapitalize(target.getTypeName());
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
        return previousContext.hasRecordReference && !isMappingPossible() && (!targetIsType || targetType.hasTable());
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

    public boolean shouldUseStandardRecordFetch() {
        return hasRecordReference && !(schema.implementsNode(target.getTypeName()) && !target.isInput() && ((ObjectField) target).isFetchByID());
    }

    public boolean shouldUseException() {
        return schema.isExceptionOrExceptionUnion((ObjectField) target) && previousContext.isTopLevelContext();
    }

    public CodeBlock getSourceGetCallBlock() {
        return getValue(asIterableIf(previousContext.sourceName, previousContext.isIterable), getSourceMapping);
    }

    public MapperContext iterateContext(GenerationField field) {
        recursionCheck(recursion);
        return new MapperContext(field, this);
    }

    public String getInputVariableName() {
        return select(targetType.getName(), targetType.asRecordName());
    }

    public String getOutputListName() {
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
            return empty();
        }

        var targetEqualsPrevious = targetName.equals(previousContext.targetName);
        var code = CodeBlock.builder();
        if (isIterable) {
            if (isResolver || isValidation) {
                code.add(declare(asIterable(sourceName), CodeBlock.of("$N.get($N)", formatSourceForIndexLoop(), getIndexName())));
            }

            if (!isValidation) {
                code.add(continueCheck(asIterable(sourceName)));

                if (!isResolver && !mapsJavaRecord && !targetEqualsPrevious) {
                    code.add(select(declareRecord(targetName, targetType, false), declareVariable(targetName, targetType.getGraphClassName())));
                }
            } else if (previousContext.isInitContext) {
                code.add(declare(VARIABLE_PATHS_FOR_PROPERTIES, CodeBlock.of("new $T<$T, $T>()", HASH_MAP.className, STRING.className, STRING.className)));
            }
        }

        if (!isValidation && mapsJavaRecord && !targetEqualsPrevious) {
            code.add(declareVariable(targetName, targetType.asTargetClassName(toRecord)));
        }

        code.add(fieldCode);

        if (isResolver) {
            return isIterable ? wrapForIndexed(sourceName, code.build()) : code.build();
        }

        if (!isValidation && isIterable && (!mapsJavaRecord || hasRecordReference)) {
            code.add(addToList(targetName));
        } else if (isValidation && previousContext.isInitContext) {
            code.addStatement("$N.addAll($T.validatePropertiesAndGenerateGraphQLErrors($N, $N, $N))", VARIABLE_VALIDATION_ERRORS, RECORD_VALIDATOR.className, asIterableIf(sourceName, isIterable), VARIABLE_PATHS_FOR_PROPERTIES, VARIABLE_ENV);
        }

        var forCode = CodeBlock.builder().add(isIterable ? (isValidation ? wrapForIndexed(asListedName(sourceName), code.build()): wrapFor(sourceName, code.build())) : code.build());
        if (isValidation || !previousContext.isInitContext && (toRecord || !mapsJavaRecord)) {
            return forCode.build();
        }

        if (!previousContext.isInitContext) {
            return forCode.add(getSetMappingBlock(targetName)).build();
        }

        return CodeBlock
                .builder()
                .add(wrapNotNull(sourceName, forCode.build()))
                .add(toRecord && !mapsJavaRecord ? applyGlobalTransforms(targetName, targetType.getRecordClassName(), TransformScope.ALL_MUTATIONS) : empty()) // Note: This is done after records are filled.
                .build();
    }

    public CodeBlock getSetMappingBlock(String valueToSet) {
        return setValue(previousContext.targetName, setTargetMapping, valueToSet);
    }

    public CodeBlock getSetMappingBlock(CodeBlock valueToSet) {
        return setValue(previousContext.targetName, setTargetMapping, valueToSet);
    }

    public CodeBlock getFieldSetMappingBlock() {
        return getSetMappingBlock(applyEnumConversion(target.getTypeName(), getSourceGetCallBlock(), target.isIterableWrapped()));
    }

    public CodeBlock getRecordSetMappingBlock() {
        return getSetMappingBlock(transformRecord(getHelperVariableName(), uncapitalize(target.getTypeName()), path, hasJavaRecordReference, toRecord));
    }

    public CodeBlock getRecordSetMappingBlock(String variableName) {
        return getSetMappingBlock(getRecordTransform(variableName));
    }

    public CodeBlock getRecordTransform(String variableName) {
        return transformRecord(variableName, targetName, path, hasJavaRecordReference);
    }

    public CodeBlock applyEnumConversion(String typeName, CodeBlock getCall, boolean isIterable) {
        return schema.isEnum(typeName) ? toGraphEnumConverter(typeName, getCall, isIterable, schema) : getCall;
    }

    public CodeBlock getReturnBlock() {
        return returnWrap(asListedName(select(targetType.getRecordReferenceName(), targetType.getName())));
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
