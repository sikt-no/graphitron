package no.fellesstudentsystem.graphitron.generators.db.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;
import graphql.language.FieldDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputCondition;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputConditions;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLJoinStatement;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asListedRecordNameIf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.VARIABLE_INTERNAL_ITERATION;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.VARIABLE_SELECT;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.FUNCTIONS;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Abstract generator for various database fetching methods.
 */
public abstract class FetchDBMethodGenerator extends DBMethodGenerator<ObjectField> {
    final String idParamName = uncapitalize(getLocalObject().getName()) + "Ids";
    final boolean isRoot = getLocalObject().isOperationRoot();
    private static final int MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPESAFETY = 22;

    public FetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    /**
     * @param joinList  List of join statements that should have their aliases defined.
     * @return Code block which declares all the aliases that will be used in a select query.
     */
    protected CodeBlock createSelectAliases(Set<SQLJoinStatement> joinList) {
        var codeBuilder = CodeBlock.builder();
        for (var join : joinList) {
            var alias = join.getJoinAlias();
            codeBuilder.add(declare(alias.getMappingName(), CodeBlock.of("$N.as($S)", alias.getVariableValue(), alias.getShortName())));
        }
        return codeBuilder.build();
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
    protected CodeBlock createSelectConditions(List<CodeBlock> conditionList) {
        return CodeBlock.join(conditionList, "\n");
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

        var fieldsWithoutSplittingSize = fieldsWithoutSplitting.size();

        var rowElements = new ArrayList<CodeBlock>();
        for (GenerationField field : fieldsWithoutSplitting) {
            var innerRowCode = processedSchema.isObject(field)
                    ? generateSelectRow(context.nextContext(field))
                    : (processedSchema.isUnion(field.getTypeName())) ? generateForUnionField(field, context) : generateForScalarField(field, context);
            rowElements.add(innerRowCode);
        }

        boolean maxTypeSafeFieldSizeIsExceeded = fieldsWithoutSplittingSize > MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPESAFETY;

        CodeBlock regularMappingFunction = context.shouldUseEnhancedNullOnAllNullCheck()
                ? createMappingFunctionWithEnhancedNullSafety(fieldsWithoutSplitting, context.getReferenceObject().getGraphClassName(), maxTypeSafeFieldSizeIsExceeded)
                : createMappingFunction(context, fieldsWithoutSplitting, maxTypeSafeFieldSizeIsExceeded);

        return CodeBlock
                .builder()
                .add(wrapRow(CodeBlock.join(rowElements, ",\n")))
                .add(".mapping(")
                .add(maxTypeSafeFieldSizeIsExceeded ? wrapWithExplicitMapping(regularMappingFunction, context, fieldsWithoutSplitting) : regularMappingFunction)
                .add(")")
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
                    ? CodeBlock.of("$T.stream(r).allMatch($T::isNull) ? null : $L", ARRAYS.className, OBJECTS.className, mappedObjectCodeBlock)
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

            codeBlockConstructor.add(argumentName);
            codeBlockConstructor.add(isLastField ? ")" : ", ");

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
    private CodeBlock wrapWithExplicitMapping(CodeBlock mappingFunction, FetchContext context, List<? extends GenerationField> fieldsWithoutTable) {
        var codeBlockBuilder = CodeBlock.builder();
        codeBlockBuilder.add("$T.class, r ->\n", context.getReferenceObject().getGraphClassName());
        codeBlockBuilder.indent().indent();
        codeBlockBuilder.add(mappingFunction);
        codeBlockBuilder.indent().indent();

        for (int i = 0; i < fieldsWithoutTable.size(); i++) {
            var field = fieldsWithoutTable.get(i);

            if (processedSchema.isObject(field)) {
                codeBlockBuilder.add("($T) r[$L]", processedSchema.getObject(field).getGraphClassName(), i);
            } else if (field.isID()) {
                codeBlockBuilder.add("($T) r[$L]", STRING.className, i);
            } else if (processedSchema.isEnum(field)) {
                var enumDefinition = processedSchema.getEnum(field);
                codeBlockBuilder.add("($T) r[$L]", enumDefinition.getGraphClassName(), i);
            } else {
                var fieldSource = context.iterateJoinSequenceFor(field);
                codeBlockBuilder.add("$L.$N.getDataType().convert(r[$L])", fieldSource.render(), field.getUpperCaseName(), i);
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
    private CodeBlock generateForScalarField(GenerationField field, FetchContext context) {
        var renderedSource = context.iterateJoinSequenceFor(field).render();
        if (field.isID()) {
            return CodeBlock.of("$L$L", renderedSource, field.getMappingFromFieldOverride().asGetCall());
        }
        var content = CodeBlock.of("$L.$N$L", renderedSource, field.getUpperCaseName(), toJOOQEnumConverter(field.getTypeName(), false, processedSchema));
        return context.getShouldUseOptional() ? (CodeBlock.of("$N.optional($S, $L)", VARIABLE_SELECT, context.getGraphPath() + field.getName(), content)) : content;
    }

    /**
     * Generate select row for each object within the union field
     */
    private CodeBlock generateForUnionField(GenerationField field, FetchContext context) {
        var codeBlock = CodeBlock.builder();
        var unionField = processedSchema.getUnion(field.getTypeName());

        var counter = 0;
        for(var fieldObject : unionField.getFieldTypeNames()) {
            var object = processedSchema.getObject(fieldObject).getName();
            var objectField = new ObjectField(new FieldDefinition(object, new graphql.language.TypeName(object)), object);
            codeBlock.add(generateSelectRow(context.nextContext(objectField).withShouldUseOptional(false)));
            if (counter + 1 < unionField.getFieldTypeNames().size()) {
                codeBlock.add(",\n");
            }
            counter++;
        }
        return codeBlock.build();
    }

    /**
     * @return Formatted CodeBlock for the where-statement and surrounding code. Applies conditions and joins.
     */
    protected CodeBlock formatWhereContents(FetchContext context) {
        var code = CodeBlock.builder().add(".where(");

        if (!isRoot) {
            code.add("$L.hasIds($N))\n", context.renderQuerySource(getLocalTable()), idParamName);
        }
        if (((ObjectField) context.getReferenceObjectField()).hasNonReservedInputFields()) {
            code.add(createWhere(context, !isRoot));
        } else if (isRoot) {
            return empty();
        }
        return code.build();
    }

    private CodeBlock createWhere(FetchContext context, boolean hasWhere) {
        var referenceField = context.getReferenceObjectField();
        var inputConditions = getInputConditions(((ObjectField)referenceField).getNonReservedArguments());
        var flatInputs = inputConditions.getIndependentConditions();
        var codeBlockBuilder = CodeBlock.builder();

        var inputsWithoutRecord = flatInputs.stream().filter(it -> !processedSchema.hasRecord(it.getInput())).collect(Collectors.toList());
        for (var inputCondition : inputsWithoutRecord) {
            var field = inputCondition.getInput();
            var name = inputCondition.getNameWithPath();
            var checks = inputCondition.getChecksAsSequence();
            var checksNotEmpty = !checks.isEmpty();
            var renderedSequence = context.iterateJoinSequenceFor(field).render();
            if (!referenceField.hasOverridingCondition() && !field.hasOverridingCondition()) {
                if (hasWhere) {
                    codeBlockBuilder.add(".and(");
                }
                if (checksNotEmpty) {
                    codeBlockBuilder.add(checks + " ? ");
                }
                codeBlockBuilder.add(renderedSequence);
                if (field.isID()) {
                    codeBlockBuilder.add(field.getMappingFromFieldOverride().asHasCall(name, field.isIterableWrapped()));
                } else {
                    codeBlockBuilder
                            .add(".$N$L", field.getUpperCaseName(), toJOOQEnumConverter(field.getTypeName(), field.isIterableWrapped(), processedSchema))
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

        if (!referenceField.hasOverridingCondition()) {
            for (var conditionTuple : inputConditions.getConditionTuples()) {
                codeBlockBuilder.add(createTupleCondition(context, hasWhere, conditionTuple.getPath(), conditionTuple.getConditions()));
                hasWhere = true;
            }
        }

        if (referenceField.hasCondition()) {
            var inputsWithRecords = flatInputs.stream().filter(it -> processedSchema.hasRecord(it.getInput())).collect(Collectors.toList());
            var inputs = Stream.concat(
                    Stream.of(context.getCurrentJoinSequence().render()),
                    Stream.concat(
                            inputsWithRecords
                                    .stream()
                                    .filter(c1 -> inputsWithRecords.stream().noneMatch(c2 -> !c1.getNamePath().equals(c2.getNamePath()) && c1.getNamePath().startsWith(c2.getNamePath())))
                                    .map(this::getCheckedNameWithPath),
                            inputsWithoutRecord
                                    .stream()
                                    .filter(c1 -> inputsWithRecords.stream().noneMatch(c2 -> c1.getNamePath().startsWith(c2.getNamePath())))
                                    .map(this::getCheckedNameWithPath)
                    )
            ).collect(Collectors.toList());
            codeBlockBuilder.add(wrapCondition(referenceField.getCondition().formatToString(inputs), hasWhere));
        }
        return codeBlockBuilder.build();
    }

    private CodeBlock getCheckedNameWithPath(InputCondition condition) {
        var nameWithPath = condition.getNameWithPath();
        var checks = condition.getChecksAsSequence();
        var enumConverter = toGraphEnumConverter(condition.getInput().getTypeName(), nameWithPath, condition.getInput().isIterableWrapped(), true, processedSchema);
        return CodeBlock.of(
                !checks.isEmpty() && !condition.getNamePath().isEmpty() ? checks + " ? $L : null" : "$L",
                enumConverter.isEmpty() ? nameWithPath : enumConverter
        );
    }

    private CodeBlock createTupleCondition(FetchContext context, boolean hasWhere, String argumentInputFieldName, List<InputCondition> conditions) {
        var checks = String.join(" && ", conditions.stream().map(InputCondition::getChecksAsSequence).collect(Collectors.toSet()));

        var tupleFieldBlocks = new ArrayList<CodeBlock>();
        var tupleVariableBlocks = new ArrayList<CodeBlock>();
        for (var condition : conditions) {
            var field = condition.getInput();
            var fieldSequence = context.iterateJoinSequenceFor(field).render();
            var unpacked = CodeBlock.of(VARIABLE_INTERNAL_ITERATION + condition.getNameWithPathString().replaceFirst(Pattern.quote(argumentInputFieldName), ""));

            if (!field.hasOverridingCondition()) {
                var enumHandling = toJOOQEnumConverter(field.getTypeName(), field.isIterableWrapped(), processedSchema);
                var tupleBlock = CodeBlock.builder().add(fieldSequence);
                if (field.isID()) {
                    tupleBlock.add(field.getMappingFromFieldOverride().asGetCall());
                } else {
                    tupleBlock.add(".$N$L", field.getUpperCaseName(), enumHandling);
                }
                tupleFieldBlocks.add(tupleBlock.build());
                tupleVariableBlocks.add(inline(unpacked));
            }

            if (field.hasCondition()) {
                tupleFieldBlocks.add(CodeBlock.of("$T.trueCondition()", DSL.className));

                var conditionInputs = List.of(fieldSequence, unpacked);
                tupleVariableBlocks.add(field.getCondition().formatToString(conditionInputs));
            }
        }

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
                .add((hasWhere ? ".and(" : ""))
                .add(indentIfMultiline(condition))
                .add(")\n")
                .build();
    }

    @NotNull
    protected InputConditions getInputConditions(List<? extends InputField> inputFields) {
        var iterableInputFieldNamePaths = new ArrayList<String>();
        var flatInputs = new ArrayList<InputCondition>();
        var inputBuffer = inputFields
                .stream()
                .map(it -> new InputCondition(it, inferFieldNamingConvention(it), processedSchema.hasRecord(it)))
                .collect(Collectors.toCollection(LinkedList::new));
        while (!inputBuffer.isEmpty() && inputBuffer.size() < Integer.MAX_VALUE) {
            var inputCondition = inputBuffer.poll();
            var inputField = inputCondition.getInput();

            if (inputField.isIterableWrapped() && processedSchema.isInputType(inputField)) {
                iterableInputFieldNamePaths.add(inputCondition.getNameWithPathString());
            }

            if (processedSchema.isInputType(inputField)) {
                inputBuffer.addAll(
                        0,
                        processedSchema
                                .getInputType(inputField)
                                .getFields()
                                .stream()
                                .map(inputCondition::iterate)
                                .collect(Collectors.toList())
                );
            }
            if (!processedSchema.isInputType(inputField) || processedSchema.hasRecord(inputField)) {
                flatInputs.add(inputCondition.applyTo(inputField));
            }
        }

        var conditionTuples = getConditionTuples(iterableInputFieldNamePaths, flatInputs);
        conditionTuples.stream()
                .map(InputConditions.ConditionTuple::getConditions)
                .forEach(flatInputs::removeAll);

        return new InputConditions(flatInputs, conditionTuples);
    }

    private List<InputConditions.ConditionTuple> getConditionTuples(List<String> iterableInputFieldNamePaths, ArrayList<InputCondition> flatInputs) {
        return iterableInputFieldNamePaths.stream()
                .map(s -> new InputConditions.ConditionTuple(s, flatInputs.stream()
                        .filter(condition -> condition.getNamePath().startsWith(s))
                        .collect(Collectors.toList())
                )).collect(Collectors.toList());
    }

    protected String inferFieldNamingConvention(GenerationField field) {
        if (processedSchema.hasRecord(field)) {
            return asListedRecordNameIf(field.getName(), field.isIterableWrapped());
        }
        return field.getName();
    }
}
