package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.*;
import no.sikt.graphitron.definitions.helpers.InputCondition;
import no.sikt.graphitron.definitions.helpers.InputConditions;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.AliasWrapper;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.objects.InterfaceDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.definitions.sql.SQLJoinStatement;
import no.sikt.graphitron.generators.abstractions.DBMethodGenerator;
import no.sikt.graphitron.generators.codebuilding.KeyWrapper;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codebuilding.VariablePrefix;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphitron.validation.ValidationHandler;
import no.sikt.graphql.naming.GraphQLReservedName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.configuration.GeneratorConfig.useOptionalSelects;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.getKeyRowTypeName;
import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.getKeySetForResolverFields;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedRecordNameIf;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.internalPrefix;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.resolverKeyPrefix;
import static no.sikt.graphitron.generators.context.JooqRecordReferenceHelpers.getSourceFieldsForForeignKey;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;
import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Abstract generator for various database fetching methods.
 */
public abstract class FetchDBMethodGenerator extends DBMethodGenerator<ObjectField> {
    protected static final String ELEMENT_NAME = internalPrefix("e");
    protected final String resolverKeyParamName;
    protected final boolean isRoot = getLocalObject().isOperationRoot();
    protected final boolean isDeleteMutationQuery; // This will eventually apply to all generated mutations
    private static final int MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPE_SAFETY = 22;

    public FetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema, boolean isDeleteMutationQuery) {
        super(localObject, processedSchema);
        resolverKeyParamName = resolverKeyPrefix(localObject.getName());
        this.isDeleteMutationQuery = isDeleteMutationQuery;
    }

    public FetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        this(localObject, processedSchema, false);
    }

    protected CodeBlock getInitialKey(FetchContext context) {
        var code = CodeBlock.builder();

        var ref = (ObjectField) context.getReferenceObjectField();
        var table = context.renderQuerySource(getLocalTable());
        if (LookupHelpers.lookupExists(ref, processedSchema)) {
            var concatBlock = LookupHelpers.getLookUpKeysAsColumnList(ref, table, processedSchema);
            if (concatBlock.toString().contains(".inline(")) {
                code.add("$T.concat($L),\n", DSL.className, concatBlock);
            } else {
                code.add(concatBlock).add(",\n");
            }
        } else if (!isRoot) {
            code.add("$L,\n", getSelectKeyColumnRow(context));
        }
        return code.build();
    }

    /**
     * @param joinList List of join statements that should be applied to a select query.
     * @return Code block containing all the join statements and their conditions.
     */
    protected CodeBlock createSelectJoins(Set<SQLJoinStatement> joinList) {
        return createSelectJoins(joinList, false);
    }

    /**
     * @param joinList List of join statements that should be applied to a select query.
     * @param skipFirstJoin Whether to skip the first join statement. Used in multitable resolver queries where
     *                      the first join is implicit via .from(keyPath), unless it's a condition reference.
     * @return Code block containing all the join statements and their conditions.
     */
    protected CodeBlock createSelectJoins(Set<SQLJoinStatement> joinList, boolean skipFirstJoin) {
        var codeBuilder = CodeBlock.builder();
        var iterator = joinList.iterator();
        if (skipFirstJoin && iterator.hasNext()) iterator.next();
        iterator.forEachRemaining(join -> codeBuilder.add(join.toJoinString()));
        return codeBuilder.build();
    }

    /**
     * @param conditionList List of conditional statements that should be appended after the where-statement.
     * @return Code block which declares all the extra conditions that will be used in a select query.
     */
    protected CodeBlock createSelectConditions(List<CodeBlock> conditionList, boolean hasWhere) {
        var code = CodeBlock.builder();
        for (var condition : conditionList) {
            code.add(".$L($L)\n", hasWhere ? "and" : "where", condition);
            hasWhere = true;
        }
        return code.build();
    }

    /**
     * @param aliasSet Set of aliases to be defined.
     * @return Code block which declares all the aliases that will be used in a select query.
     */
    protected static CodeBlock createAliasDeclarations(Set<AliasWrapper> aliasSet) {
        return createAliasDeclarations(aliasSet, false);
    }

    /**
     * @param aliasSet Set of aliases to be defined.
     * @param skipFirstAlias Whether to skip the first alias declaration. Used in multitable resolver queries where
     *                       the source table alias is already declared in the main method and passed to the helper methods.
     * @return Code block which declares all the aliases that will be used in a select query.
     */
    protected static CodeBlock createAliasDeclarations(Set<AliasWrapper> aliasSet, boolean skipFirstAlias) {
        var codeBuilder = CodeBlock.builder();
        var aliasIterator = aliasSet.iterator();
        if (skipFirstAlias && aliasIterator.hasNext()) aliasIterator.next();

        aliasIterator.forEachRemaining(aliasWrapper -> {
            var alias = aliasWrapper.getAlias();
            codeBuilder.declare(alias.getMappingName(), CodeBlock.of("$N.as($S)", alias.getVariableValue(), alias.getCodeName()));
            if (aliasWrapper.hasTableMethod()) {
                var args = alias.getMappingName();
                if (!aliasWrapper.getInputNames().isEmpty())
                    args += ", " + String.join(", ", aliasWrapper.getInputNames());
                if (!aliasWrapper.getReferenceObjectField().getContextFields().isEmpty())
                    args += ", " + String.join(", ", aliasWrapper.getReferenceObjectField().getContextFields().keySet().stream().map(VariablePrefix::contextFieldPrefix).toList());
                codeBuilder.addStatement(
                        reassignFromServiceBlock(aliasWrapper.getTableMethod().getClassName().simpleName(), aliasWrapper.getTableMethod().getMethodName(), alias.getMappingName(), args));
            }
        });
        return codeBuilder.build();
    }

    protected CodeBlock generateCorrelatedSubquery(GenerationField field, FetchContext context) {
        var isConnection = ((ObjectField) field).hasForwardPagination();
        var isMultiset = field.isIterableWrapped() || isConnection;
        var orderByFieldsBlock = field.isResolver() && tableHasPrimaryKey(context.getTargetTableName())
                ? CodeBlock.of(VAR_ORDER_FIELDS)
                : createOrderFieldsBlock((ObjectField) field, context.getTargetAlias(), context.getTargetTableName());
        var shouldBeOrdered = isMultiset && !orderByFieldsBlock.isEmpty();
        var shouldHaveOrderByToken = isConnection && !orderByFieldsBlock.isEmpty();

        CodeBlock.Builder select = CodeBlock.builder();
        select.addIf(shouldHaveOrderByToken, "\n$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, context.getTargetAlias(), orderByFieldsBlock);

        if (context.getReferenceObject() == null || field.hasNodeID()) {
            select.add(generateForField(field, context));
        } else {
            // Allow subclasses to use helper methods for record types
            var helperMethodCall = getHelperMethodCallForNestedField((ObjectField) field, context);
            if (helperMethodCall != null) {
                select.add(helperMethodCall);
            } else {
                select.add(generateSelectRow(context));
            }
        }

        var where = formatWhereContents(context, "", getLocalObject().isOperationRoot(), false);
        var joins = createSelectJoins(context.getJoinSet());

        var contents = CodeBlock.builder()
                .add("$T.select($L)", DSL.className, indentIfMultiline(select.build()))
                .add("\n.from($L)\n", context.getSourceAlias())
                .add(joins)
                .add(where)
                .add(createSelectConditions(context.getConditionList(), !where.isEmpty()))
                .addIf(shouldBeOrdered, ".orderBy($L)", orderByFieldsBlock)
                .addIf(isConnection, this::createSeekAndLimitBlock)
                .build();

        if (!isMultiset) {
            return wrapInField(contents);
        }

        return field.isResolver()
                ? wrapInMultiset(contents)
                : wrapInMultisetWithMapping(contents, shouldHaveOrderByToken);
    }

    /**
     * Override this method to provide a helper method call for nested record type fields.
     * Return null to use inline generation.
     */
    protected CodeBlock getHelperMethodCallForNestedField(ObjectField field, FetchContext context) {
        return null;
    }
    
    private CodeBlock wrapInMultisetWithMapping(CodeBlock contents, boolean hasOrderByToken) {
        return CodeBlock.of(
                "$L.mapping($L -> $N.map($T::value$L))",
                wrapRow(wrapInMultiset(contents)),
                ELEMENT_NAME,
                ELEMENT_NAME,
                hasOrderByToken ? RECORD2.className : RECORD1.className,
                hasOrderByToken ? "2" : "1"
        );
    }

    protected CodeBlock wrapInMultiset(CodeBlock contents) {
        return CodeBlock.of("$T.multiset($L)", DSL.className, indentIfMultiline(contents));
    }

    protected CodeBlock wrapInField(CodeBlock contents) {
        return CodeBlock.of("$T.field($L)", DSL.className, indentIfMultiline(contents));
    }

    /**
     * This method recursively generates one single row method call.
     * It deduces how each layer of row call should be structured by keeping track of joins and following field references.
     * @return Code block which contains the entire recursive structure of the row statement.
     */
    protected CodeBlock generateSelectRow(FetchContext context) {
        var fieldsWithoutSplitting = context
                .getReferenceObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isExceptionOrExceptionUnion(it))
                .filter(f -> !(f.isResolver() && processedSchema.isRecordType(f)))
                .collect(Collectors.toList());

        var rowElements = new ArrayList<CodeBlock>();

        var keySet = getKeySetForResolverFields(context.getReferenceObject().getFields(), processedSchema);
        var targetTable = context.getTargetTableName();
        var targetAlias = context.getTargetAlias();
        keySet.forEach(key -> rowElements.add(getSelectKeyColumnRow(key.key(), targetTable, targetAlias)));

        var referenceFieldSources = new HashMap<String, String>(); // Used to keep track of field sources for explicit mapping

        for (GenerationField field : fieldsWithoutSplitting) {
            var innerRowCodeAndFieldSource = getSelectCodeAndFieldSource(field, context);

            rowElements.add(innerRowCodeAndFieldSource.getLeft());
            referenceFieldSources.put(field.getName(), innerRowCodeAndFieldSource.getRight());
        }

        return createMapping(context, fieldsWithoutSplitting, referenceFieldSources, rowElements, keySet);
    }

    protected Pair<CodeBlock, String> getSelectCodeAndFieldSource(GenerationField field, FetchContext context) {
        CodeBlock innerRowCode;
        String fieldSource = null;

        if (processedSchema.isObject(field)) {
            var table = processedSchema.getObject(field.getTypeName()).getTable();
            innerRowCode = table != null && !table.equals(context.getTargetTable()) || field.invokesSubquery()
                    ? generateCorrelatedSubquery(field, context.nextContext(field))
                    : generateSelectRow(context.nextContext(field));
        } else if (field.isExternalField()) {
            JOOQMapping table = processedSchema.getObject(field.getContainerTypeName()).getTable();

            if (table == null) {
                throw new IllegalArgumentException("No table found for field " + field.getName());
            }

            innerRowCode = CodeBlock.of(
                    "$L.$L($L)",
                    getImportReferenceOfValidExtensionMethod(field, table.getName()),
                    field.getName(),
                    context.getTargetAlias());
        } else if (field.invokesSubquery() && !processedSchema.isNodeIdForNodeTypeWithSameTable(field)) {
            var fieldContext = context.nextContext(field);
            fieldSource = fieldContext.renderQuerySource(getLocalTable()).toString();
            innerRowCode = generateCorrelatedSubquery(field, fieldContext);
        } else {
            innerRowCode = generateForField(field, context);
        }
        return Pair.of(innerRowCode, fieldSource);
    }

    private static String getImportReferenceOfValidExtensionMethod(GenerationField field, String tableName) {
        var imports = GeneratorConfig.getExternalReferenceImports();

        Optional<String> reference = imports.stream()
                .filter(it -> getMethodFromReference(it, tableName, field.getName()).isPresent())
                .findFirst();

        if (reference.isEmpty()) {
            throw new IllegalArgumentException("No method found for field " + field.getName() + " in table " + tableName);
        }

        return reference.get();
    }

     protected CodeBlock createMapping(FetchContext context, List<? extends GenerationField> fieldsWithoutSplitting, HashMap<String, String> referenceFieldSources, List<CodeBlock> rowElements, LinkedHashSet<KeyWrapper> keySet) {
        boolean maxTypeSafeFieldSizeIsExceeded = fieldsWithoutSplitting.size() + keySet.size() > MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPE_SAFETY;

        CodeBlock regularMappingFunction = context.shouldUseEnhancedNullOnAllNullCheck()
                ? createMappingFunctionWithEnhancedNullSafety(fieldsWithoutSplitting, context.getReferenceObject().getGraphClassName(), maxTypeSafeFieldSizeIsExceeded, keySet.size())
                : createMappingFunction(context, fieldsWithoutSplitting, maxTypeSafeFieldSizeIsExceeded);

        var mappingContent = maxTypeSafeFieldSizeIsExceeded
                ? wrapWithExplicitMapping(regularMappingFunction, context, fieldsWithoutSplitting, referenceFieldSources, keySet)
                : regularMappingFunction;

        return CodeBlock
                .builder()
                .add(wrapRow(CodeBlock.join(rowElements, ",\n")))
                .addIf(!mappingContent.isEmpty(), ".mapping($L)", mappingContent)
                .build();
    }

    private CodeBlock createMappingFunction(FetchContext context, List<? extends GenerationField> fieldsWithoutTable, boolean maxTypeSafeFieldSizeIsExceeded) {
        boolean hasIdField = fieldsWithoutTable.stream().anyMatch(GenerationField::isID);
        boolean hasNullableField = fieldsWithoutTable.stream().anyMatch(GenerationField::isNullable);

        boolean canReturnNonNullableObjectWithAllFieldsNull = hasNullableField && context.getReferenceObjectField().isNonNullable() && !hasIdField;

        var className = context.getReferenceObject().getGraphClassName();
        if (canReturnNonNullableObjectWithAllFieldsNull) {
            context.setParentContextShouldUseEnhancedNullOnAllNullCheck();
            return CodeBlock.of(maxTypeSafeFieldSizeIsExceeded ? "new $T(\n" : "$T::new", className);
        }

        if (fieldsWithoutTable.stream().anyMatch(objectField -> processedSchema.isUnion(objectField.getTypeName()))) {
            return createMappingWithUnion(context, fieldsWithoutTable);
        }

        if (maxTypeSafeFieldSizeIsExceeded) {
            return listedNullCheck(VAR_RECORD_ITERATOR, CodeBlock.of("new $T(\n", className));
        }

        // Made this obscure case because compilation failed on the type safety for some of these. No clue as to why.
        var listedFieldWithoutTableIsPresent = context
                .getReferenceObject()
                .getFields()
                .stream()
                .filter(f -> !(f.isResolver() && processedSchema.isRecordType(f)))
                .anyMatch(FieldSpecification::isIterableWrapped);

        if (!context.hasPreviousContext() && !context.hasApplicableTable() && listedFieldWithoutTableIsPresent) {
            return CodeBlock.of("$T.nullOnAllNull(($L) -> new $T($N))", FUNCTIONS.className, VAR_ITERATOR, className, VAR_ITERATOR);
        }

        return CodeBlock.of("$T.nullOnAllNull($T::new)", FUNCTIONS.className, className);
    }

    private CodeBlock createMappingWithUnion(FetchContext context, List<? extends GenerationField> fieldsWithoutTable) {
        var codeBlockArguments = CodeBlock.builder();
        var codeBlockConstructor = CodeBlock.builder();
        for (var mappingIndex = 0; mappingIndex < fieldsWithoutTable.size(); mappingIndex++) {
            var field = fieldsWithoutTable.get(mappingIndex);
            var typeName = field.getTypeName();
            var isLastField = (mappingIndex == fieldsWithoutTable.size()-1);
            if (processedSchema.isUnion(typeName)) {
                codeBlockArguments.add(unionFieldArguments(typeName, mappingIndex, isLastField));
                codeBlockConstructor.add(unionFieldConstructor(typeName, mappingIndex, isLastField));
            } else {
                var currentAlias = CodeBlock.of("$L$L$L", ELEMENT_NAME, mappingIndex, (isLastField ? "" : ", "));
                codeBlockArguments.add(currentAlias);
                codeBlockConstructor.add(currentAlias);
            }
        }

        return CodeBlock.of("($L) -> new $L($L)", codeBlockArguments.build(), context.getReferenceObjectField().getTypeName(), codeBlockConstructor.build());
    }

    private CodeBlock unionFieldArguments(String typeName, int fieldIndex, boolean isLastField) {
        var codeBlockArguments = CodeBlock.builder();
        var unionFieldTypeNames = processedSchema.getUnion(typeName).getFieldTypeNames();
        for (var i = 0; i < unionFieldTypeNames.size(); i++) {
            codeBlockArguments.add("$L$L_$L", ELEMENT_NAME, fieldIndex, i);
            codeBlockArguments.addIf(i + 1 < unionFieldTypeNames.size(), ", ");
        }

        codeBlockArguments.addIf(!isLastField, ", ");
        return codeBlockArguments.build();
    }

    private CodeBlock unionFieldConstructor(String typeName, int fieldIndex, boolean isLastField) {
        var codeBlockConstructor = CodeBlock.builder();
        var unionFieldTypeNames = processedSchema.getUnion(typeName).getFieldTypeNames();
        for (var i = 0; i < unionFieldTypeNames.size(); i++) {
            var currentAlias = CodeBlock.of("$L$L_$L", ELEMENT_NAME, fieldIndex, i);
            if (i + 1 < unionFieldTypeNames.size()) {
                codeBlockConstructor.add("$L != null ? $L : ", currentAlias, currentAlias);
            } else {
                codeBlockConstructor.add(currentAlias);
            }
        }
        codeBlockConstructor.addIf(!isLastField, ", ");
        return codeBlockConstructor.build();
    }

    private CodeBlock unionFieldCondition(String typeName, int fieldIndex, boolean isLastField) {
        var codeBlockConditions = CodeBlock.builder();
        var unionFieldTypeNames = processedSchema.getUnion(typeName).getFieldTypeNames();
        for (var i = 0; i < unionFieldTypeNames.size(); i++) {
            codeBlockConditions.add("$L$L_$L != null", ELEMENT_NAME, fieldIndex, i);
            codeBlockConditions.addIf(i + 1 < unionFieldTypeNames.size(), " && ");
        }
        codeBlockConditions.addIf(!isLastField, " && ");
        return codeBlockConditions.build();
    }

    private CodeBlock createMappingFunctionWithEnhancedNullSafety(List<? extends GenerationField> fieldsWithoutTable, TypeName graphClassName, boolean maxTypeSafeFieldSizeIsExceeded, int keyCount) {
        var codeBlockArguments = CodeBlock.builder();
        var codeBlockConditions = CodeBlock.builder();
        var codeBlockConstructor = CodeBlock.builder();

        var useMemberConstructor = false;

        codeBlockArguments.add("(");
        codeBlockConstructor.add("(");

        for (int i = 0; i < fieldsWithoutTable.size() + keyCount; i++) {
            var field = i < keyCount ? null : fieldsWithoutTable.get(i - keyCount);
            var isLastField = (i == fieldsWithoutTable.size() + keyCount - 1);

            if (field != null && processedSchema.isUnion(field.getTypeName())) {
                useMemberConstructor = true;
                codeBlockArguments.add(unionFieldArguments(field.getTypeName(), i, isLastField));
                codeBlockConstructor.add(unionFieldConstructor(field.getTypeName(), i, isLastField));
                codeBlockConditions.add(unionFieldCondition(field.getTypeName(), i, isLastField));
                continue;
            }

            CodeBlock argumentName;
            if (maxTypeSafeFieldSizeIsExceeded) {
                argumentName = CodeBlock.of("$N[$L]", VAR_RECORD_ITERATOR, i);
            } else {
                argumentName = CodeBlock.of("$L$L", ELEMENT_NAME, i);
                codeBlockArguments.add(argumentName);
                codeBlockArguments.add(isLastField ? ")" : ", ");
            }

            codeBlockConstructor
                    .add(argumentName)
                    .add(isLastField ? ")" : ", ");

            if (field != null && processedSchema.isObject(field))  {
                codeBlockConditions.add("($L == null || new $T().equals($L))", argumentName, processedSchema.getObject(field).getGraphClassName(), argumentName);
            } else {
                codeBlockConditions.add("$L == null", argumentName);
            }
            codeBlockConditions.add(isLastField ? "" : " && ");
        }

        if (maxTypeSafeFieldSizeIsExceeded) {
            return CodeBlock.of("$L ? null : new $T(\n", codeBlockConditions.build(), graphClassName);
        }

        return CodeBlock.builder()
                .add(codeBlockArguments.build())
                .add(" -> ")
                .add(codeBlockConditions.build())
                .add(" ? null : new $T", graphClassName)
                .add((useMemberConstructor) ? codeBlockConstructor.build() : codeBlockArguments.build())
                .build();
    }

    /**
     * Used when fields size exceeds {@link #MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPE_SAFETY}. This
     * requires the mapping function to be wrapped with explicit mapping, without type safety.
     */
    private CodeBlock wrapWithExplicitMapping(CodeBlock mappingFunction, FetchContext context, List<? extends GenerationField> fieldsWithoutTable, HashMap<String, String> sourceForReferenceFields, LinkedHashSet<KeyWrapper> keySet) {
        var innerMappingCode = new ArrayList<CodeBlock>();

        int i = 0;
        for (var key : keySet) {
            innerMappingCode.add(CodeBlock.of("($T) $N[$L]", key.getRecordTypeName(), VAR_RECORD_ITERATOR, i));
            i++;
        }

        for (; i < fieldsWithoutTable.size() + keySet.size(); i++) {
            var field = fieldsWithoutTable.get(i - keySet.size());

            if (processedSchema.isObject(field)) {
                var typeName = field.isIterableWrapped()
                        ? ParameterizedTypeName.get(LIST.className, processedSchema.getObject(field).getGraphClassName())
                        : processedSchema.getObject(field).getGraphClassName();
                innerMappingCode.add(CodeBlock.of("($T) $N[$L]", typeName, VAR_RECORD_ITERATOR, i));
            } else if (field.isExternalField()) {
                JOOQMapping table = processedSchema.getObject(field.getContainerTypeName()).getTable();

                if (table == null) {
                    throw new IllegalArgumentException("No table found for field " + field.getName());
                }

                innerMappingCode.add(
                        CodeBlock.of(
                                "$L.$L($L).getDataType().convert($N[$L])",
                                getImportReferenceOfValidExtensionMethod(field, table.getName()),
                                field.getName(),
                                context.getTargetAlias(),
                                VAR_RECORD_ITERATOR,
                                i
                        )
                );
            } else if (field.isID()) {
                innerMappingCode.add(CodeBlock.of("($T) $N[$L]", STRING.className, VAR_RECORD_ITERATOR, i));
            } else if (processedSchema.isEnum(field)) {
                var enumDefinition = processedSchema.getEnum(field);
                innerMappingCode.add(CodeBlock.of("($T) $N[$L]", enumDefinition.getGraphClassName(), VAR_RECORD_ITERATOR, i));
            } else {
                innerMappingCode.add(
                        CodeBlock.of(
                                "$L.$N.getDataType().convert($N[$L])",
                                field.hasFieldReferences() ? sourceForReferenceFields.get(field.getName()) : context.renderQuerySource(getLocalTable()),
                                field.getUpperCaseName(),
                                VAR_RECORD_ITERATOR,
                                i
                        )
                );
            }
        }

        return CodeBlock.builder()
                .add("$T.class, $L ->\n", context.getReferenceObject().getGraphClassName(), VAR_RECORD_ITERATOR)
                .indent()
                .indent()
                .add(mappingFunction)
                .indent()
                .indent()
                .add(innerMappingCode.stream().collect(CodeBlock.joining(",\n")))
                .unindent()
                .unindent()
                .add("\n)\n")
                .unindent()
                .unindent()
                .build();
    }

    /**
     * Generate a single argument in the row method call.
     */
    protected CodeBlock generateForField(GenerationField field, FetchContext context) {
        return generateForField(field, context, false);
    }

    /**
     * Generate a single argument in the row method call.
     */
    protected CodeBlock generateForField(GenerationField field, FetchContext context, boolean overrideEnum) {
        if (processedSchema.isUnion(field)) {
            return generateForUnionField(field, context);
        }

        if (processedSchema.isNodeIdField(field)) {
            return createNodeIdBlock(
                    field.hasNodeID() ? processedSchema.getNodeTypeForNodeIdField(field) : context.getReferenceObject(),
                    context.getTargetAlias()
            );
        }

        var renderedSource = field.isInput() ? context.iterateJoinSequenceFor(field).render() : context.renderQuerySource(getLocalTable());
        if (field.isID() && !shouldMakeNodeStrategy()) {
            return CodeBlock.join(
                    renderedSource,
                    generateGetForID(field.getMappingFromFieldOverride(), context.getCurrentJoinSequence().isEmpty() ? null : context.getCurrentJoinSequence().getLast().getTable())
            );
        }

        var content = CodeBlock.of(
                "$L.$N$L",
                renderedSource,
                field.getUpperCaseName(),
                overrideEnum ? CodeBlock.empty() : toJOOQEnumConverter(field.getTypeName(), processedSchema)
        );


        return context.getShouldUseOptional() && useOptionalSelects() ? (CodeBlock.of("$N.optional($S, $L)", VAR_SELECT, context.getGraphPath() + field.getName(), content)) : content;
    }

    private CodeBlock generateGetForID(MethodMapping mapping, JOOQMapping table) {
        if (table != null) { // This may become superfluous if nodeId becomes the only way of handling IDs. This is just temporary insurance that the right thing gets picked.
            var method = searchTableForMethodWithName(table.getMappingName(), mapping.getName())
                    .or(() -> searchTableForMethodWithName(table.getMappingName(), mapping.asGet()))
                    .or(() -> searchTableForMethodWithName(table.getMappingName(), mapping.asCamelGet()));
            if (method.isPresent()) {
                return CodeBlock.of(".$L()", method.get());
            }
        }
        return mapping.asGetCall();
    }

    private CodeBlock generateHasForID(MethodMapping mapping, JOOQMapping table, CodeBlock content, boolean isIterable) {
        if (table != null) { // This may become superfluous if nodeId becomes the only way of handling IDs. This is just temporary insurance that the right thing gets picked.
            var suffix = isIterable ? "s" : "";
            if (searchTableForMethodWithName(table.getMappingName(), mapping.asHas() + suffix).isPresent()) {
                return mapping.asHasCall(content, isIterable);
            }
            if (searchTableForMethodWithName(table.getMappingName(), mapping.asCamelHas() + suffix).isPresent()) {
                return mapping.asCamelHasCall(content, isIterable);
            }
        }
        return mapping.asHasCall(content, isIterable);
    }

    /**
     * Generate select row for each object within the union field
     */
    protected CodeBlock generateForUnionField(GenerationField field, FetchContext context) {
        var union = processedSchema.getUnion(field).getFieldTypeNames();
        var code = new ArrayList<CodeBlock>();
        for (var fieldObject : union) {
            var objectField = new VirtualSourceField(processedSchema.getObject(fieldObject), fieldObject);
            code.add(generateSelectRow(context.nextContext(objectField).withShouldUseOptional(false)));
        }
        return CodeBlock.join(code, ",\n");
    }

    /**
     * @return Formatted CodeBlock for the where-statement and surrounding code. Applies conditions and joins.
     */
    protected CodeBlock formatWhereContents(FetchContext context, String resolverKeyParamName, boolean isRoot, boolean isResolverRoot) {
        var conditionList = new ArrayList<CodeBlock>();

        if (context.getReferenceObject() instanceof InterfaceDefinition) {
            // Discriminator condition for single table interface
            conditionList.add(
                    CodeBlock.of("$L.$L.in($L)",
                            context.renderQuerySource(getLocalTable()),
                            processedSchema.getInterface(context.getReferenceObjectField()).getDiscriminatorFieldName(),
                            CodeBlock.join(
                                    processedSchema
                                            .getObjects()
                                            .values()
                                            .stream()
                                            .filter(it -> it.implementsInterface(processedSchema.getInterface(context.getReferenceObjectField()).getName()))
                                            .map(it -> CodeBlock.of("$S", it.getDiscriminator()))
                                            .toList(),
                                    ", "))
            );
        }

        if (!isRoot && !resolverKeyParamName.isEmpty() && isResolverRoot) {
            conditionList.add(inResolverKeysBlock(resolverKeyParamName, context));
        }

        if (!isResolverRoot && (context.hasNonSubqueryFields() || context.hasApplicableTable())) {
            conditionList.addAll(getInputConditions(context, (ObjectField) context.getReferenceObjectField()));
            var otherConditionsFields = context
                    .getConditionSourceFields()
                    .stream()  // In theory this filter should not be necessary, context logic should add these in a way such that this case never arises.
                    .filter(it -> !it.equals(context.getReferenceObjectField()))
                    .toList();
            if (!otherConditionsFields.isEmpty()) {
                for (var otherConditionsField : otherConditionsFields) {
                    conditionList.addAll(getInputConditions(context, (ObjectField) otherConditionsField));
                }
            }
        }
        return formatJooqConditions(conditionList);
    }

    protected static CodeBlock formatJooqConditions(ArrayList<CodeBlock> conditionList) {
        var code = CodeBlock.builder();
        var hasWhere = false;
        for (var condition : conditionList) {
            if (condition.isEmpty()) continue;
            code.add(".$L($L)\n", hasWhere ? "and" : "where", indentIfMultiline(condition));
            hasWhere = true;
        }

        return code.build();
    }

    protected List<CodeBlock> getInputConditions(FetchContext context, ObjectField sourceField) {
        var allConditionCodeBlocks = new ArrayList<CodeBlock>();
        var inputConditions = getInputConditions(sourceField);
        var flatInputs = inputConditions.independentConditions();
        var declaredInputConditions = inputConditions.declaredConditionsByField();
        var splitInputs = flatInputs
                .stream()
                .collect(Collectors.partitioningBy(it -> processedSchema.hasRecord(it.getInput()) && !isDeleteMutationQuery));
        var inputsWithRecord = splitInputs.get(true);
        var inputsWithoutRecord = splitInputs.get(false);

        for (var inputCondition : inputsWithoutRecord) {
            var conditionBuilder = CodeBlock.builder();
            var field = inputCondition.getInput();
            var checks = inputCondition.getChecksAsSequence();
            var isInRecordInput = !isDeleteMutationQuery && processedSchema.isInputType(field.getContainerTypeName()) && processedSchema.hasJOOQRecord(field.getContainerTypeName());
            var checksNotEmpty = !checks.isEmpty()
                    && !(!isDeleteMutationQuery && isInRecordInput && processedSchema.isNodeIdField(field)); // Skip null checks for nodeId in jOOQ record inputs in queries
            var renderedSequence = isInRecordInput ?
                    CodeBlock.of(context.getTargetAlias())
                    :  context.iterateJoinSequenceFor(field).render();
            if (renderedSequence.isEmpty()) {
                continue;
            }

            if (!inputCondition.isOverriddenByAncestors() && !field.hasOverridingCondition()) {
                if (checksNotEmpty) {
                    conditionBuilder.add(checks + " ? ");
                }

                conditionBuilder.add(formatCondition(inputCondition, renderedSequence, context));

                if (checksNotEmpty) {
                    var fallbackOnFalse = (isDeleteMutationQuery && (inputCondition.isWrappedInList() || inputCondition.previousWasNullable())) || field instanceof VirtualInputField;
                    conditionBuilder
                            .add(" : ")
                            .addIf(fallbackOnFalse, falseCondition())
                            .addIf(!fallbackOnFalse, noCondition());
                }
                allConditionCodeBlocks.add(conditionBuilder.build());
            }

            if (field.hasCondition()) {
                var conditionInputs = List.of(renderedSequence, getCheckedNameWithPath(inputCondition));
                allConditionCodeBlocks.add(field.getCondition().formatToString(conditionInputs));
            }
        }

        for (var conditionTuple : inputConditions.conditionTuples()) {
            if (inputConditions.conditionTuples()
                    .stream()
                    .anyMatch(it -> conditionTuple.path().startsWith(it.path())
                            && conditionTuple.path().length() > it.path().length())) {
                continue;
            }

            allConditionCodeBlocks.add(createTupleCondition(
                    context, conditionTuple.path(), conditionTuple.conditions()));
        }

        for (var inputCondition : inputsWithRecord) {
            var field = inputCondition.getInput();
            var sequence = context.iterateJoinSequenceFor(field).render();

            if (!sequence.isEmpty() && field.hasCondition()) {
                var conditionInputs = List.of(sequence, getCheckedNameWithPath(inputCondition));
                allConditionCodeBlocks.add(field.getCondition().formatToString(conditionInputs));
            }
        }

        for (var condition : declaredInputConditions.entrySet()) {
            var inputs = Stream.concat(
                    Stream.of(context.getCurrentJoinSequence().render()),
                    condition.getValue().stream().map(this::getCheckedNameWithPath)
            ).collect(Collectors.toList());

            allConditionCodeBlocks.add(condition.getKey().getCondition().formatToString(inputs));
        }
        return allConditionCodeBlocks;
    }

    private CodeBlock formatCondition(InputCondition inputCondition, CodeBlock renderedSequence, FetchContext context) {
        var field = inputCondition.getInput();
        var name = !isDeleteMutationQuery && processedSchema.isNodeIdField(field) && processedSchema.hasJOOQRecord(field.getContainerTypeName())
                ? CodeBlock.of(inputCondition.getNamePath())
                : inputCondition.getNameWithPath();
        if (processedSchema.isNodeIdField(field)) {
            return hasIdOrIdsBlock(
                    name,
                    processedSchema.getNodeTypeForNodeIdField(field),
                    renderedSequence.toString(),
                    getSourceFieldsForForeignKey(field, processedSchema, renderedSequence),
                    field.isIterableWrapped()
            );
        }

        if (field.isID() && !shouldMakeNodeStrategy()) {
            var isInRecordInput = processedSchema.isInputType(field.getContainerTypeName()) && processedSchema.hasJOOQRecord(field.getContainerTypeName());
            var table = isInRecordInput ? context.getCurrentJoinSequence().getLast().getTable() : context.iterateJoinSequenceFor(field).getLast().getTable();
            return CodeBlock.join(renderedSequence, generateHasForID(field.getMappingFromFieldOverride(), table, name, field.isIterableWrapped()));
        }

        return CodeBlock.of(
                "$L.$N$L.$L($L)",
                renderedSequence,
                field.getUpperCaseName(),
                !processedSchema.hasJOOQRecord(field.getContainerTypeName()) ? toJOOQEnumConverter(field.getTypeName(), processedSchema) : CodeBlock.empty(),
                field.isIterableWrapped() ? "in" : "eq",
                name
        );
    }

    private CodeBlock getCheckedNameWithPath(InputCondition condition) {
        var nameWithPath = condition.getNameWithPath();
        var checks = condition.getChecksAsSequence();
        var enumConverter = toGraphEnumConverter(
                condition.getInput().getTypeName(),
                nameWithPath,
                true,
                processedSchema
        );

        return CodeBlock.of(
                !checks.isEmpty() && !condition.getNamePath().isEmpty() ? checks + " ? $L : null" : "$L",
                enumConverter.isEmpty() ? nameWithPath : enumConverter
        );
    }

    private CodeBlock createTupleCondition(
            FetchContext context,
            String argumentInputFieldName,
            List<InputCondition> conditions
    ) {
        var selectedConditions = new HashSet<InputCondition>();
        var tupleFieldBlocks = new ArrayList<CodeBlock>();
        var tupleVariableBlocks = new ArrayList<CodeBlock>();

        for (var condition : conditions) {
            var field = condition.getInput();
            var fieldSequence = context.iterateJoinSequenceFor(field);
            var lastTable = fieldSequence.getLast().getTable();
            var unpacked = unpackElement(context, argumentInputFieldName, condition, lastTable);

            if (condition.isOverriddenByAncestors()) {
                continue;
            }

            if (!field.hasOverridingCondition()) {
                var argumentSelect = CodeBlock.ofIf(field.isNullable(), "$N.getArgumentSet().contains($S + $N + $S)",
                        VAR_SELECT, condition.getSourceInput().getName() + "[" , VAR_ITERATOR, "]/" + field.getName());
                if (processedSchema.isNodeIdField(field)) {
                    if(field.isNullable() && !(field instanceof VirtualInputField)) {
                        tupleVariableBlocks.add(ofTernary(argumentSelect, unpacked, trueCondition()));
                    } else {
                        tupleVariableBlocks.add(unpacked);
                    }
                } else if (field.isID()) {
                    if(field.isNullable() && !(field instanceof VirtualInputField)) {
                        tupleVariableBlocks.add(ofTernary(argumentSelect, CodeBlock.join(fieldSequence.render(), generateHasForID(field.getMappingFromFieldOverride(), lastTable, unpacked, field.isIterableWrapped())), trueCondition()));
                    } else {
                        tupleVariableBlocks.add(CodeBlock.join(fieldSequence.render(), generateHasForID(field.getMappingFromFieldOverride(), lastTable, unpacked, field.isIterableWrapped())));
                    }
                } else {
                    if (field.isNullable() && !(field instanceof VirtualInputField)) {
                        tupleVariableBlocks.add(ofTernary(argumentSelect, val(unpacked), makeTupleBlock(field, context, condition.hasRecord(), fieldTypeIsCLOB(lastTable.getName(), field.getUpperCaseName()))));
                    }else{
                        tupleVariableBlocks.add(val(unpacked));
                    }
                }

                tupleFieldBlocks.add(makeTupleBlock(field, context, condition.hasRecord(), fieldTypeIsCLOB(lastTable.getName(), field.getUpperCaseName())));
                selectedConditions.add(condition);
            }

            if (field.hasCondition()) {
                tupleFieldBlocks.add(trueCondition());

                var conditionInputs = List.of(fieldSequence.render(), unpacked);
                tupleVariableBlocks.add(field.getCondition().formatToString(conditionInputs));
                selectedConditions.add(condition);
            }
        }

        if (tupleFieldBlocks.isEmpty()) {
            return CodeBlock.empty();
        }

        var checks = String.join(" && ", selectedConditions
                .stream()
                .map(InputCondition::getChecksAsSequence)
                .collect(Collectors.toSet()));

        return CodeBlock
                .builder()
                .add(checks.isEmpty() ? "" : "$L ?\n", checks)
                .indent()
                .indent()
                .add(wrapRow(CodeBlock.join(tupleFieldBlocks, ",\n")))
                .add(".in(\n")
                .indent()
                .indent()
                .add("$T.range(0, $N.size()).mapToObj($N ->\n", INT_STREAM.className, argumentInputFieldName, VAR_ITERATOR)
                .indent()
                .indent()
                .add(wrapRow(CodeBlock.join(tupleVariableBlocks, ",\n")))
                .unindent()
                .unindent()
                .add("\n)")
                .unindent()
                .unindent()
                .add("$L\n) : $L", collectToList(), isDeleteMutationQuery ? falseCondition() : noCondition())
                .unindent()
                .unindent()
                .build();
    }

    private CodeBlock makeTupleBlock(GenerationField field, FetchContext context, boolean hasRecord, boolean isClob) {
        if (field.isID() || processedSchema.isNodeIdField(field)) {
            return trueCondition();
        }

        return CodeBlock.join(
                generateForField(field, context, hasRecord),
                CodeBlock.ofIf(isClob, ".cast($T.class)", STRING.className)
        );
    }

    private CodeBlock unpackElement(FetchContext context, String argumentInputFieldName, InputCondition condition, JOOQMapping table) {
        var field = condition.getInput();
        var getElement = CodeBlock.of("$N.get($N)", argumentInputFieldName, VAR_ITERATOR);
        if (processedSchema.isNodeIdField(field)) {
            getElement = isDeleteMutationQuery && processedSchema.hasJOOQRecord(field.getContainerTypeName())
                    ? CodeBlock.of("$L$L", getElement, field.getMappingForRecordFieldOverride().asGetCall())
                    : getElement;
            var referenceObject = processedSchema.hasJOOQRecord(field.getContainerTypeName())
                    ? processedSchema.getNodeTypeForNodeIdField(field)
                    : context.getReferenceObject();
            return hasIdBlock(getElement, referenceObject, context.getTargetAlias());
        }

        if (!condition.hasRecord()) {
            return CodeBlock.of("$L$L", getElement, condition.getNameWithPathString().replaceFirst(Pattern.quote(argumentInputFieldName), ""));
        }

        var mapping = field.isID() && !Optional.ofNullable(processedSchema.getRecordType(condition.getSourceInput())).map(RecordObjectSpecification::hasJavaRecordReference).orElse(false)
                ? generateGetForID(field.getMappingFromFieldOverride(), table)
                : field.getMappingForRecordFieldOverride().asGetCall();
        return CodeBlock.of("$L$L", getElement, mapping);
    }

    @NotNull
    protected InputConditions getInputConditions(ObjectField referenceField) {
        var pathNameForIterableFields = new ArrayList<String>();
        var flatInputs = new ArrayList<InputCondition>();
        var declaredConditionsByField = new LinkedHashMap<GenerationField, List<InputCondition>>();
        var ancestorsWithDeclaredCondition = new HashSet<GenerationField>();

        if (referenceField.hasCondition()) {
            declaredConditionsByField.put(referenceField, new ArrayList<>());
            ancestorsWithDeclaredCondition.add(referenceField);
        }

        var inputBuffer = referenceField
                .getNonReservedArguments()
                .stream()
                .map(it -> new InputCondition(
                        it,
                        inferFieldNamingConvention(it),
                        processedSchema.hasRecord(it) && !isDeleteMutationQuery,
                        referenceField.hasOverridingCondition()))
                .collect(Collectors.toCollection(LinkedList::new));

        while (!inputBuffer.isEmpty() && inputBuffer.size() < Integer.MAX_VALUE) {
            var inputCondition = inputBuffer.poll();
            var inputField = inputCondition.getInput();

            if (processedSchema.isInputType(inputField)) {
                if (ancestorsWithDeclaredCondition.contains(inputField)) {
                    ancestorsWithDeclaredCondition.remove(inputField);
                    continue;
                } else if (inputField.hasCondition() && !processedSchema.hasRecord(inputField)) {
                    ancestorsWithDeclaredCondition.add(inputField);
                    declaredConditionsByField.put(inputField, new ArrayList<>());
                    inputBuffer.addFirst(inputCondition);
                }

                if (inputField.isIterableWrapped()) {
                    pathNameForIterableFields.add(inputCondition.getNameWithPathString());
                }

                var innerFields = !isDeleteMutationQuery  && getLocalObject().getName().equalsIgnoreCase(SCHEMA_MUTATION.getName()) && processedSchema.hasJOOQRecord(inputField) ?
                        getPrimaryKeyForTable(processedSchema.getRecordType(inputField).getTable().getName())
                                .map(it -> it.getFields().stream().map(col -> new VirtualInputField(col.getName(), inputField.getContainerTypeName())).toList())
                                .orElse(List.of())
                        : processedSchema.getInputType(inputField).getFields();

                inputBuffer.addAll(
                        0,
                        innerFields.stream()
                                .map(inputCondition::iterate)
                                .toList()
                );
            }

            if (!processedSchema.isInputType(inputField) || (processedSchema.hasRecord(inputField) && !isDeleteMutationQuery)) {
                var flatInput = inputCondition.applyTo(inputField);

                flatInputs.add(flatInput);

                for (var ancestor : ancestorsWithDeclaredCondition) {
                    declaredConditionsByField.get(ancestor).add(flatInput);
                }
            }
        }

        var conditionTuples = getConditionTuples(pathNameForIterableFields, flatInputs);

        conditionTuples
                .stream()
                .map(InputConditions.ConditionTuple::conditions)
                .forEach(flatInputs::removeAll);

        filterDeclaredConditions(declaredConditionsByField, conditionTuples);

        return new InputConditions(flatInputs, conditionTuples, declaredConditionsByField);
    }

    private List<InputConditions.ConditionTuple> getConditionTuples(
            List<String> iterableInputFields,
            List<InputCondition> flatInputs) {
        return iterableInputFields
                .stream()
                .map(s -> new InputConditions.ConditionTuple(
                        s, flatInputs
                        .stream()
                        .filter(condition -> condition.getNamePath().startsWith(s))
                        .collect(Collectors.toList())))
                .collect(Collectors.toList());
    }

    private void filterDeclaredConditions(
            LinkedHashMap<GenerationField, List<InputCondition>> declaredConditionsByField,
            List<InputConditions.ConditionTuple> conditionTuples) {
        for (var entry : declaredConditionsByField.entrySet()) {
            var recordConditions = entry
                    .getValue()
                    .stream()
                    .filter(InputCondition::hasRecord)
                    .toList();
            var conditions = entry
                    .getValue()
                    .stream()
                    .filter(c -> !c.hasRecord())
                    .collect(Collectors.toList());

            var filteredRecordConditions = recordConditions
                    .stream()
                    .filter(c1 -> recordConditions
                            .stream()
                            .noneMatch(c2 -> !c1.getNamePath().equals(c2.getNamePath()) &&
                                             c1.getNamePath().startsWith(c2.getNamePath())))
                    .toList();

            conditionTuples
                    .stream()
                    .map(InputConditions.ConditionTuple::conditions)
                    .forEach(conditions::removeAll);

            declaredConditionsByField.replace(
                    entry.getKey(),
                    Stream.concat(filteredRecordConditions.stream(), conditions.stream()).collect(Collectors.toList()));
        }
    }

    protected String inferFieldNamingConvention(GenerationField field) {
        if (processedSchema.hasRecord(field) && !isDeleteMutationQuery) {
            return asListedRecordNameIf(field.getName(), field.isIterableWrapped());
        }
        return field.getName();
    }

    protected CodeBlock createSeekAndLimitBlock() {
        return CodeBlock
                .builder()
                .add(".seek($T.getOrderByValues($N, $L, $N))\n", QUERY_HELPER.className, VAR_CONTEXT, VAR_ORDER_FIELDS, GraphQLReservedName.PAGINATION_AFTER.getName())
                .add(".limit($N + 1)\n", VAR_PAGE_SIZE)
                .build();
    }

    protected CodeBlock createOrderFieldsDeclarationBlock(ObjectField referenceField, String actualRefTable, String tableName) {
        var fieldsBlock = createOrderFieldsBlock(referenceField, actualRefTable, tableName);
        return !fieldsBlock.isEmpty() ? CodeBlock.declare(VAR_ORDER_FIELDS, fieldsBlock) : CodeBlock.empty();
    }

    protected CodeBlock createOrderFieldsBlock(ObjectField referenceField, String actualRefTable, String tableName) {
        var orderByField = referenceField.getOrderField();
        var hasPrimaryKey = tableHasPrimaryKey(tableName);

        if (orderByField.isEmpty() && !hasPrimaryKey) {
            return CodeBlock.empty();
        }

        var defaultOrderByFields = hasPrimaryKey
                ? getPrimaryKeyFieldsWithTableAliasBlock(actualRefTable)
                : CodeBlock.of("new $T[] {}", SORT_FIELD.className);
        var code = CodeBlock.builder();
        orderByField.ifPresentOrElse(
                it -> code.add(createCustomOrderBy(it, actualRefTable, defaultOrderByFields, tableName)),
                () -> code.add(defaultOrderByFields));
        return code.build();
    }

    @NotNull
    private CodeBlock createCustomOrderBy(InputField orderInputField, String actualRefTable, CodeBlock primaryKeyFieldsBlock, String targetTableName) {
        var orderByFieldEnum = processedSchema.getOrderByFieldEnum(orderInputField);
        var orderByFieldToDBIndexName = orderByFieldEnum
                .getFields()
                .stream()
                .collect(Collectors.toMap(AbstractField::getName, FetchDBMethodGenerator::getIndexName));

        orderByFieldToDBIndexName.forEach((orderByField, indexName) -> ValidationHandler.isTrue(TableReflection.tableHasIndex(targetTableName, indexName),
                "Table '%S' has no index '%S' necessary for sorting by '%s'", targetTableName, indexName, orderByField));

        var sortFieldMapEntries = orderByFieldToDBIndexName.entrySet()
                .stream()
                .map(it -> CodeBlock.of("Map.entry($S, $S)", it.getKey(), it.getValue()))
                .collect(CodeBlock.joining(",\n"));
        var sortFieldsMapBlock = CodeBlock.builder()
                .add("$T.ofEntries(\n", MAP.className)
                .indent()
                .add("$L)", sortFieldMapEntries)
                .add("\n")
                .unindent()
                .build();

        var orderInputFieldName = orderInputField.getName();
        return CodeBlock.builder()
                .add("$N == null\n", orderInputFieldName)
                .indent().indent()
                .add("? $L\n", primaryKeyFieldsBlock)
                .add(": $T.getSortFields($N, $L.get($N.get$L().toString()), $N.getDirection().toString())",
                        QUERY_HELPER.className, actualRefTable, sortFieldsMapBlock, orderInputFieldName, capitalize(GraphQLReservedName.ORDER_BY_FIELD.getName()), orderInputFieldName)
                .unindent().unindent()
                .build();
    }

    private static String getIndexName(OrderByEnumField enumField) {
        var indexName = enumField.getIndexName();
        if(indexName.isEmpty()) {
            ValidationHandler.addErrorMessageAndThrow("No index name found on enumField %s", enumField.getName());
        }
        return indexName.orElseThrow();
    }

    @NotNull
    protected MethodSpec.Builder getSpecBuilder(ObjectField referenceField, TypeName refTypeName, InputParser parser) {
        return getDefaultSpecBuilder(
                asQueryMethodName(referenceField.getName(), getLocalObject().getName()),
                getReturnType(referenceField, refTypeName)
        )
                .addParameterIf(!isRoot, () -> wrapSet(getKeyRowTypeName(referenceField, processedSchema)), resolverKeyParamName)
                .addParameters(getMethodParametersWithOrderField(parser))
                .addParameterIf(referenceField.hasForwardPagination(), INTEGER.className, VAR_PAGE_SIZE)
                .addParameterIf(referenceField.hasForwardPagination(), STRING.className, GraphQLReservedName.PAGINATION_AFTER.getName())
                .addParameters(getContextParameters(referenceField))
                .addParameter(SELECTION_SET.className, VAR_SELECT);
    }

    @NotNull
    private TypeName getReturnType(ObjectField referenceField, TypeName refClassName) {
        TypeName type = referenceField.hasForwardPagination() ? ParameterizedTypeName.get(PAIR.className, STRING.className, refClassName) : refClassName;

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);

        if (isRoot && !lookupExists) {
            return wrapListIf(type, referenceField.isIterableWrapped() || referenceField.hasForwardPagination());
        } else if (!isRoot) {
            return wrapMap(
                    getKeyRowTypeName(referenceField, processedSchema),
                    wrapListIf(type, referenceField.isIterableWrapped() && !processedSchema.isOrderedMultiKeyQuery(referenceField) || referenceField.hasForwardPagination()));
        } else {
            return wrapMap(STRING.className, wrapListIf(type, referenceField.hasForwardPagination()));
        }
    }
}
