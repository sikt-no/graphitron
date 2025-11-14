package no.sikt.graphitron.generators.mapping;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.abstractions.AbstractMapperMethodGenerator;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static no.sikt.graphitron.configuration.GeneratorConfig.getRecordValidation;
import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.recordValidateMethod;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapList;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapSet;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.mappings.JavaPoetClassName.GRAPHQL_ERROR;
import static no.sikt.graphitron.mappings.JavaPoetClassName.HASH_SET;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class RecordValidatorMethodGenerator extends AbstractMapperMethodGenerator {
    public RecordValidatorMethodGenerator(GenerationField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema, false); // It operates on records as input, so technically false.
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        var methodName = recordValidateMethod();
        if (!processedSchema.hasInputJOOQRecord(target) || !getRecordValidation().isEnabled()) {
            return MethodSpec.methodBuilder(methodName).build();
        }

        var input = processedSchema.getInputType(target);
        return getDefaultSpecBuilder(methodName, inputPrefix(input.getRecordReferenceName()), wrapList(input.getRecordClassName()), wrapSet(GRAPHQL_ERROR.className))
                .declare(VAR_ARGS, asMethodCall(VAR_TRANSFORMER, METHOD_ARGS_NAME))
                .declare(VAR_ENV, asMethodCall(VAR_TRANSFORMER, METHOD_ENV_NAME))
                .declareNew(VAR_VALIDATION_ERRORS, ParameterizedTypeName.get(HASH_SET.className, GRAPHQL_ERROR.className))
                .addCode("\n")
                .addCode("$L\n", iterateRecords(MapperContext.createValidationContext(target, processedSchema)))
                .addCode(returnWrap(VAR_VALIDATION_ERRORS))
                .build();
    }

    /**
     * @return Code for setting the validation paths.
     */
    @NotNull
    protected CodeBlock iterateRecords(MapperContext context) {
        if (context.isIterable() && !context.hasTable()) {
            return CodeBlock.empty();
        }

        var fieldCode = CodeBlock.builder();
        var containedInputs = context
                .getTargetType()
                .getInputsSortedByNullability()
                .stream()
                .filter(it -> !processedSchema.hasInputJOOQRecord(it))
                .filter(it -> !(it.isExplicitlyNotGenerated() || it.isResolver()))
                .toList();
        for (var innerField : containedInputs) {
            var innerContext = context.iterateContext(innerField);
            if (innerContext.targetIsType()) {
                fieldCode.add(iterateRecords(innerContext));
            } else {
                fieldCode
                        .beginControlFlow("if ($L)", selectionSetLookup(innerContext.getIndexPath().replaceAll("(.*?)\"/", ""), false, true))
                        .addStatement("$N.put($S, $N + $L\")", VAR_PATHS_FOR_PROPERTIES, uncapitalize(innerField.getFieldRecordMappingName()), VAR_PATH_HERE, innerContext.getIndexPath())
                        .endControlFlow();
            }
        }

        return context.wrapFields(fieldCode.build());
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (getLocalObject() == null || getLocalObject().isExplicitlyNotGenerated() || !recordValidationEnabled()) {
            return List.of();
        }

        var input = processedSchema.getInputType(getLocalField());
        if (input == null || !input.hasTable() || input.hasJavaRecordReference()) {
            return List.of();
        }

        return List.of(generate(getLocalField()));
    }

    @Override
    public boolean mapsJavaRecord() {
        return false;
    }
}
