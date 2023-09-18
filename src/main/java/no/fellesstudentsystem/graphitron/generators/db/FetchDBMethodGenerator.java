package no.fellesstudentsystem.graphitron.generators.db;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputCondition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBMethodGenerator;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.collectToList;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.DSL;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Abstract generator for various database fetching methods.
 */
public abstract class FetchDBMethodGenerator extends DBMethodGenerator<ObjectField> {
    final String idParamName = uncapitalize(getLocalObject().getName()) + "Ider";
    final boolean isRoot = getLocalObject().isRoot();

    public FetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    /**
     * @return Formatted CodeBlock for the where-statement and surrounding code. Applies conditions and joins.
     */
    protected CodeBlock formatWhereContents(ObjectField referenceField, String currentJoinSequence, boolean hasKeyReference, String actualRefTable) {
        var code = CodeBlock.builder().add(".where(");

        if (!isRoot) {
            var localTableName = getLocalObject().getTable().getName();
            var qualifiedId = TableReflection.getQualifiedId(actualRefTable, localTableName);

            code
                    .add(
                            hasKeyReference
                                    ? actualRefTable + String.format(".has%ss($N)", qualifiedId)
                                    : localTableName + ".hasIds($N)",
                            idParamName
                    )
                    .add(")\n");
        }
        if (referenceField.hasNonReservedInputFields()) {
            code.add(createWhere(actualRefTable, referenceField, currentJoinSequence, !isRoot));
        } else if (isRoot) {
            return CodeBlock.builder().build();
        }
        return code.build();
    }

    private CodeBlock createWhere(String actualRefTable, ObjectField referenceField, String currentJoinSequence, boolean hasWhere) {
        var inputConditions = getInputConditions(referenceField.getNonReservedInputFields());
        var flatInputs = inputConditions.getIndependentConditions();
        var codeBlockBuilder = CodeBlock.builder();

        for (InputCondition inputCondition : flatInputs) {
            InputField field = inputCondition.getInput();
            var name = inputCondition.getNameWithPath();
            var checks = inputCondition.getChecksAsSequence();
            var checksNotEmpty = !checks.isEmpty();
            if (!referenceField.hasOverridingCondition() && !field.hasOverridingCondition()) {
                var fieldType = field.getFieldType();
                var fieldTypeName = fieldType.getName();
                codeBlockBuilder
                        .add(hasWhere ? ".and(" : "")
                        .add(checksNotEmpty ? checks + " ? " : "")
                        .add(currentJoinSequence + getJoinedFieldSource(field, actualRefTable) + "." + field.getUpperCaseName())
                        .add(toJOOQEnumConverter(fieldTypeName))
                        .add(fieldType.isIterableWrapped() ? ".in($N)" : ".eq($N)", name);
                if (checksNotEmpty) {
                    codeBlockBuilder.add(" : $T.noCondition()", DSL.className);
                }
                codeBlockBuilder.add(")\n");
            }
            if (!codeBlockBuilder.isEmpty()) {
                hasWhere = true;
            }

            if (field.hasCondition()) {
                var conditionInputs = List.of(CodeBlock.of(actualRefTable), getCheckedNameWithPath(inputCondition));
                codeBlockBuilder.add(wrapCondition(field.getCondition().formatToString(conditionInputs), hasWhere));
            }
            if (!codeBlockBuilder.isEmpty()) {
                hasWhere = true;
            }
        }

        for (Map.Entry<InputField, List<InputCondition>> conditionTuple : inputConditions.getConditionTuples().entrySet()) {
            codeBlockBuilder.add(createTupleCondition(actualRefTable, hasWhere, conditionTuple.getKey(), conditionTuple.getValue()));
            hasWhere = true;
        }

        if (referenceField.hasCondition()) {
            var inputs = Stream.concat(
                    Stream.of(CodeBlock.of(actualRefTable)),
                    flatInputs.stream().map(this::getCheckedNameWithPath)
            ).collect(Collectors.toList());
            codeBlockBuilder.add(wrapCondition(referenceField.getCondition().formatToString(inputs), hasWhere));
        }
        return codeBlockBuilder.build();
    }

    private CodeBlock getCheckedNameWithPath(InputCondition condition) {
        var nameWithPath = condition.getNameWithPath();
        var checks = condition.getChecksAsSequence();
        var enumConverter = toGraphEnumConverter(condition.getInput().getTypeName(), CodeBlock.of(nameWithPath));
        return CodeBlock.of(
                !checks.isEmpty() && !condition.getNamePath().isEmpty() ? checks + " ? $L : null" : "$L",
                enumConverter.isEmpty() ? nameWithPath : enumConverter
        );
    }

    private CodeBlock createTupleCondition(String actualRefTable, boolean hasWhere, InputField argumentInputField, List<InputCondition> conditions) {
        var codeBlockBuilder = CodeBlock.builder();
        var argumentName = argumentInputField.getName();

        codeBlockBuilder
                .add(hasWhere ? ".and(" : "")
                .add("$N != null && $N.size() > 0 ?\n", argumentName, argumentName)
                .indent().indent()
                .add("$T.row(\n", DSL.className)
                .indent().indent();

        for (int i = 0; i < conditions.size(); i++) {
            InputField field = conditions.get(i).getInput();
            var fieldType = field.getFieldType();
            var fieldTypeName = fieldType.getName();

            codeBlockBuilder
                    .add(actualRefTable + getJoinedFieldSource(field, actualRefTable) + "." + field.getUpperCaseName())
                    .add(toJOOQEnumConverter(fieldTypeName))
                    .add(i < conditions.size()-1 ? ",\n" : "");
        }
        codeBlockBuilder
                .unindent().unindent()
                .add("\n)")
                .add(".in($N.stream().map(input -> $T.row(\n", argumentName, DSL.className)
                .indent().indent()
                .add(conditions.stream()
                        .map(it -> "input" + it.getNameWithPath().replaceFirst(argumentName, ""))
                        .collect(Collectors.joining(",\n")))
                .unindent().unindent()
                .add(")\n)")
                .add(collectToList())
                .add(") :\n")
                .add("$T.noCondition()", DSL.className)
                .unindent().unindent()
                .add(")\n");

        return codeBlockBuilder.build();
    }

    private CodeBlock wrapCondition(CodeBlock condition, boolean hasWhere) {
        if (!condition.isEmpty()) {
            return CodeBlock.of((hasWhere ? ".and(" : "") + "$L)\n", condition);
        }
        return condition;
    }

    private String getJoinedFieldSource(InputField field, String refTableName) {
        if (field.hasImplicitJoin()) {
            return getAppliedImplicitJoin(field.getImplicitJoin(), refTableName);
        }
        return "";
    }
}
