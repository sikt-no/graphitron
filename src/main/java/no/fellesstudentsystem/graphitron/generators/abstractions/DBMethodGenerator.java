package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import graphql.language.FieldDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputCondition;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputConditions;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLJoinStatement;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;
import org.jooq.Record1;

import javax.lang.model.element.Modifier;
import java.util.*;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.getKeyFields;
import static org.apache.commons.lang3.StringUtils.*;

/**
 * Generic select query generation functionality is contained within this class.
 * @param <T> Field type that this generator operates on.
 */
abstract public class DBMethodGenerator<T extends ObjectField> extends AbstractMethodGenerator<T> {
    protected final static String SELECTION_NAME = "select";
    private static final int MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPESAFETY = 22;

    public DBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return MethodSpec
                .methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType)
                .addParameter(DSL_CONTEXT.className, Dependency.CONTEXT_NAME);
    }

    /**
     * @param joinList  List of join statements that should have their aliases defined.
     * @return Code block which declares all the aliases that will be used in a select query.
     */
    protected CodeBlock createSelectAliases(Set<SQLJoinStatement> joinList) {
        var codeBuilder = CodeBlock.builder();
        for (var join : joinList) {
            var alias = join.getJoinAlias();
            codeBuilder.addStatement("var $L = $N.as($S)", alias.getMappingName(), join.getJoinTargetTable().getMappingName(), alias.getShortName());
        }
        return codeBuilder.build();
    }

    /**
     * @param context Contains the list of join statements that should be applied to a select query.
     * @return Code block containing all the join statements and their conditions.
     */
    protected CodeBlock createSelectJoins(FetchContext context) {
        var codeBuilder = CodeBlock.builder();
        var joinList = new LinkedHashSet<>(context.getJoinSet());

        var currentJoinList = new LinkedHashSet<SQLJoinStatement>();
        if(!joinList.isEmpty() && !context.getMultisetObjectFields().isEmpty()) {
            var listOfMultisetObjectTables = context.getMultisetObjectFields().stream().map(it -> context.nextContext(it).getReferenceTable().getMappingName()).collect(Collectors.toList());
            for(var currentJoin : joinList) {
                var joinElement = currentJoin.getJoinSequence().stream().reduce((first, second) -> second);
                if(joinElement.isPresent() && !listOfMultisetObjectTables.contains(joinElement.get().getTable().getMappingName())) {
                    currentJoinList.add(currentJoin);
                }
            }
        } else {
            currentJoinList = joinList;
        }

        currentJoinList.forEach(join -> codeBuilder.add(join.toJoinString()));
        return codeBuilder.build();
    }

    protected CodeBlock createJoinsForMultisetWhere(FetchContext context) {
        var codeBuilder = CodeBlock.builder();
        var joinList = context.getJoinSet();
        var currentReferenceTable = context.getReferenceTable().getMappingName();

        for(var currentJoin : joinList) {
            var joinElement = currentJoin.getJoinSequence().stream().reduce((first, second) -> second);
            if(joinElement.isPresent() && currentReferenceTable.equals(joinElement.get().getTable().getMappingName())) {
                codeBuilder.add(currentJoin.toJoinString());
            }
        }
        return codeBuilder.build();
    }

    /**
     * @param context contains a map of conditional statements that should be appended after the where-statement.
     * @return Code block which declares all the extra conditions that will be used in a select query.
     */
    protected CodeBlock createSelectConditions(FetchContext context) {
        var codeBuilder = CodeBlock.builder();
        var conditionMap = context.getConditionSet();

        if(conditionMap.isEmpty()) {
            return codeBuilder.build();
        }

        for(var conditionMultisetContext : conditionMap.keySet()) {
            var currentConditionList = conditionMap.get(conditionMultisetContext);
            if(currentConditionList.isEmpty()) {
                break;
            }
            for(var currentCondition : currentConditionList) {
                if(conditionMultisetContext == null || Objects.equals(conditionMultisetContext.getGraphPath(), context.getGraphPath())) {
                    codeBuilder.add(".and($L)\n", currentCondition);
                }
            }
        }

        return codeBuilder.build();
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
        var fieldsOfTypeListWithoutSplitting = fieldsWithoutSplitting
                .stream()
                .filter(processedSchema::isObject)
                .filter(AbstractField::isIterableWrapped)
                .collect(Collectors.toList());

        context.setMultisetObjectFields(fieldsOfTypeListWithoutSplitting);

        var rowContentCode = CodeBlock.builder().indent().indent();

        for (int i = 0; i < fieldsWithoutSplittingSize; i++) {
            var field = fieldsWithoutSplitting.get(i);
            if(field.isIterableWrapped() && processedSchema.isObject(field)) {
                var multisetContext = context.nextContext(field);
                var multisetContent = generateMultisetSelectRow(multisetContext);
                rowContentCode.add(multisetContent)
                .add((i < fieldsWithoutSplittingSize - 1) ? ",\n" : "\n");
                continue;
            }
            var innerRowCode = processedSchema.isObject(field)
                    ? generateSelectRow(context.nextContext(field))
                    : (processedSchema.isUnion(field.getTypeName())) ? generateForUnionField(field, context) : generateForScalarField(field, context);
            if(context.requiresAlias()) {
                rowContentCode.add("$L.as($S)", innerRowCode, field.getName());
            } else {
                rowContentCode.add("$L", innerRowCode);
            }
            rowContentCode.add((i < fieldsWithoutSplittingSize - 1) ? ",\n" : "\n");
        }

        boolean maxTypeSafeFieldSizeIsExceeded = fieldsWithoutSplittingSize > MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPESAFETY;

        CodeBlock regularMappingFunction = context.shouldUseEnhancedNullOnAllNullCheck()
                ? createMappingFunctionWithEnhancedNullSafety(fieldsWithoutSplitting, context.getReferenceObject().getGraphClassName(), maxTypeSafeFieldSizeIsExceeded)
                : createMappingFunction(context, fieldsWithoutSplitting, maxTypeSafeFieldSizeIsExceeded);

        var useSpecialMapping = !fieldsOfTypeListWithoutSplitting.isEmpty();

        rowContentCode
                .unindent()
                .unindent()
                .add(").mapping(")
                .add(useSpecialMapping ? generateMultisetMapping(context, fieldsWithoutSplitting) : maxTypeSafeFieldSizeIsExceeded ? wrapWithExplicitMapping(regularMappingFunction, context, fieldsWithoutSplitting) : regularMappingFunction);

        return CodeBlock
                .builder()
                .add("$T.row(\n$L)", DSL.className, rowContentCode.build())
                .build();
    }

    public CodeBlock generateMultisetMapping(FetchContext context, List<ObjectField> fieldsWithoutSplitting) {
        var mappingFrom = CodeBlock.builder().add("(");
        var mappingTo = CodeBlock.builder().add(" -> new $T(", context.getReferenceObject().getGraphClassName());
        var mappingIndex = 0;
        for(var field : fieldsWithoutSplitting){
            if(field.isIterableWrapped()) {
                mappingTo.add(createListMapping(mappingIndex));
            } else {
                mappingTo.add(("a" + mappingIndex));
            }
            mappingFrom.add("a" + mappingIndex);
            mappingIndex++;
            if(mappingIndex >= fieldsWithoutSplitting.size()) {
                mappingFrom.add(")");
                mappingTo.add(")");
            } else {
                mappingFrom.add(", ");
                mappingTo.add((", "));
            }

        }

        return mappingFrom.add(mappingTo.build()).build();
    }

    public CodeBlock generateMultisetSelectRow(FetchContext context) {
        var multisetFrom = context.getReferenceTable() != null ? context.getReferenceTable().getMappingName() : null;
        var multisetRowContent = generateSelectRow(context.toMultisetContext());
        var multisetJoin = createJoinsForMultisetWhere(context);
        var multisetWhere = getMultisetSelectWhereStatement(context);
        return CodeBlock.builder()
                .add("$T.multiset(\n", DSL.className)
                .indent().add("$T.select(\n", DSL.className)
                .indent().add(multisetRowContent)
                .unindent().add("\n)\n")
                .add(".from($L)\n", multisetFrom)
                .add(multisetJoin)
                .add(multisetWhere)
                .add(createSelectConditions(context))
                .unindent().add(")").build();
    }

    public CodeBlock getMultisetSelectWhereStatement(FetchContext context) {
        var keyFields = getKeyFields(context.getKeyForMapping());

        if(keyFields.isEmpty()) {
            throw new IllegalArgumentException("The multiset context in " + context.getPreviousTable().getMappingName() + " is set to generate a where statement but cannot find a path between " + context.getPreviousTable().getMappingName() + " and " + context.getReferenceObject().getName().toUpperCase());
        }

        var multisetJoinAlias = context.generateMultisetAliasFromJoinSequence(context.getCurrentJoinSequence());

        var stringMap = keyFields.get().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().getQualifiedName().toString().toUpperCase().replace("\"", ""),
                        e -> (multisetJoinAlias.map(
                                codeBlock -> codeBlock + "." + e.getValue().getName().toUpperCase().replace("\"", ""))
                                .orElseGet(() -> e.getValue().getQualifiedName().toString().toUpperCase().replace("\"", ""))
                        )
                ));

        var conditionFields = context.getReferenceObjectField().getFieldReferences().stream().filter(it -> it.getTableCondition() != null).collect(Collectors.toList());

        if(conditionFields.size() > 0) {
            throw new IllegalArgumentException(String.format("List of type %s requires the @SplitQuery directive to be able to contain @condition in a @reference within a list", context.getReferenceObject().getTypeDefinition().getName()));
        }

        var whereContent = CodeBlock.builder();
        var fromTable = context.getPreviousTable();
        var joinAlias = context.getJoinSet().stream().filter(it -> fromTable.equals(it.getJoinTargetTable())).map(SQLJoinStatement::getJoinAlias).findFirst();
        var i = 0;
        for(var field : stringMap.entrySet()) {
            whereContent.add(".$L($L.eq($L))\n", (i == 0) ? "where" : "and", field.getKey(), joinAlias.map(alias -> alias.getMappingName() + "." + Arrays.stream(field.getValue().split("[.]")).reduce((first, second) -> second).orElseGet(field::getValue)).orElseGet(field::getValue));
            i++;
        }
        if (context.getReferenceObjectField().hasNonReservedInputFields()) {
            throw new IllegalArgumentException("Input arguments is not supported for multiset lists in " + context.getPreviousTable().getMappingName() + "");
        }

        return whereContent.build();
    }

    private CodeBlock createMappingFunction(FetchContext context, List<ObjectField> fieldsWithoutTable, boolean maxTypeSafeFieldSizeIsExeeded) {
        boolean hasIdField = fieldsWithoutTable.stream().anyMatch(ObjectField::isID);
        boolean hasNullableField = fieldsWithoutTable.stream().anyMatch(ObjectField::isNullable);

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

    protected CodeBlock createListMapping(int mappingIndex) {
        var codeBlock = CodeBlock.builder();
        codeBlock.add("a$L.map", mappingIndex);
        codeBlock.add("($T::value1)", Record1.class);
        return codeBlock.build();
    }

    private CodeBlock createMappingWithUnion(FetchContext context, List<ObjectField> fieldsWithoutTable) {
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

    private CodeBlock createMappingFunctionWithEnhancedNullSafety(List<ObjectField> fieldsWithoutTable, TypeName graphClassName, boolean maxTypeSafeFieldSizeIsExeeded) {
        var codeBlockArguments = CodeBlock.builder();
        var codeBlockConditions = CodeBlock.builder();
        var codeBlockConstructor = CodeBlock.builder();

        var useMemberConstructor = false;

        codeBlockArguments.add("(");
        codeBlockConstructor.add("(");

        for (int i = 0; i < fieldsWithoutTable.size(); i++) {
            String argumentName;
            ObjectField field = fieldsWithoutTable.get(i);
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
    private CodeBlock wrapWithExplicitMapping(CodeBlock mappingFunction, FetchContext context, List<ObjectField> fieldsWithoutTable) {
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
    private CodeBlock generateForScalarField(ObjectField field, FetchContext context) {
        var renderedSource = context.iterateJoinSequenceFor(field).render();
        if (field.isID()) {
            return CodeBlock.of("$L.get$L()", renderedSource, capitalize(field.getMappingFromColumn().getName()));
        }
        var content = CodeBlock.of("$L.$N$L", renderedSource, field.getUpperCaseName(), toJOOQEnumConverter(field.getTypeName()));
        return context.getShouldUseOptional() ? (CodeBlock.of("$N.optional($S, $L)", SELECTION_NAME, context.getGraphPath() + field.getName(), content)) : content;
    }

    /**
     * Generate select row for each object within the union field
     */
    private CodeBlock generateForUnionField(ObjectField field, FetchContext context) {
        var codeBlock = CodeBlock.builder();
        var unionField = processedSchema.getUnion(field.getTypeName());

        var counter = 0;
        for(var fieldObject : unionField.getFieldTypeNames()) {
            var object = processedSchema.getObject(fieldObject).getName();
            var objectField = new ObjectField(new FieldDefinition(object, new graphql.language.TypeName(object)));
            codeBlock.add(generateSelectRow(context.nextContext(objectField).withShouldUseOptional(false)));
            if(counter + 1 < unionField.getFieldTypeNames().size()) {
                codeBlock.add(".as($S),\n", field.getName());
            }
            counter++;
        }
        return codeBlock.build();
    }

    @NotNull
    protected InputConditions getInputConditions(List<? extends InputField> inputFields) {
        var iterableInputFieldNamePaths = new ArrayList<String>();
        var flatInputs = new ArrayList<InputCondition>();
        var inputBuffer = inputFields
                .stream()
                .map(InputCondition::new)
                .collect(Collectors.toCollection(LinkedList::new));
        while (!inputBuffer.isEmpty() && inputBuffer.size() < Integer.MAX_VALUE) {
            var inputCondition = inputBuffer.poll();
            var inputField = inputCondition.getInput();

            if (inputField.isIterableWrapped() && processedSchema.isInputType(inputField)) {
                iterableInputFieldNamePaths.add(inputCondition.getNameWithPath());
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
            } else {
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
}
