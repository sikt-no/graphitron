package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.ClassName;
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
        if (!processedSchema.isRecordType(target)) {
            return MethodSpec.methodBuilder(recordTransformMethod(target.getTypeName(), false, toRecord)).build();
        }

        var type = processedSchema.getRecordType(target);
        var hasReference = type.hasJavaRecordReference();
        var methodName = recordTransformMethod(type.getName(), hasReference, toRecord);
        if (!type.hasRecordReference()) {
            return MethodSpec.methodBuilder(methodName).build();
        }

        var mapperClass = ClassName.get(GeneratorConfig.outputPackage() + "." + RecordMapperClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, asRecordMapperClass(type.getName(), hasReference, toRecord));
        var useValidation = toRecord && useValidation(type);
        var spec = getDefaultSpecBuilder(methodName, wrapList(type.asTargetClassName(toRecord)), wrapList(type.asSourceClassName(toRecord)))
                .addStatement(transformCallCode(useValidation, mapperClass, hasReference, toRecord));

        if (!useValidation) {
            return spec.build();
        }

        return spec
                .addParameter(STRING.className, PATH_INDEX_NAME)
                .addStatement(validateCode(mapperClass))
                .addCode(returnWrap(VARIABLE_RECORDS))
                .build();
    }
}
