package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.FieldType;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputCondition;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputConditions;
import no.fellesstudentsystem.graphitron.definitions.objects.EnumDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLAlias;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLImplicitFKJoin;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLJoinStatement;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.ContextDependency;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphitron.mappings.ReferenceHelpers;
import no.fellesstudentsystem.kjerneapi.enums.GeneratorEnum;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.*;

/**
 * Generic select query generation functionality is contained within this class.
 * @param <T> Field type that this generator operates on.
 */
abstract public class DBMethodGenerator<T extends ObjectField> extends AbstractMethodGenerator<T> {
    protected final static String SELECTION_NAME = "select";
    private static final int MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPESAFETY = 22;
    protected final Map<String, Class<?>> enumOverrides;
    protected final Map<String, Method> conditionOverrides;

    public DBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        this(localObject, processedSchema, Map.of(), Map.of());
    }

    public DBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema,
            Map<String, Class<?>> enumOverrides,
            Map<String, Method> conditionOverrides
    ) {
        super(localObject, processedSchema);
        dependencySet.add(ContextDependency.getInstance());
        this.enumOverrides = enumOverrides;
        this.conditionOverrides = conditionOverrides;
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return MethodSpec
                .methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);
    }

    /**
     * @param joinList  List of join statements that should have their aliases defined.
     * @param aliasList List of all aliases created from implicit joins.
     * @return Code block which declares all the aliases that will be used in a select query.
     */
    protected CodeBlock createSelectAliases(List<SQLJoinStatement> joinList, List<SQLAlias> aliasList) {
        var codeBuilder = CodeBlock.builder();
        for (var join : joinList) {
            var aliasName = join.getJoinAlias();
            codeBuilder.addStatement("var " + aliasName + " = " + join.getJoinTargetTable() + ".as(\"" + aliasName + "\")");
        }
        for (var alias : aliasList) {
            codeBuilder.addStatement(alias.toString());
        }
        return codeBuilder.build();
    }

    /**
     * @param joinList List of join statements that should be applied to a select query.
     * @return Code block containing all the join statements and their conditions.
     */
    protected CodeBlock createSelectJoins(List<SQLJoinStatement> joinList) {
        var codeBuilder = CodeBlock.builder();
        joinList.forEach(join -> codeBuilder.add(join.toJoinString(conditionOverrides)));
        return codeBuilder.build();
    }

    /**
     * @param conditionList List of conditional statements that should be appended after the where-statement.
     * @return Code block which declares all the extra conditions that will be used in a select query.
     */
    protected CodeBlock createSelectConditions(List<String> conditionList) {
        var builder = CodeBlock.builder();
        conditionList.forEach(c -> builder.add(c + "\n"));
        return builder.build();
    }

    /**
     * This method recursively generates one single row method call.
     * It deduces how each layer of row call should be structured by keeping track of joins and following field references.
     * @return Code block which contains the entire recursive structure of the row statement.
     */
    protected CodeBlock generateSelectRow(FetchContext context) {
        var codeBlockBuilder = CodeBlock
                .builder()
                .add("row(\n")
                .indent()
                .indent();

        var fieldsWithoutTable = context.getReferenceObject()
                .getFields()
                .stream()
                .filter(f -> !(f.isResolver() &&
                        (processedSchema.isObject(f.getTypeName()) || processedSchema.isInterface(f.getTypeName()))))
                .collect(Collectors.toList());
        var fieldsWithoutTableSize = fieldsWithoutTable.size();
        for (int i = 0; i < fieldsWithoutTableSize; i++) {
            var field = fieldsWithoutTable.get(i);

            codeBlockBuilder.add(
                    processedSchema.isObject(field.getTypeName())
                            ? generateSelectRow(context.nextContext(field))
                            : generateForScalarField(field, context)
            );
            codeBlockBuilder.add(".as($S)", field.getName());
            codeBlockBuilder.add((i < fieldsWithoutTableSize - 1) ? ",\n" : "\n");
        }
        codeBlockBuilder.unindent().unindent();
        codeBlockBuilder.add(").mapping(");

        boolean maxTypeSafeFieldSizeIsExceeded = fieldsWithoutTable.size() > MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPESAFETY;

        CodeBlock mappingFunction = context.shouldUseEnhancedNullOnAllNullCheck()
                ? createMappingFunctionWithEnhancedNullSafety(fieldsWithoutTable, context.getReferenceObject().getGraphClassName(), maxTypeSafeFieldSizeIsExceeded)
                : createMappingFunction(context, fieldsWithoutTable, maxTypeSafeFieldSizeIsExceeded);

        if (maxTypeSafeFieldSizeIsExceeded) {
            codeBlockBuilder.add(wrapWithExplicitMapping(mappingFunction, context, fieldsWithoutTable));
        } else {
            codeBlockBuilder.add(mappingFunction);
        }
        codeBlockBuilder.add(")");
        return codeBlockBuilder.build();
    }

    private CodeBlock createMappingFunction(FetchContext context, List<ObjectField> fieldsWithoutTable, boolean maxTypeSafeFieldSizeIsExeeded) {
        boolean hasIdField = fieldsWithoutTable.stream()
                .map(ObjectField::getFieldType)
                .anyMatch(FieldType::isID);
        boolean hasNullableField = fieldsWithoutTable.stream()
                .map(ObjectField::getFieldType)
                .anyMatch(FieldType::isNullable);

        boolean canReturnNonNullableObjectWithAllFieldsNull = hasNullableField && context.getReferenceObjectField().getFieldType().isNonNullable() && !hasIdField;

        var mappedObjectCodeBlock = CodeBlock.of(maxTypeSafeFieldSizeIsExeeded ? "new $T(\n" : "$T::new", context.getReferenceObject().getGraphClassName());

        if (canReturnNonNullableObjectWithAllFieldsNull) {
            context.setParentContextShouldUseEnhancedNullOnAllNullCheck();
            return mappedObjectCodeBlock;
        } else {
            return maxTypeSafeFieldSizeIsExeeded
                    ? CodeBlock.of("$T.stream(r).allMatch($T::isNull) ? null : $L", ARRAYS.className, OBJECTS.className, mappedObjectCodeBlock)
                    : CodeBlock.of("nullOnAllNull($L)", mappedObjectCodeBlock);
        }
    }

    private CodeBlock createMappingFunctionWithEnhancedNullSafety(List<ObjectField> fieldsWithoutTable, ClassName graphClassName, boolean maxTypeSafeFieldSizeIsExeeded) {
        var codeBlockArguments = CodeBlock.builder();
        var codeBlockConditions = CodeBlock.builder();

        codeBlockArguments.add("(");

        for (int i = 0; i < fieldsWithoutTable.size(); i++) {
            String argumentName;

            if (maxTypeSafeFieldSizeIsExeeded) {
                argumentName = "r[" + i + "]";
            } else {
                argumentName = "a" + i;
                codeBlockArguments.add(argumentName);
                codeBlockArguments.add(i == fieldsWithoutTable.size()-1 ? ")" : ", ");
            }
            ObjectField field = fieldsWithoutTable.get(i);

            if (processedSchema.isObject(field.getTypeName()))  {
                codeBlockConditions.add("($L == null || new $T().equals($L))", argumentName, processedSchema.getObject(field.getTypeName()).getGraphClassName(), argumentName);
            } else {
                codeBlockConditions.add("$L == null", argumentName);
            }
            codeBlockConditions.add(i == fieldsWithoutTable.size()-1 ? "" : " && ");
        }

        if (maxTypeSafeFieldSizeIsExeeded) {
            return CodeBlock.of("$L ? null : new $T(\n", codeBlockConditions.build(), graphClassName);
        }

        return CodeBlock.builder()
                .add(codeBlockArguments.build())
                .add(" -> ")
                .add(codeBlockConditions.build())
                .add(" ? null : new $T", graphClassName)
                .add(codeBlockArguments.build())
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
            var fieldType = field.getTypeName();

            if (processedSchema.isObject(fieldType)) {
                codeBlockBuilder.add("($T) r[$L]", processedSchema.getObject(fieldType).getGraphClassName(), i);
            } else if (field.getFieldType().isID()) {
                codeBlockBuilder.add("($T) r[$L]", STRING.className, i);
            } else if (processedSchema.isEnum(fieldType)) {
                var enumDefinition = processedSchema.getEnum(fieldType);
                codeBlockBuilder.add("($T) r[$L]", enumDefinition.getGraphClassName(), i);
            } else {
                var fieldString = context.getCurrentJoinSequence() +
                        getJoinedFieldSource(field, context.getReferenceTable().getName()) + "." + field.getUpperCaseName();
                codeBlockBuilder.add("$N.getDataType().convert(r[$L])", fieldString, i);
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
        var fieldType = field.getTypeName();
        var refObject = context.getReferenceObject();

        String joinedFieldSource = context.getCurrentJoinSequence() +
                getJoinedFieldSource(field, context.getReferenceTable().getName());
        var fieldString = joinedFieldSource + "." + field.getUpperCaseName();

        var codeBlockBuilder = CodeBlock.builder();
        if (field.getFieldType().isID()) {
            var hasKeyReference = !field.hasImplicitJoin()
                    && !context.hasJoinedAlreadyOrWillJoin()
                    && ReferenceHelpers.usesIDReference(context.getPreviousTableObject(), refObject.getTable());
            var qualifiedId = "";
            if (hasKeyReference) {
                var localTableName = getLocalObject().getTable().getName();
                var referenceTableName = context.getReferenceTable().getName();
                qualifiedId = TableReflection.getQualifiedId(localTableName, referenceTableName);
            }

            return codeBlockBuilder
                    .add(joinedFieldSource + (hasKeyReference ? String.format(".get%s()", qualifiedId) : ".getId()"))
                    .build();
        }

        return codeBlockBuilder
                .add("$N.optional(\"" + context.getGraphPath() + field.getName() + "\", " + fieldString, SELECTION_NAME)
                .add(toEnumConverter(fieldType))
                .add(")")
                .build();
    }

    private String getJoinedFieldSource(ObjectField field, String refTableName) {
        if (field.hasImplicitJoin()) {
            return getAppliedImplicitJoin(field.getImplicitJoin(), refTableName);
        }
        return "";
    }

    protected static String getAppliedImplicitJoin(SQLImplicitFKJoin join, String refTableName) {
        var table = join.hasTable() ? Optional.of(join.getTable().getCodeName()) : getJoinTableByKey(join.getKey());
        if (table.isPresent()) {
            if (tableHasMethod(refTableName, table.get())) {
                return "." + table.get() + "()";
            } else {
                var joinReference = searchTableForMethodByKey(refTableName, join.getKey());
                if (joinReference.isPresent()) {
                    return "." + joinReference.get() + "()";
                }
            }
        }
        return "";
    }

    /**
     * @return Code block containing the enum conversion method call with an anonymous function declaration.
     */
    protected CodeBlock toEnumConverter(String enumType) {
        if (!processedSchema.isEnum(enumType)) {
            return CodeBlock.of("");
        }
        var enumEntry = processedSchema.getEnum(enumType);
        return CodeBlock
                .builder()
                .add(".convert($T.class, s -> s == null ? null : $T.of(", enumEntry.getGraphClassName(), MAP.className)
                .add(renderMapElements(enumEntry, true))
                .add(").getOrDefault(s, null), s -> s == null ? null : $T.of(", MAP.className)
                .add(renderMapElements(enumEntry, false))
                .add(").getOrDefault(s, null))")
                .build();
    }

    private CodeBlock renderMapElements(EnumDefinition enumEntry, boolean flipDirection) {
        var code = CodeBlock.builder();
        var hasEnumReference = enumEntry.hasDbEnumMapping();
        var dbName = enumEntry.getDbName();
        var entryClassName = enumEntry.getGraphClassName();
        var entrySet = new ArrayList<>(enumEntry.getValuesMap().entrySet());
        var entrySetSize = entrySet.size();
        for (int i = 0; i < entrySetSize; i++) {
            var enumValue = entrySet.get(i);
            if (flipDirection) {
                code
                        .add(renderValueSide(hasEnumReference, dbName, enumValue.getValue().getUpperCaseName()))
                        .add(", $T.$L", entryClassName, enumValue.getKey());
            } else {
                code
                        .add("$T.$L, ", entryClassName, enumValue.getKey())
                        .add(renderValueSide(hasEnumReference, dbName, enumValue.getValue().getUpperCaseName()));
            }
            if (i < entrySetSize - 1) {
                code.add(", ");
            }
        }
        return code.build();
    }

    private CodeBlock renderValueSide(boolean hasEnumReference, String dbName, String valueName) {
        var code = CodeBlock.builder();
        if (hasEnumReference) {
            var enumName = dbName.toUpperCase();
            var apiEnumType = enumOverrides.containsKey(enumName) ?  enumOverrides.get(enumName) : GeneratorEnum.valueOf(enumName).getEnumType();
            code.add("$T.$L", ClassName.get(apiEnumType.getPackageName(), apiEnumType.getSimpleName()), valueName);
        } else {
            code.add("$S", valueName);
        }
        return code.build();
    }

    @NotNull
    protected InputConditions getInputConditions(List<InputField> inputFields) {
        var flatInputs = new ArrayList<InputCondition>();
        var inputBuffer = inputFields
                .stream()
                .map(InputCondition::new)
                .collect(Collectors.toCollection(LinkedList::new));
        while (!inputBuffer.isEmpty() && inputBuffer.size() < Integer.MAX_VALUE) {
            var inputData = inputBuffer.poll();
            var input = inputData.getInput();
            if (processedSchema.isInputType(input.getTypeName())) {
                inputBuffer.addAll(
                        0,
                        processedSchema
                                .getInputType(input.getTypeName())
                                .getInputs()
                                .stream()
                                .map(inputData::iterate)
                                .collect(Collectors.toList())
                );
            } else {
                flatInputs.add(inputData.applyTo(input));
            }
        }

        var conditionTuples = getConditionTuples(inputFields, flatInputs);
        conditionTuples.values().forEach(flatInputs::removeAll);

        return new InputConditions(flatInputs, conditionTuples);
    }

    private Map<InputField, List<InputCondition>> getConditionTuples(List<InputField> inputFields, ArrayList<InputCondition> flatInputs) {
        return inputFields.stream()
                .filter(inputField -> inputField.getFieldType().isIterableWrapped() &&
                        processedSchema.isInputType(inputField.getTypeName()))
                .collect(Collectors.toMap(
                        Function.identity(),
                        inputField -> flatInputs.stream()
                                .filter(condition -> condition.getNamePath().startsWith(inputField.getName()))
                                .collect(Collectors.toList())
                ));
    }
}
