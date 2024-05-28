package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractMapperMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.getRecordValidation;
import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapList;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapSet;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asListedName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.recordValidateMethod;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator.METHOD_ARGS_NAME;
import static no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator.METHOD_ENV_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.GRAPHQL_ERROR;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.HASH_SET;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class RecordValidatorMethodGenerator extends AbstractMapperMethodGenerator<GenerationField> {
    public RecordValidatorMethodGenerator(GenerationField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema, false); // It operates on records as input, so technically false.
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        var methodName = recordValidateMethod();
        if (!processedSchema.isTableInputType(target) || !getRecordValidation().isEnabled()) {
            return MethodSpec.methodBuilder(methodName).build();
        }

        var input = processedSchema.getInputType(target);
        return getDefaultSpecBuilder(methodName, asListedName(input.getRecordReferenceName()), wrapList(input.getRecordClassName()), wrapSet(GRAPHQL_ERROR.className))
                .addCode(declare(VARIABLE_ARGUMENTS, asMethodCall(TRANSFORMER_NAME, METHOD_ARGS_NAME)))
                .addCode(declare(VARIABLE_ENV, asMethodCall(TRANSFORMER_NAME, METHOD_ENV_NAME)))
                .addCode(declare(VARIABLE_VALIDATION_ERRORS, CodeBlock.of("new $T<$T>()", HASH_SET.className, GRAPHQL_ERROR.className)))
                .addCode("\n")
                .addCode("$L\n", iterateRecords(MapperContext.createValidationContext(target, processedSchema)))
                .addCode(returnWrap(VARIABLE_VALIDATION_ERRORS))
                .build();
    }

    /**
     * @return Code for setting the validation paths.
     */
    @NotNull
    protected CodeBlock iterateRecords(MapperContext context) {
        if (context.isIterable() && !context.hasTable()) {
            return empty();
        }

        var fieldCode = CodeBlock.builder();
        var containedInputs = context
                .getTargetType()
                .getInputsSortedByNullability()
                .stream()
                .filter(it -> !processedSchema.isTableInputType(it))
                .filter(it -> !(it.isExplicitlyNotGenerated() || it.isResolver()))
                .collect(Collectors.toList());
        for (var innerField : containedInputs) {
            var innerContext = context.iterateContext(innerField);
            if (innerContext.targetIsType()) {
                fieldCode.add(iterateRecords(innerContext));
            } else {
                fieldCode
                        .beginControlFlow("if ($L)", selectionSetLookup(innerContext.getIndexPath().replaceAll("(.*?)\"/", ""), false, true))
                        .addStatement("$N.put($S, $N + $L\")", VARIABLE_PATHS_FOR_PROPERTIES, uncapitalize(innerField.getFieldJOOQMappingName()), PATH_HERE_NAME, innerContext.getIndexPath())
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
    public boolean generatesAll() {
        return (getLocalObject() == null || !recordValidationEnabled() || !getLocalObject().isExplicitlyNotGenerated()) && processedSchema.isTableInputType(getLocalField());
    }

    @Override
    public boolean mapsJavaRecord() {
        return false;
    }
}
