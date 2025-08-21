package no.sikt.graphitron.generators.mapping;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.abstractions.AbstractMapperMethodGenerator;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.selectionSetLookup;
import static no.sikt.graphitron.generators.codebuilding.MappingCodeBlocks.idFetchAllowingDuplicates;

public class JavaRecordMapperMethodGenerator extends AbstractMapperMethodGenerator {
    public JavaRecordMapperMethodGenerator(GenerationField localField, ProcessedSchema processedSchema, boolean toRecord) {
        super(localField, processedSchema, toRecord);
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        return getMapperSpecBuilder(target).build();
    }

    /**
     * @return Code for setting the record data from input types.
     */
    @NotNull
    protected CodeBlock iterateRecords(MapperContext context) {
        if (context.isIterable() && !context.hasRecordReference()) {
            return CodeBlock.empty(); // Can not allow this, because input type may contain multiple fields. These can not be mapped to a single field in any reasonable way.
        }

        var fieldCode = CodeBlock.builder();
        var fields = context
                .getTargetType()
                .getFields()
                .stream()
                .filter(it -> !(it.isExplicitlyNotGenerated() || (it.isResolver() && toRecord)))
                .toList();
        for (var innerField : fields) {
            var innerContext = context.iterateContext(innerField);
            if (innerContext.targetCanNotBeMapped()) {
                continue;
            }

            var varName = innerContext.getHelperVariableName();
            var innerCode = CodeBlock.builder();
            if (!innerContext.getTarget().isResolver()) {
                if (!innerContext.targetIsType()) {
                    innerCode.add(innerContext.getFieldSetMappingBlock());
                }  else if (innerContext.shouldUseStandardRecordFetch()) {
                    innerCode.add(innerContext.getRecordSetMappingBlock());
                } else if (innerContext.hasRecordReference()) {
                    innerCode.add(idFetchAllowingDuplicates(innerContext, innerField, varName, false));
                }//else if (innerContext.hasRecordReference()) {
                  //  innerCode.add(innerContext.getRecordSetMappingBlock());
                 else {
                    innerCode.add(iterateRecords(innerContext));
                }
            } else {
                innerCode.add(innerContext.getResolverKeySetMappingBlockForJavaRecord(varName));
            }

            if (!innerCode.isEmpty()) {
                var notAlreadyDefined = innerContext.variableNotAlreadyDeclared();
                var shouldDeclareVariable = notAlreadyDefined || innerContext.getTarget().isResolver();
                var nullBlock = CodeBlock.ofIf(shouldDeclareVariable, "$N != null && ", varName);
                fieldCode
                        .declareIf(shouldDeclareVariable, varName, innerContext.getSourceGetCallBlock())
                        .beginControlFlow("if ($L$L)", nullBlock, selectionSetLookup(innerContext.getPath(), false, toRecord))
                        .add(innerCode.build())
                        .endControlFlow()
                        .add("\n");
            }
        }

        return context.wrapFields(fieldCode.build());
    }

    @Override
    public boolean mapsJavaRecord() {
        return true;
    }
}
