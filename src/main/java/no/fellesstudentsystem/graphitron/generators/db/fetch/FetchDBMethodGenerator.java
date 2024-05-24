package no.fellesstudentsystem.graphitron.generators.db.fetch;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputCondition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.DSL;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Abstract generator for various database fetching methods.
 */
public abstract class FetchDBMethodGenerator extends DBMethodGenerator<ObjectField> {
    final String idParamName = uncapitalize(getLocalObject().getName()) + "Ids";
    final boolean isRoot = getLocalObject().isRoot();

    public FetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
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

        for (var inputCondition : flatInputs) {
            var field = inputCondition.getInput();
            var name = inputCondition.getNameWithPath();
            var checks = inputCondition.getChecksAsSequence();
            var checksNotEmpty = !checks.isEmpty();
            var renderedSequence = context.iterateJoinSequenceFor(field).render();
            if (!referenceField.hasOverridingCondition() && !field.hasOverridingCondition()) {
                codeBlockBuilder
                        .add(hasWhere ? ".and(" : "")
                        .add(checksNotEmpty ? checks + " ? " : "")
                        .add("$L.$N", renderedSequence, field.getUpperCaseName())
                        .add(toJOOQEnumConverter(field.getTypeName(), field.isIterableWrapped(), processedSchema))
                        .add(field.isIterableWrapped() ? ".in($L)" : ".eq($L)", name);
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
            codeBlockBuilder.add(createTupleCondition(context, hasWhere, conditionTuple.getPath(), conditionTuple.getConditions()));
            hasWhere = true;
        }

        if (referenceField.hasCondition()) {
            var inputs = Stream.concat(
                    Stream.of(context.getCurrentJoinSequence().render()),
                    flatInputs.stream().map(this::getCheckedNameWithPath)
            ).collect(Collectors.toList());
            codeBlockBuilder.add(wrapCondition(referenceField.getCondition().formatToString(inputs), hasWhere));
        }
        return codeBlockBuilder.build();
    }

    private CodeBlock getCheckedNameWithPath(InputCondition condition) {
        var nameWithPath = condition.getNameWithPath();
        var checks = condition.getChecksAsSequence();
        var enumConverter = toGraphEnumConverter(condition.getInput().getTypeName(), nameWithPath, condition.getInput().isIterableWrapped(), processedSchema);
        return CodeBlock.of(
                !checks.isEmpty() && !condition.getNamePath().isEmpty() ? checks + " ? $L : null" : "$L",
                enumConverter.isEmpty() ? nameWithPath : enumConverter
        );
    }

    private CodeBlock createTupleCondition(FetchContext context, boolean hasWhere, String argumentInputFieldName, List<InputCondition> conditions) {
        var codeBlockBuilder = CodeBlock.builder();

        var checks = String.join(" && ", conditions.stream().map(InputCondition::getChecksAsSequence).collect(Collectors.toSet()));

        codeBlockBuilder
                .add(hasWhere ? ".and(" : "")
                .add(checks.isEmpty() ? "" : "$L ?\n", checks)
                .indent().indent()
                .add("$T.row(\n", DSL.className)
                .indent().indent();

        for (int i = 0; i < conditions.size(); i++) {
            var field = conditions.get(i).getInput();
            var fieldSequence = context.iterateJoinSequenceFor(field).render();

            codeBlockBuilder
                    .add("$L.$N", fieldSequence, field.getUpperCaseName())
                    .add(toJOOQEnumConverter(field.getTypeName(), field.isIterableWrapped(), processedSchema))
                    .add(i < conditions.size()-1 ? ",\n" : "");
        }
        codeBlockBuilder
                .unindent().unindent()
                .add("\n)")
                .add(".in($N.stream().map(input -> $T.row(\n", argumentInputFieldName, DSL.className)
                .indent().indent()
                .add(conditions.stream()
                        .map(it -> "input" + it.getNameWithPathString().replaceFirst(Pattern.quote(argumentInputFieldName), ""))
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
}
