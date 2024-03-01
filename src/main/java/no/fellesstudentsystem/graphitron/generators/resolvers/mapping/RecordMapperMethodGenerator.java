package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractMapperMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.recordTransformMethod;

public class RecordMapperMethodGenerator extends AbstractMapperMethodGenerator<GenerationField> {
    public RecordMapperMethodGenerator(GenerationField localField, ProcessedSchema processedSchema, boolean toRecord) {
        super(localField, processedSchema, toRecord);
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        if (!processedSchema.hasTable(target)) {
            return MethodSpec.methodBuilder(recordTransformMethod(false, toRecord)).build();
        }

        return getMapperSpecBuilder(target).build();
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    @NotNull
    protected CodeBlock iterateRecords(MapperContext context) {
        if (context.isIterable() && !context.hasTable()) {
            return empty();
        }

        var fieldCode = CodeBlock.builder();
        var type = context.getTargetType();
        var fields = (toRecord ? type.getInputsSortedByNullability().stream().filter(it -> !processedSchema.isTableInputType(it)).collect(Collectors.toList()) : type.getFields())
                .stream()
                .filter(it -> !(it.isExplicitlyNotGenerated() || it.isResolver()))
                .collect(Collectors.toList());
        for (var innerField : fields) {
            var innerContext = context.iterateContext(innerField);
            var isType = innerContext.targetIsType();

            var innerCode = CodeBlock.builder();
            if (!isType) {
                innerCode.add(innerContext.getFieldSetMappingBlock());
            } else if (!innerField.isResolver() && !innerContext.hasTable()) {
                innerCode.add(iterateRecords(innerContext));
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

                var ifBlock = isType && toRecord ? ifNotNull(varName) : CodeBlock.of("if ($L)", argumentsLookup(innerContext.getPath(), false));
                fieldCode
                        .beginControlFlow("$L", ifBlock)
                        .add(isType && !toRecord ? declareBlock : empty())
                        .add(innerCode.build())
                        .add(isType && !toRecord ? innerContext.getSetMappingBlock(varName) : empty())
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
