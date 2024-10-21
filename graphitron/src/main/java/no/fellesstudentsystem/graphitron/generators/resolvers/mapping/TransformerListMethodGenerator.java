package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapList;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asRecordMapperClass;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.recordTransformMethod;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.PATH_INDEX_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.STRING;

public class TransformerListMethodGenerator extends TransformerMethodGenerator {
    public TransformerListMethodGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        var toRecord = target.isInput();
        var type = processedSchema.getRecordType(target);
        var methodName = recordTransformMethod(type.getName(), type.hasJavaRecordReference(), toRecord);
        if (!type.hasRecordReference()) {
            return MethodSpec.methodBuilder(methodName).build();
        }

        var spec = getDefaultSpecBuilder(methodName, wrapList(type.asTargetClassName(toRecord)), wrapList(type.asSourceClassName(toRecord)));
        if (toRecord && useValidation(type)) {
            spec.addParameter(STRING.className, PATH_INDEX_NAME);
        }

        return spec.addCode(getMethodContent(target)).build();
    }

    @Override
    protected CodeBlock getMethodContent(GenerationField target) {
        var toRecord = target.isInput();

        var type = processedSchema.getRecordType(target);
        var hasReference = type.hasJavaRecordReference();

        var mapperClass = ClassName.get(GeneratorConfig.outputPackage() + "." + RecordMapperClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, asRecordMapperClass(type.getName(), hasReference, toRecord));
        var useValidation = toRecord && useValidation(type);
        var code = CodeBlock
                .builder()
                .addStatement(transformCallCode(useValidation, mapperClass, hasReference, toRecord));

        if (!useValidation) {
            return code.build();
        }

        return code
                .addStatement(validateCode(mapperClass))
                .add(returnWrap(VARIABLE_RECORDS))
                .build();
    }
}
