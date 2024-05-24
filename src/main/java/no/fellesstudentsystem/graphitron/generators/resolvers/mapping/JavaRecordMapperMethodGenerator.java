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
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryNodeMethod;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.recordTransformMethod;

public class JavaRecordMapperMethodGenerator extends AbstractMapperMethodGenerator<GenerationField> {
    public JavaRecordMapperMethodGenerator(GenerationField localField, ProcessedSchema processedSchema, boolean toRecord) {
        super(localField, processedSchema, toRecord);
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        if (!processedSchema.isJavaRecordType(target)) {
            return MethodSpec.methodBuilder(recordTransformMethod(true, toRecord)).build();
        }

        return getMapperSpecBuilder(target).build();
    }

    /**
     * @return Code for setting the record data from input types.
     */
    @NotNull
    protected CodeBlock iterateRecords(MapperContext context) {
        if (context.isIterable() && !context.hasRecordReference()) {
            return empty(); // Can not allow this, because input type may contain multiple fields. These can not be mapped to a single field in any reasonable way.
        }

        var fieldCode = CodeBlock.builder();
        var fields = context.getTargetType().getFields().stream().filter(it -> !(it.isExplicitlyNotGenerated() || it.isResolver())).collect(Collectors.toList());
        for (var innerField : fields) {
            var innerContext = context.iterateContext(innerField);
            if (innerContext.targetCanNotBeMapped()) {
                continue;
            }

            var varName = innerContext.getHelperVariableName();
            var innerCode = CodeBlock.builder();
            if (!innerContext.targetIsType()) {
                innerCode.add(innerContext.getFieldSetMappingBlock());
            } else if (innerContext.shouldUseStandardRecordFetch()) {
                innerCode.add(innerContext.getRecordSetMappingBlock());
            } else if (innerContext.hasRecordReference()) {
                var fetchCode = createIdFetch(innerField, varName, innerContext.getPath(), false);
                if (innerContext.isIterable()) {
                    var tempName = asQueryNodeMethod(innerField.getTypeName());
                    innerCode
                            .add(declare(tempName, fetchCode))
                            .add(innerContext.getSetMappingBlock(CodeBlock.of("$N.stream().map(it -> $N.get(it.getId()))$L", varName, tempName, collectToList())));
                } else {
                    innerCode.add(innerContext.getSetMappingBlock(fetchCode)); // TODO: Should be done outside for? Preferably devise some general dataloader-like solution applying to query classes.
                }
            } else {
                innerCode.add(iterateRecords(innerContext));
            }

            if (!innerCode.isEmpty()) {
                var notAlreadyDefined = innerContext.variableNotAlreadyDeclared();
                if (notAlreadyDefined) {
                    fieldCode.add(declare(varName, innerContext.getSourceGetCallBlock()));
                }
                var nullBlock = notAlreadyDefined ? CodeBlock.of("$N != null && ", varName) : empty();
                fieldCode
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

    @Override
    public boolean generatesAll() {
        return processedSchema.isJavaRecordType(getLocalField());
    }
}
