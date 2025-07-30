package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.PATH_HERE_NAME;

public class MappingCodeBlocks {
    /**
     * @return Code for adding error types and calling transform methods.
     */
    public static CodeBlock generateSchemaOutputs(MapperContext mapperContext, ProcessedSchema schema) {
        if (!mapperContext.targetIsType()) {
            return CodeBlock.empty();
        }

        var code = CodeBlock.builder();
        if (mapperContext.hasRecordReference() && mapperContext.isTopLevelContext()) {
            code.add(declare(asListedNameIf(mapperContext.getTargetName(), mapperContext.isIterable()), mapperContext.transformOutputRecord(mapperContext.getTarget().getName())));
        } else if (!mapperContext.hasRecordReference()) {
            code.add(declare(mapperContext.getTargetName(), mapperContext.getTargetType().getGraphClassName()));
        }

        code.add("\n");

        for (var innerField : mapperContext.getTargetType().getFields()) {
            var innerContext = mapperContext.iterateContext(innerField);

            if (innerContext.shouldUseException()) {
                continue;
            }

            var previousTarget = innerContext.getPreviousContext().getTarget();

            var innerCode = CodeBlock.builder();

            if (!innerField.isExplicitlyNotGenerated() && !innerContext.getTarget().isResolver() && !innerContext.getPreviousContext().hasRecordReference()) {
                if (!innerContext.targetIsType()) {
                    innerCode.add(innerContext.getSetMappingBlock(getFieldSetContent((ObjectField) innerField, (ObjectField) previousTarget, schema)));
                } else if (innerContext.hasRecordReference()) {
                    innerCode.add(innerContext.getRecordSetMappingBlock(previousTarget.getName()));
                } else {
                    innerCode.add(generateSchemaOutputs(innerContext, schema));
                }
            }

            if (!innerCode.isEmpty()) {
                code
                        .beginControlFlow("if ($N != null && $L)", previousTarget.getName(), selectionSetLookup(innerContext.getPath(), true, false))
                        .add(innerCode.build())
                        .endControlFlow()
                        .add("\n");
            }
        }

        return code.add("\n").build();
    }

    public static CodeBlock idFetchAllowingDuplicates(MapperContext context, GenerationField field, String varName, boolean atResolver) {
        var get = getNodeQueryCallBlock(field, varName, !atResolver ? CodeBlock.of("$N + $S", PATH_HERE_NAME, context.getPath()) : CodeBlock.of("$S", context.getPath()), atResolver);
        var code = CodeBlock.builder();
        if (context.isIterable()) {
            var tempName = asNodeQueryName(field.getTypeName());
            code
                    .add(declare(tempName, get))
                    .add(context.getSetMappingBlock(CodeBlock.of("$N.stream().map(it -> $N.get(it.getId()))$L", varName, tempName, collectToList())));
        } else {
            code.add(context.getSetMappingBlock(get)); // TODO: Should be done outside for? Preferably devise some general dataloader-like solution applying to query classes.
        }
        return code.build();
    }

    private static CodeBlock getFieldSetContent(ObjectField field, ObjectField previousField, ProcessedSchema schema) {
        var resultName = asIterableResultNameIf(previousField.getUnprocessedFieldOverrideInput(), previousField.isIterableWrapped());
        if (schema.isObject(previousField) && schema.isRecordType(field)) {
            var getMapping = field.getMappingForRecordFieldOverride();
            var extractValue = field.isIterableWrapped() && !previousField.isIterableWrapped();
            if (extractValue) {
                var iterationName = asIterable(field.getName());
                return CodeBlock.of("$N.stream().map($L -> $L)$L", resultName, iterationName, getValue(iterationName, getMapping), collectToList());
            } else {
                return getValue(resultName, getMapping);
            }
        }

        return CodeBlock.of("$N", resultName);
    }
}
