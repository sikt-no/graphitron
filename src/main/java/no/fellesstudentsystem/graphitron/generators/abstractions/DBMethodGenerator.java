package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.FieldType;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputCondition;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputConditions;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLAlias;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLJoinStatement;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.mappings.ReferenceHelpers;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

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
     * @param aliasList List of all aliases created from implicit joins.
     * @return Code block which declares all the aliases that will be used in a select query.
     */
    protected CodeBlock createSelectAliases(Set<SQLJoinStatement> joinList, Set<SQLAlias> aliasList) {
        var codeBuilder = CodeBlock.builder();
        for (var join : joinList) {
            var aliasName = join.getJoinAlias();
            var shortAliasName = join.getJoinShortAliasName();
            codeBuilder.addStatement("var " + aliasName + " = " + join.getJoinTargetTable() + ".as(\"" + shortAliasName + "\")");
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
    protected CodeBlock createSelectJoins(Set<SQLJoinStatement> joinList) {
        var codeBuilder = CodeBlock.builder();
        joinList.forEach(join -> codeBuilder.add(join.toJoinString()));
        return codeBuilder.build();
    }

    /**
     * @param conditionList List of conditional statements that should be appended after the where-statement.
     * @return Code block which declares all the extra conditions that will be used in a select query.
     */
    protected CodeBlock createSelectConditions(Set<String> conditionList) {
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
        var fieldsWithoutSplitting = context
                .getReferenceObject()
                .getFields()
                .stream()
                .filter(f -> !(f.isResolver() && (processedSchema.isObject(f) || processedSchema.isInterface(f))))
                .collect(Collectors.toList());
        var fieldsWithoutSplittingSize = fieldsWithoutSplitting.size();
        // if (fieldsWithoutSplittingSize == 0) {
        //     return empty(); // $T.noField($T.inline(($T) null)) // Need to figure out how to handle empty rows.
        // }

        var rowContentCode = CodeBlock.builder().indent().indent();
        for (int i = 0; i < fieldsWithoutSplittingSize; i++) {
            var field = fieldsWithoutSplitting.get(i);
            var innerRowCode = processedSchema.isObject(field)
                    ? generateSelectRow(context.nextContext(field))
                    : generateForScalarField(field, context);
            rowContentCode
                    .add("$L.as($S)", innerRowCode, field.getName())
                    .add((i < fieldsWithoutSplittingSize - 1) ? ",\n" : "\n");
        }

        boolean maxTypeSafeFieldSizeIsExceeded = fieldsWithoutSplittingSize > MAX_NUMBER_OF_FIELDS_SUPPORTED_WITH_TYPESAFETY;

        CodeBlock mappingFunction = context.shouldUseEnhancedNullOnAllNullCheck()
                ? createMappingFunctionWithEnhancedNullSafety(fieldsWithoutSplitting, context.getReferenceObject().getGraphClassName(), maxTypeSafeFieldSizeIsExceeded)
                : createMappingFunction(context, fieldsWithoutSplitting, maxTypeSafeFieldSizeIsExceeded);

        rowContentCode
                .unindent()
                .unindent()
                .add(").mapping(")
                .add(maxTypeSafeFieldSizeIsExceeded ? wrapWithExplicitMapping(mappingFunction, context, fieldsWithoutSplitting) : mappingFunction);

        return CodeBlock
                .builder()
                .add("$T.row(\n$L)", DSL.className, rowContentCode.build())
                .build();
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
                    : CodeBlock.of("$T.nullOnAllNull($L)", FUNCTIONS.className, mappedObjectCodeBlock);
        }
    }

    private CodeBlock createMappingFunctionWithEnhancedNullSafety(List<ObjectField> fieldsWithoutTable, TypeName graphClassName, boolean maxTypeSafeFieldSizeIsExeeded) {
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

            if (processedSchema.isObject(field))  {
                codeBlockConditions.add("($L == null || new $T().equals($L))", argumentName, processedSchema.getObject(field).getGraphClassName(), argumentName);
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

            if (processedSchema.isObject(field)) {
                codeBlockBuilder.add("($T) r[$L]", processedSchema.getObject(field).getGraphClassName(), i);
            } else if (field.getFieldType().isID()) {
                codeBlockBuilder.add("($T) r[$L]", STRING.className, i);
            } else if (processedSchema.isEnum(field)) {
                var enumDefinition = processedSchema.getEnum(field);
                codeBlockBuilder.add("($T) r[$L]", enumDefinition.getGraphClassName(), i);
            } else {
                var fieldSource = field.hasFieldReferences()
                        ? context.iterateSourceMultipleSequences(field.getFieldReferences())
                        : context.getCurrentJoinSequence();
                codeBlockBuilder.add("$L.$N.getDataType().convert(r[$L])", fieldSource, field.getUpperCaseName(), i);
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

        var joinedFieldSource = field.hasFieldReferences()
                ? context.iterateSourceMultipleSequences(field.getFieldReferences())
                : context.getCurrentJoinSequence();

        if (field.getFieldType().isID()) {
            var hasKeyReference = !context.hasJoinedAlreadyOrWillJoin()
                    && context.hasKeyReference()
                    && ReferenceHelpers.usesReverseReference(context.getPreviousTableObject(), context.getReferenceTable());
            var qualifiedId = "Id";
            if (hasKeyReference) {
                qualifiedId = TableReflection.getQualifiedId(context.getPreviousTableObject().getName(), context.getReferenceTable().getName());
            }

            return CodeBlock.of("$L.get$L()", joinedFieldSource, qualifiedId);
        }

        return CodeBlock.of(
                "$N.optional($S, $L.$N$L)",
                SELECTION_NAME,
                context.getGraphPath() + field.getName(),
                joinedFieldSource,
                field.getUpperCaseName(),
                toJOOQEnumConverter(fieldType)
        );
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
            if (processedSchema.isInputType(input)) {
                inputBuffer.addAll(
                        0,
                        processedSchema
                                .getInputType(input)
                                .getFields()
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
                .filter(inputField -> inputField.isIterableWrapped() &&
                        processedSchema.isInputType(inputField))
                .collect(Collectors.toMap(
                        Function.identity(),
                        inputField -> flatInputs.stream()
                                .filter(condition -> condition.getNamePath().startsWith(inputField.getName()))
                                .collect(Collectors.toList())
                ));
    }
}
