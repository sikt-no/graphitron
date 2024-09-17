package no.fellesstudentsystem.graphitron.generators.codebuilding;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.PATH_HERE_NAME;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.TRANSFORMER_NAME;

public class MappingCodeBlocks {
    public static CodeBlock inputTransform(List<? extends InputField> specInputs, ProcessedSchema schema) {
        var code = CodeBlock
                .builder()
                .add(declareTransform())
                .add("\n");
        var recordCode = CodeBlock.builder();

        var inputObjects = specInputs.stream().filter(schema::isInputType).collect(Collectors.toList());
        for (var in : inputObjects) {
            code.add(declareRecords(in, schema, 0));
            recordCode.add(unwrapRecords(MapperContext.createResolverContext(in, true, schema)));
        }

        if (code.isEmpty() && recordCode.isEmpty()) {
            return empty();
        }

        code.add("\n").add(recordCode.build());

        if (recordValidationEnabled()) {
            code.add("\n").addStatement(asMethodCall(TRANSFORMER_NAME, TransformerClassGenerator.METHOD_VALIDATE_NAME));
        }

        return code.build();
    }

    private static CodeBlock declareRecords(InputField target, ProcessedSchema schema, int recursion) {
        recursionCheck(recursion);

        var input = schema.getInputType(target);
        if (!input.hasRecordReference()) {
            return empty();
        }

        var targetName = target.getName();
        var code = CodeBlock.builder();
        var declareBlock = declare(asListedRecordNameIf(targetName, target.isIterableWrapped()), transformRecord(targetName, target.getTypeName(), input.hasJavaRecordReference()));
        if (input.hasJavaRecordReference()) {
            return declareBlock; // If the input type is a Java record, no further records should be declared.
        }

        if (input.hasTable() && recursion == 0) {
            code.add(declareBlock);
        } else {
            code.add(declareRecord(asRecordName(target.getName()), input, target.isIterableWrapped(), true));
        }

        input
                .getFields()
                .stream()
                .filter(schema::isInputType)
                .forEach(in -> code.add(declareRecords(in, schema, recursion + 1)));

        return code.build();
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    private static CodeBlock unwrapRecords(MapperContext context) {
        if (context.hasJavaRecordReference()) {
            return empty();
        }

        var schema = context.getSchema();

        var containedInputTypes = context.getTargetType()
                .getInputsSortedByNullability()
                .stream()
                .filter(schema::isInputType)
                .filter(it -> schema.hasRecord(it) || schema.getInputType(it).getFields().stream().anyMatch(schema::isInputType))
                .collect(Collectors.toList());

        var fieldCode = CodeBlock.builder();
        for (var in : containedInputTypes) {
            var innerContext = context.iterateContext(in);
            fieldCode
                    .add(declare(in.getName(), innerContext.getSourceGetCallBlock()))
                    .add(unwrapRecords(innerContext));
        }

        var code = CodeBlock.builder();
        var sourceName = context.getSourceName();
        if (context.hasTable() && !context.isTopLevelContext()) {
            var record = transformRecord(sourceName, context.getTarget().getTypeName(), context.getPath(), context.getIndexPath(), false);
            if (!context.getPreviousContext().wasIterable()) {
                code.addStatement("$L = $L", asListedRecordNameIf(sourceName, context.isIterable()), record);
            } else {
                code.addStatement("$N.add$L($L)", asListedRecordName(sourceName), context.isIterable() ? "All" : "", record);
            }
        }

        var fields = context.getTargetType().getFields();
        if (fieldCode.isEmpty() || fields.stream().noneMatch(schema::isInputType)) {
            return code.build();
        }

        if (context.isIterable() && !(context.hasTable() && fields.stream().anyMatch(it -> it.isIterableWrapped() && schema.isInputType(it)))) {
            return code.build();
        }

        return wrapNotNull(sourceName, code.add(context.wrapFields(fieldCode.build())).build());
    }

    /**
     * @return Code for adding error types and calling transform methods.
     */
    public static CodeBlock generateSchemaOutputs(MapperContext mapperContext, boolean returnsRecord, ProcessedSchema schema) {
        if (!mapperContext.targetIsType()) {
            return empty();
        }

        var code = CodeBlock.builder();
        if (mapperContext.hasRecordReference() && mapperContext.isTopLevelContext()) {
            code.add(declare(asListedNameIf(mapperContext.getTargetName(), mapperContext.isIterable()), mapperContext.getRecordTransform(mapperContext.getTarget().getName())));
        } else if (!mapperContext.hasRecordReference()) {
            code.add(declareVariable(mapperContext.getTargetName(), mapperContext.getTargetType().getGraphClassName()));
        }

        code.add("\n");

        for (var innerField : mapperContext.getTargetType().getFields()) {
            var innerContext = mapperContext.iterateContext(innerField);

            if (innerContext.shouldUseException()) {
                continue;
            }

            var previousTarget = innerContext.getPreviousContext().getTarget();

            var innerCode = CodeBlock.builder();

            if (!innerField.isExplicitlyNotGenerated() && !innerContext.getPreviousContext().hasRecordReference()) {
                if (!innerContext.targetIsType()) {
                    innerCode.add(innerContext.getSetMappingBlock(getFieldSetContent((ObjectField) innerField, (ObjectField) previousTarget, returnsRecord, schema)));
                } else if (innerContext.shouldUseStandardRecordFetch()) {
                    innerCode.add(innerContext.getRecordSetMappingBlock(previousTarget.getName()));
                } else if (innerContext.hasRecordReference()) {
                    var fetchCode = createIdFetch(innerField, previousTarget.getName(), innerContext.getPath(), true);
                    if (innerContext.isIterable()) {
                        var tempName = asQueryNodeMethod(innerField.getTypeName());
                        innerCode
                                .add(declare(tempName, fetchCode))
                                .add(innerContext.getSetMappingBlock(CodeBlock.of("$N.stream().map(it -> $N.get(it.getId()))$L", previousTarget.getName(), tempName, collectToList())));
                    } else {
                        innerCode.add(innerContext.getSetMappingBlock(fetchCode)); // TODO: Should be done outside for? Preferably devise some general dataloader-like solution applying to query classes.
                    }
                } else {
                    innerCode.add(generateSchemaOutputs(innerContext, returnsRecord, schema));
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

    public static CodeBlock createIdFetch(GenerationField field, String varName, String path, boolean atResolver) {
        return getNodeQueryCallBlock(field, varName, !atResolver ? CodeBlock.of("$N + $S", PATH_HERE_NAME, path) : CodeBlock.of("$S", path), false, field.isIterableWrapped(), atResolver);
    }

    private static CodeBlock getFieldSetContent(ObjectField field, ObjectField previousField, boolean serviceReturnsRecord, ProcessedSchema schema) {
        var resultName = asIterableResultNameIf(previousField.getUnprocessedFieldOverrideInput(), previousField.isIterableWrapped());
        if (schema.isObject(previousField) && (serviceReturnsRecord || schema.isRecordType(field))) {
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
