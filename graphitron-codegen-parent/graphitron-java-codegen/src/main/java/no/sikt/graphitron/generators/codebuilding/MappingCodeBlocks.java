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
            code.declare(asListedNameIf(mapperContext.getTargetName(), mapperContext.isIterable()), mapperContext.transformOutputRecord(mapperContext.getTarget().getName()));
        } else if (!mapperContext.hasRecordReference()) {
            code.declareNew(mapperContext.getTargetName(), mapperContext.getTargetType().getGraphClassName());
        }

        code.add("\n");

        for (var innerField : mapperContext.getTargetType().getFields()) {
            var innerContext = mapperContext.iterateContext(innerField);
            if (innerContext.shouldUseException()) {
                continue;
            }

            var previousTarget = innerContext.getPreviousContext().getTarget();
            if (innerField.isExplicitlyNotGenerated() || innerContext.getTarget().isResolver() || innerContext.getPreviousContext().hasRecordReference()) {
                continue;
            }

            code
                    .beginControlFlow("if ($N != null && $L)", previousTarget.getName(), selectionSetLookup(innerContext.getPath(), true, false))
                    .add(getInnerMapping(innerContext, innerField, previousTarget, schema))
                    .endControlFlow()
                    .add("\n");
        }

        return code.add("\n").build();
    }

    private static CodeBlock getInnerMapping(MapperContext context, GenerationField innerField, GenerationField previousTarget, ProcessedSchema schema) {
        if (!context.targetIsType()) {
            return context.getSetMappingBlock(getFieldSetContent((ObjectField) innerField, (ObjectField) previousTarget, schema));
        }

        if (context.hasRecordReference()) {
            return context.getRecordSetMappingBlock(previousTarget.getName());
        }

        return generateSchemaOutputs(context, schema);
    }

    @Deprecated
    public static CodeBlock idFetchAllowingDuplicates(MapperContext context, GenerationField field, String varName, boolean atResolver) {
        var get = getNodeQueryCallBlock(field, varName, !atResolver ? CodeBlock.of("$N + $S", PATH_HERE_NAME, context.getPath()) : CodeBlock.of("$S", context.getPath()), atResolver);
        if (!context.isIterable()) {
            return context.getSetMappingBlock(get);
        }

        var tempName = asNodeQueryName(field.getTypeName());
        return CodeBlock
                .builder()
                .declare(tempName, get)
                .add(context.getSetMappingBlock(CodeBlock.of("$N.stream().map(it -> $N.get(it.getId()))$L", varName, tempName, collectToList())))
                .build();
    }

    private static CodeBlock getFieldSetContent(ObjectField field, ObjectField previousField, ProcessedSchema schema) {
        var resultName = asIterableResultNameIf(previousField.getUnprocessedFieldOverrideInput(), previousField.isIterableWrapped());
        if (!schema.isObject(previousField) || !schema.isRecordType(field)) {
            return CodeBlock.of("$N", resultName);
        }

        var getMapping = field.getMappingForRecordFieldOverride();
        if (!field.isIterableWrapped() || previousField.isIterableWrapped()) {
            return getValue(resultName, getMapping);
        }

        var iterationName = asIterable(field.getName());
        return CodeBlock.of("$N.stream().map($L -> $L)$L", resultName, iterationName, getValue(iterationName, getMapping), collectToList());
    }
}
