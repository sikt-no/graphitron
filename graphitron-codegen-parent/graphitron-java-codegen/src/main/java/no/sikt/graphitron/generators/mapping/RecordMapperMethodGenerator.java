package no.sikt.graphitron.generators.mapping;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.abstractions.AbstractMapperMethodGenerator;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
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
    protected CodeBlock iterateRecords(MapperContext context) {
        if (context.isIterable() && !context.hasTable() && context.hasSourceName()) {
            return empty();
        }

        var fieldCode = CodeBlock.builder();
        var type = context.getTargetType();
        var fields = (toRecord ? type.getInputsSortedByNullability().stream().filter(it -> !processedSchema.hasJOOQRecord(it)).toList() : type.getFields())
                .stream()
                .filter(it -> !(it.isExplicitlyNotGenerated() || it.isResolver()))
                .toList();
        for (var innerField : fields) {
            if (innerField.getMappingFromFieldOverride().getName().equalsIgnoreCase(ERROR_FIELD.getName())) { //TODO tmp solution to skip mapping Errors as this is handled by "MutationExceptionStrategy"
                continue;
            }

            if (innerField.hasFieldReferences()) { // TODO: Can not handle references in jOOQ mappers as input records do not contain them.
                continue;
            }

            var innerContext = context.iterateContext(innerField);
            var isType = innerContext.targetIsType();
            var previousHadSource = innerContext.getPreviousContext().hasSourceName();

            var innerCode = CodeBlock.builder();
            if (!isType) {
                innerCode.add(innerContext.getFieldSetMappingBlock());
            } else if (!innerField.isResolver() && !innerContext.hasTable()) {
                innerCode.add(iterateRecords(innerContext));
            } else if (!previousHadSource) {
                innerCode.add(innerContext.getRecordSetMappingBlock());
            }

            if (!innerCode.isEmpty()) {
                var varName = innerContext.getHelperVariableName();
                CodeBlock declareBlock;
                if (isType) {
                    declareBlock = declare(varName, toRecord ? innerContext.getSourceGetCallBlock() : CodeBlock.of("new $T()", innerContext.getTargetType().getGraphClassName()));
                    if (toRecord) {
                        fieldCode.add(declareBlock);
                    }
                } else {
                    declareBlock = empty();
                }

                var ifBlock = isType && toRecord ? ifNotNull(varName) : CodeBlock.of("if ($L)", selectionSetLookup(innerContext.getPath(), false, toRecord));
                fieldCode
                        .beginControlFlow("$L", ifBlock)
                        .add(isType && !toRecord && previousHadSource ? declareBlock : empty())
                        .add(innerCode.build())
                        .add(isType && !toRecord && previousHadSource ? innerContext.getSetMappingBlock(varName) : empty())
                        .endControlFlow()
                        .add("\n");
            }
        }

        return context.wrapFields(fieldCode.build());
    }

    @Override
    public boolean mapsJavaRecord() {
        return false;
    }
}
