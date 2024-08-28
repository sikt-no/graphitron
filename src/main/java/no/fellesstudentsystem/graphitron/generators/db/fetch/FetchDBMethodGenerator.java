package no.fellesstudentsystem.graphitron.generators.db.fetch;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputCondition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.VARIABLE_INTERNAL_ITERATION;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.DSL;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Abstract generator for various database fetching methods.
 */
public abstract class FetchDBMethodGenerator extends DBMethodGenerator<ObjectField> {
    final String idParamName = uncapitalize(getLocalObject().getName()) + "Ids";
    final boolean isRoot = getLocalObject().isOperationRoot();

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
}
