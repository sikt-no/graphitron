package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.helpers.ServiceWrapper;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractMethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.LIST;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.STRING;

public class TransformerMethodGenerator extends AbstractMethodGenerator<GenerationField> {
    protected static final String VARIABLE_INPUT = "input", VARIABLE_RECORDS = "records";

    public TransformerMethodGenerator(ProcessedSchema processedSchema) {
        super(null, processedSchema);
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        var typeName = target.getTypeName();
        var toRecord = target.isInput();
        if (!processedSchema.isRecordType(target)) {
            return MethodSpec.methodBuilder(recordTransformMethod(typeName, false, toRecord)).build();
        }

        var type = processedSchema.getRecordType(target);
        var methodName = recordTransformMethod(type.getName(), type.hasJavaRecordReference(), toRecord);
        var returnType = type.asTargetClassName(toRecord);
        var currentSource = type.asSourceClassName(toRecord);
        var noRecordIterability = currentSource == null && target.isIterableWrapped();
        var spec = getDefaultSpecBuilder(
                methodName,
                wrapListIf(returnType, noRecordIterability),
                getSource(currentSource, target)
        );
        var useValidation = toRecord && useValidation(type);
        if (useValidation) {
            spec.addParameter(STRING.className, PATH_INDEX_NAME);
        }
        if (currentSource == null) {
            var hasReference = type.hasJavaRecordReference();
            var mapperClass = ClassName.get(GeneratorConfig.outputPackage() + "." + RecordMapperClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, asRecordMapperClass(type.getName(), hasReference, toRecord));
            spec.addStatement(transformCallCode(useValidation, mapperClass, hasReference, toRecord));

            if (!useValidation) {
                return spec.build();
            }

            return spec
                    .addStatement(validateCode(mapperClass))
                    .addCode(returnWrap(VARIABLE_RECORDS))
                    .build(); // TODO: Test this.
        }

        spec.addStatement(
                "return $N($T.of($N), $N$L).stream().findFirst().orElse($L)",
                methodName,
                LIST.className,
                VARIABLE_INPUT,
                PATH_NAME,
                useValidation ? CodeBlock.of(", $N", PATH_INDEX_NAME) : empty(),
                toRecord ? CodeBlock.of("new $T()", returnType) : CodeBlock.of("null")
        );

        return spec.build();
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

    private TypeName getSource(ClassName currentSource, GenerationField target) {
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
                .filter(processedSchema::isRecordType)
                .collect(Collectors.toMap(processedSchema::getRecordType, Function.identity(), (it1, it2) -> it1)) // Filter duplicates if multiple fields use the same input type.
                .values()
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
