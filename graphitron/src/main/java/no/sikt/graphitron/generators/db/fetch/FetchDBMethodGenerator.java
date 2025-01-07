package no.sikt.graphitron.generators.db.fetch;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.helpers.InputCondition;
import no.sikt.graphitron.definitions.helpers.InputConditions;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.mapping.Alias;
import no.sikt.graphitron.definitions.objects.InterfaceObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.definitions.sql.SQLJoinStatement;
import no.sikt.graphitron.generators.abstractions.DBMethodGenerator;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.naming.GraphQLReservedName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.GeneratorConfig.useOptionalSelects;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedRecordNameIf;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.tableHasPrimaryKey;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Abstract generator for various database fetching methods.
 */
public abstract class FetchDBMethodGenerator extends DBMethodGenerator<ObjectField> {
    protected final String idParamName = uncapitalize(getLocalObject().getName()) + "Ids";
    protected final boolean isRoot = getLocalObject().isOperationRoot();
    private static final int MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPESAFETY = 22;

    public FetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    /**
     * @param joinList List of join statements that should be applied to a select query.
     * @return Code block containing all the join statements and their conditions.
     */
    protected CodeBlock createSelectJoins(Set<SQLJoinStatement> joinList) {
        var codeBuilder = CodeBlock.builder();
        joinList.forEach(join -> codeBuilder.add(join.toJoinString()));
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
     * @param aliasSet  Set of aliases to be defined.
     * @return Code block which declares all the aliases that will be used in a select query.
     */
    protected static CodeBlock createAliasDeclarations(Set<Alias> aliasSet) {
        var codeBuilder = CodeBlock.builder();
        for (var alias : aliasSet) {
            codeBuilder.add(declare(alias.getMappingName(), CodeBlock.of("$N.as($S)", alias.getVariableValue(), alias.getShortName())));
        }
        return codeBuilder.build();
    }

    protected CodeBlock generateCorrelatedSubquery(GenerationField field, FetchContext context) {
        var isConnection = ((ObjectField) field).hasForwardPagination();
        var isMultiset = field.isIterableWrapped() || isConnection;
        Optional<CodeBlock> maybeOrderByFields = field.isResolver() && tableHasPrimaryKey(context.getTargetTableName()) ? Optional.of(CodeBlock.of(ORDER_FIELDS_NAME)) : maybeCreateOrderFieldsBlock((ObjectField) field, context.getTargetAlias(), context.getTargetTableName());
        var shouldBeOrdered = isMultiset && maybeOrderByFields.isPresent();
        var shouldHaveOrderByToken = isConnection && maybeOrderByFields.isPresent();

        CodeBlock.Builder select = CodeBlock.builder();
        select.add(shouldHaveOrderByToken ? CodeBlock.of("\n$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, context.getTargetAlias(), maybeOrderByFields.get()) : empty());

        if (context.getReferenceObject() == null) {
            select.add((processedSchema.isUnion(field)) ? generateForUnionField(field, context) : generateForScalarField(field, context));
        } else {
            select.add(generateSelectRow(context));
        }

        var where = formatWhereContents(context, "", getLocalObject().isOperationRoot(), false);
        var joins = createSelectJoins(context.getJoinSet());

        var contents = CodeBlock.builder()
                .add("$T.select($L)", DSL.className, indentIfMultiline(select.build()))
                .add("\n.from($L)", context.getCurrentJoinSequence().getFirst().getMappingName())
                .add(joins)
                .add(where)
                .add(createSelectConditions(context.getConditionList(), !where.isEmpty()))
                .add(shouldBeOrdered ? CodeBlock.of("\n.orderBy($L)", maybeOrderByFields.get()) : empty())
                .add(isConnection ? createSeekAndLimitBlock() : empty());


        return isMultiset ? field.isResolver() ? wrapInMultiset(contents.build()) : wrapInMultisetWithMapping(contents.build(), shouldHaveOrderByToken) : wrapInField(contents.build());
    }

    private CodeBlock wrapInMultisetWithMapping(CodeBlock contents, boolean hasOrderByToken) {
        if (hasOrderByToken) {
            return CodeBlock.of("$T.row($L).mapping(a0 -> a0.map($T::value2))", DSL.className, indentIfMultiline(wrapInMultiset(contents)), RECORD2.className);
        }
        return CodeBlock.of("$T.row($L).mapping(a0 -> a0.map($T::value1))", DSL.className, indentIfMultiline(wrapInMultiset(contents)), RECORD1.className);
    }

    private CodeBlock wrapInMultiset(CodeBlock contents) {
        return CodeBlock.builder()
                .add("$T.multiset(", DSL.className)
                .add(indentIfMultiline(contents))
                .add(")")
                .build();
    }

    protected CodeBlock wrapInField(CodeBlock contents) {
        return CodeBlock.builder()
                .add("$T.field(", DSL.className)
                .add(indentIfMultiline(contents))
                .add(")")
                .build();
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
                .filter(f -> !(f.isResolver() && (processedSchema.isObject(f) || processedSchema.isInterface(f))))
                .collect(Collectors.toList());

        var rowElements = new ArrayList<CodeBlock>();

        var referenceFieldSources = new HashMap<String, String>(); // Used to keep track of field sources for explicit mapping

        for (GenerationField field : fieldsWithoutSplitting) {
            var innerRowCodeAndFieldSource = getSelectCodeAndFieldSource(field, context);
            rowElements.add(innerRowCodeAndFieldSource.getLeft());
            referenceFieldSources.put(field.getName(), innerRowCodeAndFieldSource.getRight());
        }

        return createMapping(context, fieldsWithoutSplitting, referenceFieldSources, rowElements);
    }

    protected Pair<CodeBlock, String> getSelectCodeAndFieldSource(GenerationField field, FetchContext context) {
        CodeBlock innerRowCode;
        String fieldSource = null;

        if (processedSchema.isObject(field)) {
            var table = processedSchema.getObject(field.getTypeName()).getTable();
            innerRowCode = table != null && !table.equals(context.getTargetTable()) ? generateCorrelatedSubquery(field, context.nextContext(field)) : generateSelectRow(context.nextContext(field));
        }
        else if (field.hasFieldReferences()) {
            var fieldContext = context.nextContext(field);
            fieldSource = fieldContext.renderQuerySource(getLocalTable()).toString();
            innerRowCode = generateCorrelatedSubquery(field, fieldContext);
        } else {
            innerRowCode = (processedSchema.isUnion(field.getTypeName())) ? generateForUnionField(field, context) : generateForScalarField(field, context);
        }
        return Pair.of(innerRowCode, fieldSource);
    }

    protected CodeBlock createMapping(FetchContext context, List<? extends GenerationField> fieldsWithoutSplitting, HashMap<String, String> referenceFieldSources, List<CodeBlock> rowElements) {
        boolean maxTypeSafeFieldSizeIsExceeded = fieldsWithoutSplitting.size() > MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPESAFETY;

        CodeBlock regularMappingFunction = context.shouldUseEnhancedNullOnAllNullCheck()
                ? createMappingFunctionWithEnhancedNullSafety(fieldsWithoutSplitting, context.getReferenceObject().getGraphClassName(), maxTypeSafeFieldSizeIsExceeded)
                : createMappingFunction(context, fieldsWithoutSplitting, maxTypeSafeFieldSizeIsExceeded);

        var mappingContent = maxTypeSafeFieldSizeIsExceeded
                ? wrapWithExplicitMapping(regularMappingFunction, context, fieldsWithoutSplitting, referenceFieldSources)
                : regularMappingFunction;

        return CodeBlock
                .builder()
                .add(wrapRow(CodeBlock.join(rowElements, ",\n")))
                .add(mappingContent.isEmpty() ? empty() : CodeBlock.of(".mapping($L)", mappingContent))
                .build();
    }

    private CodeBlock createMappingFunction(FetchContext context, List<? extends GenerationField> fieldsWithoutTable, boolean maxTypeSafeFieldSizeIsExeeded) {
        boolean hasIdField = fieldsWithoutTable.stream().anyMatch(GenerationField::isID);
        boolean hasNullableField = fieldsWithoutTable.stream().anyMatch(GenerationField::isNullable);

        boolean canReturnNonNullableObjectWithAllFieldsNull = hasNullableField && context.getReferenceObjectField().isNonNullable() && !hasIdField;

        var mappedObjectCodeBlock = CodeBlock.of(maxTypeSafeFieldSizeIsExeeded ? "new $T(\n" : "$T::new", context.getReferenceObject().getGraphClassName());

        if (canReturnNonNullableObjectWithAllFieldsNull) {
            context.setParentContextShouldUseEnhancedNullOnAllNullCheck();
        } else if(fieldsWithoutTable.stream().anyMatch(objectField -> processedSchema.isUnion(objectField.getTypeName()))) {
            return createMappingWithUnion(context, fieldsWithoutTable);
        } else {
            return maxTypeSafeFieldSizeIsExeeded
                    ? listedNullCheck("r", mappedObjectCodeBlock)
                    : CodeBlock.of("$T.nullOnAllNull($L)", FUNCTIONS.className, mappedObjectCodeBlock);
        }
        return mappedObjectCodeBlock;
    }

    private CodeBlock createMappingWithUnion(FetchContext context, List<? extends GenerationField> fieldsWithoutTable) {
        var codeBlockArguments = CodeBlock.builder();
        var codeBlockConstructor = CodeBlock.builder();
        for(var mappingIndex = 0; mappingIndex < fieldsWithoutTable.size(); mappingIndex++) {
            var field = fieldsWithoutTable.get(mappingIndex);
            var typeName = field.getTypeName();
            var isLastField = (mappingIndex == fieldsWithoutTable.size()-1);
            if(processedSchema.isUnion(typeName)) {
                codeBlockArguments.add(unionFieldArguments(typeName, mappingIndex, isLastField));
                codeBlockConstructor.add(unionFieldConstructor(typeName, mappingIndex, isLastField));
            } else {
                var currentAlias = String.format("a%s%s", mappingIndex, (isLastField ? "" : ", "));
                codeBlockArguments.add(currentAlias);
                codeBlockConstructor.add(currentAlias);
            }
        }

        return CodeBlock.of("($L) -> new $L($L)", codeBlockArguments.build(), context.getReferenceObjectField().getTypeName(), codeBlockConstructor.build());
    }

    private CodeBlock unionFieldArguments(String typeName, int fieldIndex, boolean isLastField) {
        var codeBlockArguments = CodeBlock.builder();
        var unionFieldTypeNames = processedSchema.getUnion(typeName).getFieldTypeNames();
        for(var i = 0; i < unionFieldTypeNames.size(); i++) {
            var currentAlias = String.format("a%d_%s", fieldIndex, i);
            codeBlockArguments.add(currentAlias);
            if(i + 1 < unionFieldTypeNames.size()) {
                codeBlockArguments.add(", ");
            }
        }
        if(!isLastField) {
            codeBlockArguments.add(", ");
        }
        return codeBlockArguments.build();
    }

    private CodeBlock unionFieldConstructor(String typeName, int fieldIndex, boolean isLastField) {
        var codeBlockConstructor = CodeBlock.builder();
        var unionFieldTypeNames = processedSchema.getUnion(typeName).getFieldTypeNames();
        for(var i = 0; i < unionFieldTypeNames.size(); i++) {
            var currentAlias = String.format("a%d_%s", fieldIndex, i);
            if(i + 1 < unionFieldTypeNames.size()) {
                codeBlockConstructor.add("$L != null ? $L : ", currentAlias, currentAlias);
            } else {
                codeBlockConstructor.add("$L", currentAlias);
            }
        }
        if(!isLastField) {
            codeBlockConstructor.add(", ");
        }
        return codeBlockConstructor.build();
    }

    private CodeBlock unionFieldCondition(String typeName, int fieldIndex, boolean isLastField) {
        var codeBlockConditions = CodeBlock.builder();
        var unionFieldTypeNames = processedSchema.getUnion(typeName).getFieldTypeNames();
        for(var i = 0; i < unionFieldTypeNames.size(); i++) {
            var currentAlias = String.format("a%d_%s", fieldIndex, i);
            codeBlockConditions.add("$L != null", currentAlias);
            if(i + 1 < unionFieldTypeNames.size()) {
                codeBlockConditions.add(" && ");
            }
        }
        if(!isLastField) {
            codeBlockConditions.add(" && ");
        }
        return codeBlockConditions.build();
    }

    private CodeBlock createMappingFunctionWithEnhancedNullSafety(List<? extends GenerationField> fieldsWithoutTable, TypeName graphClassName, boolean maxTypeSafeFieldSizeIsExeeded) {
        var codeBlockArguments = CodeBlock.builder();
        var codeBlockConditions = CodeBlock.builder();
        var codeBlockConstructor = CodeBlock.builder();

        var useMemberConstructor = false;

        codeBlockArguments.add("(");
        codeBlockConstructor.add("(");

        for (int i = 0; i < fieldsWithoutTable.size(); i++) {
            String argumentName;
            var field = fieldsWithoutTable.get(i);
            var isLastField = (i == fieldsWithoutTable.size()-1);

            if (processedSchema.isUnion(field.getTypeName())) {
                useMemberConstructor = true;
                codeBlockArguments.add(unionFieldArguments(field.getTypeName(), i, isLastField));
                codeBlockConstructor.add(unionFieldConstructor(field.getTypeName(), i, isLastField));
                codeBlockConditions.add(unionFieldCondition(field.getTypeName(), i, isLastField));
                continue;
            }

            if (maxTypeSafeFieldSizeIsExeeded) {
                argumentName = "r[" + i + "]";
            } else {
                argumentName = "a" + i;
                codeBlockArguments.add(argumentName);
                codeBlockArguments.add(isLastField ? ")" : ", ");
            }

            codeBlockConstructor
                    .add(argumentName)
                    .add(isLastField ? ")" : ", ");

            if (processedSchema.isObject(field))  {
                codeBlockConditions.add("($L == null || new $T().equals($L))", argumentName, processedSchema.getObject(field).getGraphClassName(), argumentName);
            } else {
                codeBlockConditions.add("$L == null", argumentName);
            }
            codeBlockConditions.add(isLastField ? "" : " && ");
        }

        if (maxTypeSafeFieldSizeIsExeeded) {
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
     * Used when fields size exceeds {@link #MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPESAFETY}. This
     * requires the mapping function to be wrapped with explicit mapping, without type safety.
     */
    private CodeBlock wrapWithExplicitMapping(CodeBlock mappingFunction, FetchContext context, List<? extends GenerationField> fieldsWithoutTable, HashMap<String, String> sourceForReferenceFields) {
        var codeBlockBuilder = CodeBlock.builder();
        codeBlockBuilder.add("$T.class, r ->\n", context.getReferenceObject().getGraphClassName());
        codeBlockBuilder.indent().indent();
        codeBlockBuilder.add(mappingFunction);
        codeBlockBuilder.indent().indent();

        for (int i = 0; i < fieldsWithoutTable.size(); i++) {
            var field = fieldsWithoutTable.get(i);

            if (processedSchema.isObject(field)) {
                if (field.isIterableWrapped()) {
                    codeBlockBuilder.add("($T) r[$L]", ParameterizedTypeName.get(LIST.className, processedSchema.getObject(field).getGraphClassName()), i);
                } else {
                    codeBlockBuilder.add("($T) r[$L]", processedSchema.getObject(field).getGraphClassName(), i);
                }
            } else if (field.isID()) {
                codeBlockBuilder.add("($T) r[$L]", STRING.className, i);
            } else if (processedSchema.isEnum(field)) {
                var enumDefinition = processedSchema.getEnum(field);
                codeBlockBuilder.add("($T) r[$L]", enumDefinition.getGraphClassName(), i);
            } else {
                codeBlockBuilder.add("$L.$N.getDataType().convert(r[$L])", field.hasFieldReferences() ? sourceForReferenceFields.get(field.getName()) : context.renderQuerySource(getLocalTable()), field.getUpperCaseName(), i);
            }
            if (i < fieldsWithoutTable.size() - 1) {
                codeBlockBuilder.add(",\n");
            }
        }
        codeBlockBuilder.unindent().unindent().add("\n)\n").unindent().unindent();
        return codeBlockBuilder.build();
    }

    /**
     * Generate a single argument in the row method call.
     */
    protected CodeBlock generateForScalarField(GenerationField field, FetchContext context) {
        var renderedSource = context.renderQuerySource(getLocalTable());
        if (field.isID()) {
            return CodeBlock.of("$L$L", renderedSource, field.getMappingFromFieldOverride().asGetCall());
        }

        var content = CodeBlock.of("$L.$N$L", renderedSource, field.getUpperCaseName(), toJOOQEnumConverter(field.getTypeName(), processedSchema));
        return context.getShouldUseOptional() && useOptionalSelects() ? (CodeBlock.of("$N.optional($S, $L)", VARIABLE_SELECT, context.getGraphPath() + field.getName(), content)) : content;
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
    protected CodeBlock formatWhereContents(FetchContext context, String idParamName, boolean isRoot, boolean isResolverRoot) {
        boolean hasWhere = false;
        var code = CodeBlock.builder();

        if (context.getReferenceObject() instanceof InterfaceObjectDefinition) {
            code.add(".where($L.$L.in(", context.renderQuerySource(getLocalTable()), processedSchema.getInterface(context.getReferenceObjectField()).getDiscriminatingFieldName());

            code.add(
                        CodeBlock.join(
                                processedSchema
                                        .getObjects()
                                        .values()
                                        .stream()
                                        .filter(it -> it.implementsInterface(processedSchema.getInterface(context.getReferenceObjectField()).getName()))
                                        .map(it -> CodeBlock.of("$S", it.getDiscriminator()))
                                        .collect(Collectors.toList()),
                                ", ")
                );

            code.add("))\n");
            hasWhere = true;
        }

        if (!isRoot && !idParamName.isEmpty()) {
            code.add(".where($L.hasIds($N))\n", context.renderQuerySource(getLocalTable()), idParamName);
            hasWhere = true;
        }
        if (((ObjectField) context.getReferenceObjectField()).hasNonReservedInputFields() && !isResolverRoot) {
            code.add(createWhere(context, hasWhere));
        }
        return code.build();
    }

    private CodeBlock createWhere(FetchContext context, boolean hasWhere) {
        var inputConditions = getInputConditions((ObjectField) context.getReferenceObjectField());
        var flatInputs = inputConditions.getIndependentConditions();
        var codeBlockBuilder = CodeBlock.builder();
        var declaredInputConditions = inputConditions.getDeclaredConditionsByField();
        var inputsWithRecord = flatInputs
                .stream()
                .filter(it -> processedSchema.hasRecord(it.getInput()))
                .toList();
        var inputsWithoutRecord = flatInputs
                .stream()
                .filter(it -> !processedSchema.hasRecord(it.getInput()))
                .toList();

        for (var inputCondition : inputsWithoutRecord) {
            var field = inputCondition.getInput();
            var name = inputCondition.getNameWithPath();
            var checks = inputCondition.getChecksAsSequence();
            var checksNotEmpty = !checks.isEmpty();
            var renderedSequence = context.iterateJoinSequenceFor(field).render();

            if (!inputCondition.isOverriddenByAncestors() && !field.hasOverridingCondition()) {
                codeBlockBuilder.add(hasWhere ? ".and(" : ".where(");
                if (checksNotEmpty) {
                    codeBlockBuilder.add(checks + " ? ");
                }

                codeBlockBuilder.add(renderedSequence);

                if (field.isID()) {
                    codeBlockBuilder
                            .add(field.getMappingFromFieldOverride().asHasCall(name, field.isIterableWrapped()));
                } else {
                    codeBlockBuilder
                            .add(".$N$L", field.getUpperCaseName(), toJOOQEnumConverter(
                                    field.getTypeName(), processedSchema))
                            .add(field.isIterableWrapped() ? ".in($L)" : ".eq($L)", name);
                }

                if (checksNotEmpty) {
                    codeBlockBuilder.add(" : $T.noCondition()", DSL.className);
                }

                codeBlockBuilder.add(")\n");
            }

            if (!codeBlockBuilder.isEmpty()) {
                hasWhere = true;
            }

            if (field.hasCondition()) {
                var conditionInputs = List.of(renderedSequence, getCheckedNameWithPath(inputCondition));
                codeBlockBuilder.add(wrapCondition(field.getCondition().formatToString(conditionInputs), hasWhere));
            }

            if (!codeBlockBuilder.isEmpty()) {
                hasWhere = true;
            }
        }

        for (var conditionTuple : inputConditions.getConditionTuples()) {
            if (inputConditions.getConditionTuples()
                    .stream()
                    .anyMatch(it -> conditionTuple.getPath().startsWith(it.getPath())
                            && conditionTuple.getPath().length() > it.getPath().length())) {
                continue;
            }

            codeBlockBuilder.add(createTupleCondition(
                    context, hasWhere, conditionTuple.getPath(), conditionTuple.getConditions()));

            if (!codeBlockBuilder.isEmpty()) {
                hasWhere = true;
            }
        }

        for (var inputCondition : inputsWithRecord) {
            var field = inputCondition.getInput();

            if (field.hasCondition()) {
                var conditionInputs = List.of(
                        context.iterateJoinSequenceFor(field).render(), getCheckedNameWithPath(inputCondition));
                codeBlockBuilder.add(wrapCondition(field.getCondition().formatToString(conditionInputs), hasWhere));

                hasWhere = true;
            }
        }

        for (var condition : declaredInputConditions.entrySet()) {
            var inputs = Stream.concat(
                    Stream.of(context.getCurrentJoinSequence().render()),
                    condition.getValue().stream().map(this::getCheckedNameWithPath)
            ).collect(Collectors.toList());

            codeBlockBuilder.add(wrapCondition(
                    condition.getKey().getCondition().formatToString(inputs),
                    hasWhere));

            hasWhere = true;
        }

        return codeBlockBuilder.build();
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
            boolean hasWhere,
            String argumentInputFieldName,
            List<InputCondition> conditions) {

        var selectedConditions = new HashSet<InputCondition>();
        var tupleFieldBlocks = new ArrayList<CodeBlock>();
        var tupleVariableBlocks = new ArrayList<CodeBlock>();

        for (var condition : conditions) {
            var field = condition.getInput();
            var fieldSequence = context.iterateJoinSequenceFor(field).render();
            var unpacked = CodeBlock.of(
                    VARIABLE_INTERNAL_ITERATION + condition
                            .getNameWithPathString().replaceFirst(Pattern.quote(argumentInputFieldName), ""));

            if (!field.hasOverridingCondition() && !condition.isOverriddenByAncestors()) {
                var enumHandling = toJOOQEnumConverter(field.getTypeName(), processedSchema);
                var tupleBlock = CodeBlock.builder().add(fieldSequence);

                if (field.isID()) {
                    tupleBlock.add(field.getMappingFromFieldOverride().asGetCall());
                } else {
                    tupleBlock.add(".$N$L", field.getUpperCaseName(), enumHandling);
                }

                tupleFieldBlocks.add(tupleBlock.build());
                tupleVariableBlocks.add(inline(unpacked));
                selectedConditions.add(condition);
            }

            if (field.hasCondition() && !condition.isOverriddenByAncestors()) {
                tupleFieldBlocks.add(CodeBlock.of("$T.trueCondition()", DSL.className));

                var conditionInputs = List.of(fieldSequence, unpacked);
                tupleVariableBlocks.add(field.getCondition().formatToString(conditionInputs));
                selectedConditions.add(condition);
            }
        }

        if (tupleFieldBlocks.isEmpty()) {
            return empty();
        }

        var checks = String.join(" && ", selectedConditions
                .stream()
                .map(InputCondition::getChecksAsSequence)
                .collect(Collectors.toSet()));

        return wrapCondition(
                CodeBlock
                        .builder()
                        .add(checks.isEmpty() ? "" : "$L ?\n", checks)
                        .indent()
                        .indent()
                        .add(wrapRow(CodeBlock.join(tupleFieldBlocks, ",\n")))
                        .add(".in(\n")
                        .indent()
                        .indent()
                        .add("$N.stream().map($L ->\n", argumentInputFieldName, VARIABLE_INTERNAL_ITERATION)
                        .indent()
                        .indent()
                        .add(wrapRow(CodeBlock.join(tupleVariableBlocks, ",\n")))
                        .unindent()
                        .unindent()
                        .add("\n)")
                        .unindent()
                        .unindent()
                        .add("$L\n) : $T.noCondition()", collectToList(), DSL.className)
                        .unindent()
                        .unindent()
                        .build(),
                hasWhere
        );
    }

    private CodeBlock wrapCondition(CodeBlock condition, boolean hasWhere) {
        if (condition.isEmpty()) {
            return empty();
        }

        return CodeBlock
                .builder()
                .add((hasWhere ? ".and(" : ".where("))
                .add(indentIfMultiline(condition))
                .add(")\n")
                .build();
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
                        processedSchema.hasRecord(it),
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

                inputBuffer.addAll(
                        0,
                        processedSchema
                                .getInputType(inputField)
                                .getFields()
                                .stream()
                                .map(inputCondition::iterate)
                                .toList()
                );
            }

            if (!processedSchema.isInputType(inputField) || processedSchema.hasRecord(inputField)) {
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
                .map(InputConditions.ConditionTuple::getConditions)
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
                    .map(InputConditions.ConditionTuple::getConditions)
                    .forEach(conditions::removeAll);

            declaredConditionsByField.replace(
                    entry.getKey(),
                    Stream.concat(filteredRecordConditions.stream(), conditions.stream()).collect(Collectors.toList()));
        }
    }

    protected String inferFieldNamingConvention(GenerationField field) {
        if (processedSchema.hasRecord(field)) {
            return asListedRecordNameIf(field.getName(), field.isIterableWrapped());
        }
        return field.getName();
    }

    protected CodeBlock createSeekAndLimitBlock() {
        var code = CodeBlock.builder();

        code.add(".seek($T.getOrderByValues($N, $L, $N))\n", QUERY_HELPER.className, CONTEXT_NAME, ORDER_FIELDS_NAME, GraphQLReservedName.PAGINATION_AFTER.getName());
        code.add(".limit($N + 1)\n", PAGE_SIZE_NAME);
        return code.build();
    }

    protected Optional<CodeBlock> maybeCreateOrderFieldsDeclarationBlock(ObjectField referenceField, String actualRefTable, String tableName) {
        return maybeCreateOrderFieldsBlock(referenceField, actualRefTable, tableName).map(codeBlock -> declare(ORDER_FIELDS_NAME, codeBlock));
    }

    protected Optional<CodeBlock> maybeCreateOrderFieldsBlock(ObjectField referenceField, String actualRefTable, String tableName) {
        var orderByField = referenceField.getOrderField();
        var hasPrimaryKey = tableHasPrimaryKey(tableName);

        if (orderByField.isEmpty() && !hasPrimaryKey) return Optional.empty();

        var defaultOrderByFields = hasPrimaryKey ? getPrimaryKeyFieldsBlock(actualRefTable) : CodeBlock.of("new $T[] {}", SORT_FIELD.className);
        var code = CodeBlock.builder();
        orderByField.ifPresentOrElse(
                it -> code.add(createCustomOrderBy(it, actualRefTable, defaultOrderByFields, tableName)),
                () -> code.add("$L", defaultOrderByFields));
        return Optional.of(code.build());
    }

    @NotNull
    private CodeBlock createCustomOrderBy(InputField orderInputField, String actualRefTable, CodeBlock primaryKeyFieldsBlock, String targetTableName) {
        var orderByFieldEnum = processedSchema.getOrderByFieldEnum(orderInputField);
        var orderByFieldToDBIndexName = orderByFieldEnum
                .getFields()
                .stream()
                .collect(Collectors.toMap(AbstractField::getName, enumField -> enumField.getIndexName().orElseThrow()));

        orderByFieldToDBIndexName.forEach((orderByField, indexName) -> Validate.isTrue(TableReflection.tableHasIndex(targetTableName, indexName),
                "Table '%S' has no index '%S' necessary for sorting by '%s'", targetTableName, indexName, orderByField));

        var sortFieldMapEntries = orderByFieldToDBIndexName.entrySet()
                .stream()
                .map(it -> CodeBlock.of("Map.entry($S, $S)", it.getKey(), it.getValue()))
                .collect(CodeBlock.joining(",\n"));
        var sortFieldsMapBlock = CodeBlock.builder().add("$T.ofEntries(\n", MAP.className)
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
                .add(": $T.getSortFields($N.getIndexes(), $L.get($N.get$L().toString()), $N.getDirection().toString())",
                        QUERY_HELPER.className, actualRefTable, sortFieldsMapBlock, orderInputFieldName, capitalize(GraphQLReservedName.ORDER_BY_FIELD.getName()), orderInputFieldName)
                .unindent().unindent()
                .build();
    }

    protected static CodeBlock getPrimaryKeyFieldsBlock(String actualRefTable) {
        return CodeBlock.of("$N.fields($N.getPrimaryKey().getFieldsArray())", actualRefTable, actualRefTable);
    }

    @NotNull
    protected MethodSpec.Builder getSpecBuilder(ObjectField referenceField, TypeName refTypeName, InputParser parser) {
        var spec = getDefaultSpecBuilder(
                asQueryMethodName(referenceField.getName(), getLocalObject().getName()),
                getReturnType(referenceField, refTypeName)
        );
        if (!isRoot) {
            spec.addParameter(getStringSetTypeName(), idParamName);
        }

        parser.getMethodInputsWithOrderField().forEach((key, value) -> spec.addParameter(iterableWrapType(value), key));

        if (referenceField.hasForwardPagination()) {
            spec
                    .addParameter(INTEGER.className, PAGE_SIZE_NAME)
                    .addParameter(STRING.className, GraphQLReservedName.PAGINATION_AFTER.getName());
        }
        return spec.addParameter(SELECTION_SET.className, VARIABLE_SELECT);
    }

    @NotNull
    private TypeName getReturnType(ObjectField referenceField, TypeName refClassName) {
        TypeName type = referenceField.hasForwardPagination() ? ParameterizedTypeName.get(PAIR.className, STRING.className, refClassName) : refClassName;

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);

        if (isRoot && !lookupExists) {
            return wrapListIf(type, referenceField.isIterableWrapped() || referenceField.hasForwardPagination());
        } else {
            return wrapMap(STRING.className, wrapListIf(type, referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()));
        }
    }
}
