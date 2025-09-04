package no.sikt.graphitron.generators.mapping;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.generators.abstractions.AbstractSchemaMethodGenerator;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

public class TransformerMethodGenerator extends AbstractSchemaMethodGenerator<GenerationField, RecordObjectSpecification<GenerationField>> {
    protected static final String VARIABLE_INPUT = "input", VARIABLE_RECORDS = "records";

    public TransformerMethodGenerator(ProcessedSchema processedSchema) {
        super(null, processedSchema);
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        var toRecord = target.isInput();
        var type = processedSchema.getRecordType(target);
        var methodName = recordTransformMethod(type.getName(), type.hasJavaRecordReference(), toRecord);
        var currentSource = type.asSourceClassName(toRecord);
        return getDefaultSpecBuilder(
                methodName,
                wrapListIf(type.asTargetClassName(toRecord), currentSource == null && target.isIterableWrapped()),
                currentSource != null || !target.hasServiceReference() ? currentSource : target.getExternalMethod().getGenericReturnType()
        )
                .addParameterIf(toRecord && useValidation(type), STRING.className, PATH_INDEX_NAME)
                .addCode(getMethodContent(target))
                .build();
    }

    protected CodeBlock getMethodContent(GenerationField target) {
        var toRecord = target.isInput();

        var type = processedSchema.getRecordType(target);
        var useValidation = toRecord && useValidation(type);
        if (type.asSourceClassName(toRecord) == null) {
            var hasReference = type.hasJavaRecordReference();
            var mapperClass = getGeneratedClassName(RecordMapperClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, asRecordMapperClass(type.getName(), hasReference, toRecord));

            var code = CodeBlock
                    .builder()
                    .addStatement(transformCallCode(useValidation, mapperClass, hasReference, toRecord));

            if (!useValidation) {
                return code.build();
            }

            return code
                    .addStatement(validateCode(mapperClass))
                    .add(returnWrap(VARIABLE_RECORDS))
                    .build(); // TODO: Test this.
        }

        return CodeBlock.statementOf(
                "return $N($T.of($N)$L, $N$L).stream().findFirst().orElse($L)",
                recordTransformMethod(type.getName(), type.hasJavaRecordReference(), toRecord),
                LIST.className,
                VARIABLE_INPUT,
                CodeBlock.ofIf(shouldMakeNodeStrategy(), ", $N", NODE_ID_STRATEGY_NAME),
                PATH_NAME,
                CodeBlock.ofIf(useValidation, ", $N", PATH_INDEX_NAME),
                toRecord ? CodeBlock.of("new $T()", type.getRecordClassName()) : CodeBlock.of("null")
        );
    }

    protected static CodeBlock transformCallCode(boolean useValidation, ClassName mapperClass, boolean hasReference, boolean toRecord) {
        return CodeBlock.of(
                "$L$T.$L($N,$L $N, this)",
                useValidation ? CodeBlock.of("var $L = ", VARIABLE_RECORDS) : CodeBlock.of("return "),
                mapperClass,
                recordTransformMethod(hasReference, toRecord),
                VARIABLE_INPUT,
                CodeBlock.ofIf(shouldMakeNodeStrategy(), " $N,", NODE_ID_STRATEGY_NAME),
                PATH_NAME
        );
    }

    protected static CodeBlock validateCode(ClassName mapperClass) {
        return CodeBlock.of("$N.addAll($T.$L($N, $N, this))", VALIDATION_ERRORS_NAME, mapperClass, recordValidateMethod(), VARIABLE_RECORDS, PATH_INDEX_NAME);
    }

    protected MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType, TypeName source) {
        return getDefaultSpecBuilder(methodName, returnType)
                .addParameter(source, VARIABLE_INPUT)
                .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, NODE_ID_STRATEGY_NAME)
                .addParameter(STRING.className, PATH_NAME);
    }

    protected static boolean useValidation(RecordObjectSpecification<?> type) {
        return recordValidationEnabled() && type.hasTable() && !type.hasJavaRecordReference();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return processedSchema
                .getTransformableFields()
                .stream()
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
