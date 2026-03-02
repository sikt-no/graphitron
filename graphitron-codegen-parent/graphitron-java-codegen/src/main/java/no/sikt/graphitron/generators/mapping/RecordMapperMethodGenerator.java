package no.sikt.graphitron.generators.mapping;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.abstractions.AbstractMapperMethodGenerator;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.outputPrefix;
import static no.sikt.graphql.naming.GraphQLReservedName.ERROR_FIELD;

public class RecordMapperMethodGenerator extends AbstractMapperMethodGenerator {
    public RecordMapperMethodGenerator(GenerationField localField, ProcessedSchema processedSchema, boolean toRecord) {
        super(localField, processedSchema, toRecord);
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        return getMapperSpecBuilder(target).build();
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    @NotNull
    @Override
    protected CodeBlock iterateRecords(MapperContext context) {
        if (context.isIterable() && !context.hasTable() && context.hasSourceName()) {
            return CodeBlock.empty();
        }

        var fieldCode = new ArrayList<CodeBlock>();
        var type = context.getTargetType();
        var fields = (toRecord ? type.getInputsSortedByNullability().stream().filter(it -> !processedSchema.hasJOOQRecord(it)).toList() : type.getFields())
                .stream()
                .filter(it -> !(it.isExplicitlyNotGenerated()))
                .toList();
        for (var innerField : fields) {
            if (innerField.getMappingFromFieldOverride().getName().equalsIgnoreCase(ERROR_FIELD.getName())) {
                continue;
            }

            // Can not handle references in jOOQ mappers as input records do not contain them.
            if (!innerField.hasNodeID() && !innerField.isGeneratedWithResolver() && innerField.hasFieldReferences()) {
                continue;
            }

            var innerContext = context.iterateContext(innerField);
            var isType = innerContext.targetIsType();
            MapperContext previousContext = innerContext.getPreviousContext();
            var previousHadSource = previousContext.hasSourceName();

            var innerCode = CodeBlock.builder();
            if (innerField.createsDataFetcher()) {
                if (processedSchema.isOrderedMultiKeyQuery(innerField) || !previousHadSource) {
                    innerCode.add(innerContext.getResolverKeySetMappingBlock(inputPrefix(type.asRecordName())));
                } else {
                    innerCode.add(innerContext.getResolverKeySetMappingBlockForJooqRecord());
                }
            } else if (!isType) {
                innerCode.add(innerContext.getFieldSetMappingBlock());
            } else if (!previousHadSource) {
                innerCode.add(innerContext.getRecordSetMappingBlock());
            } else if (!innerField.createsDataFetcher() && !innerContext.hasTable()) {
                innerCode.add(iterateRecords(innerContext));
            }

            if (!innerCode.isEmpty()) {
                var varName = innerContext.getHelperVariableName();
                var declareBlock = CodeBlock.declareIf(
                        isType && !innerField.createsDataFetcher(),
                        toRecord ? inputPrefix(varName) : outputPrefix(varName),
                        () -> toRecord
                                ? innerContext.getSourceGetCallBlock()
                                : CodeBlock.of("new $T()", innerContext.getTargetType().getGraphClassName())
                );

                if (isType && toRecord) {
                    fieldCode.add(CodeBlock.join(declareBlock, wrapNotNull(inputPrefix(varName), innerCode.build())));
                } else {
                    fieldCode.add(
                            CodeBlock
                                    .builder()
                                    .beginControlFlow("if ($L)", selectionSetLookup(innerContext.getPath(), false, toRecord))
                                    .addIf(isType && previousHadSource, declareBlock)
                                    .add(innerCode.build())
                                    .addIf(isType && previousHadSource && !innerField.createsDataFetcher(), () -> innerContext.getSetMappingBlock(outputPrefix(varName)))
                                    .endControlFlow()
                                    .build()
                    );
                }
            }
        }

        return context.wrapFields(CodeBlock.join(fieldCode, "\n"));
    }

    @Override
    public boolean mapsJavaRecord() {
        return false;
    }
}
