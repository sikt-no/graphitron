package no.sikt.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.helpers.ServiceWrapper;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.generators.abstractions.AbstractMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.LIST;
import static no.sikt.graphitron.mappings.JavaPoetClassName.STRING;

public class TransformerMethodGenerator extends AbstractMethodGenerator<GenerationField> {
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
        var spec = getDefaultSpecBuilder(
                methodName,
                wrapListIf(type.asTargetClassName(toRecord), currentSource == null && target.isIterableWrapped()),
                getSource(currentSource, target)
        );
        if (toRecord && useValidation(type)) {
            spec.addParameter(STRING.className, PATH_INDEX_NAME);
        }

        return spec.addCode(getMethodContent(target)).build();
    }

    protected CodeBlock getMethodContent(GenerationField target) {
        var toRecord = target.isInput();

        var type = processedSchema.getRecordType(target);
        var code = CodeBlock.builder();
        var useValidation = toRecord && useValidation(type);
        if (type.asSourceClassName(toRecord) == null) {
            var hasReference = type.hasJavaRecordReference();
            var mapperClass = getGeneratedClassName(RecordMapperClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, asRecordMapperClass(type.getName(), hasReference, toRecord));
            code.addStatement(transformCallCode(useValidation, mapperClass, hasReference, toRecord));

            if (!useValidation) {
                return code.build();
            }

            return code
                    .addStatement(validateCode(mapperClass))
                    .add(returnWrap(VARIABLE_RECORDS))
                    .build(); // TODO: Test this.
        }

        return code.addStatement(
                "return $N($T.of($N), $N$L).stream().findFirst().orElse($L)",
                recordTransformMethod(type.getName(), type.hasJavaRecordReference(), toRecord),
                LIST.className,
                VARIABLE_INPUT,
                PATH_NAME,
                useValidation ? CodeBlock.of(", $N", PATH_INDEX_NAME) : empty(),
                toRecord ? CodeBlock.of("new $T()", type.getRecordClassName()) : CodeBlock.of("null")
        ).build();
    }

    protected static CodeBlock transformCallCode(boolean useValidation, ClassName mapperClass, boolean hasReference, boolean toRecord) {
        return CodeBlock.of(
                "$L$T.$L($N, $N, this)",
                useValidation ? CodeBlock.of("var $L = ", VARIABLE_RECORDS) : CodeBlock.of("return "),
                mapperClass,
                recordTransformMethod(hasReference, toRecord),
                VARIABLE_INPUT,
                PATH_NAME
        );
    }

    protected static CodeBlock validateCode(ClassName mapperClass) {
        return CodeBlock.of("$N.addAll($T.$L($N, $N, this))", VALIDATION_ERRORS_NAME, mapperClass, recordValidateMethod(), VARIABLE_RECORDS, PATH_INDEX_NAME);
    }

    protected TypeName getSource(ClassName currentSource, GenerationField target) {
        if (currentSource != null || !target.hasServiceReference()) {
            return currentSource;
        }

        return new ServiceWrapper(target, processedSchema.getObject(target)).getGenericReturnType();
    }

    protected MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType, TypeName source) {
        return getDefaultSpecBuilder(methodName, returnType)
                .addParameter(source, VARIABLE_INPUT)
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
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        return getLocalObject() != null && getLocalObject().isGenerated();
    }
}
