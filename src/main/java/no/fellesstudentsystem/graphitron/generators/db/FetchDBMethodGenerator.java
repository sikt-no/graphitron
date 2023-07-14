package no.fellesstudentsystem.graphitron.generators.db;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputCondition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBMethodGenerator;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.COLLECTORS;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public abstract class FetchDBMethodGenerator extends DBMethodGenerator<ObjectField> {

    final String idParamName = uncapitalize(getLocalObject().getName()) + "Ider";
    final boolean isRoot = getLocalObject().isRoot();

    public FetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    public FetchDBMethodGenerator(ObjectDefinition localObject,
                                  ProcessedSchema processedSchema,
                                  Map<String, Class<?>> enumOverrides,
                                  Map<String, Method> conditionOverrides) {
        super(localObject, processedSchema, enumOverrides, conditionOverrides);
    }

    CodeBlock formatWhereContents(ObjectField referenceField, String currentJoinSequence, boolean hasKeyReference, String actualRefTable) {
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
                        .add(toEnumConverter(fieldTypeName))
                        .add(fieldType.isIterableWrapped() ? ".in($N)" : ".eq($N)", name)
                        .add(checksNotEmpty ? " : noCondition()" : "")
                        .add(")\n");
            }

            if (field.hasCondition()) {
                var conditionInputs = List.of(actualRefTable, inputCondition.getCheckedNameWithPath());
                codeBlockBuilder.add(wrapCondition(field.applyCondition(conditionInputs, conditionOverrides), hasWhere));
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
                    Stream.of(actualRefTable),
                    flatInputs.stream().map(InputCondition::getCheckedNameWithPath)
            ).collect(Collectors.toList());
            codeBlockBuilder.add(wrapCondition(referenceField.applyCondition(inputs, conditionOverrides), hasWhere));
        }
        return codeBlockBuilder.build();
    }

    private CodeBlock createTupleCondition(String actualRefTable, boolean hasWhere, InputField argumentInputField, List<InputCondition> conditions) {
        var codeBlockBuilder = CodeBlock.builder();
        var argumentName = argumentInputField.getName();

        codeBlockBuilder
                .add(hasWhere ? ".and(" : "")
                .add("$N != null && $N.size() > 0 ?\n", argumentName, argumentName)
                .indent().indent()
                .add("row(\n")
                .indent().indent();

        for (int i = 0; i < conditions.size(); i++) {
            InputField field = conditions.get(i).getInput();
            var fieldType = field.getFieldType();
            var fieldTypeName = fieldType.getName();

            codeBlockBuilder
                    .add(actualRefTable + getJoinedFieldSource(field, actualRefTable) + "." + field.getUpperCaseName())
                    .add(toEnumConverter(fieldTypeName))
                    .add(i < conditions.size()-1 ? ",\n" : "");
        }
        codeBlockBuilder
                .unindent().unindent()
                .add("\n)")
                .add(".in($N.stream().map(input -> row(\n", argumentName)
                .indent().indent()
                .add(conditions.stream()
                        .map(it -> "input" + it.getNameWithPath().replaceFirst(argumentName, ""))
                        .collect(Collectors.joining(",\n")))
                .unindent().unindent()
                .add(")\n")
                .add(").collect($T.toList()))",  COLLECTORS.className)
                .add(" :\n")
                .add("noCondition()")
                .unindent().unindent()
                .add(")\n");

        return codeBlockBuilder.build();
    }

    private String wrapCondition(String condition, boolean hasWhere) {
        if (!condition.isEmpty()) {
            return (hasWhere ? ".and(" : "") + condition + ")\n";
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
