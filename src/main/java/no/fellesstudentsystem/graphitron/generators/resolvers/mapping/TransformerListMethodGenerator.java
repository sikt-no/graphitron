package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapList;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.STRING;

public class TransformerListMethodGenerator extends TransformerMethodGenerator {
    public TransformerListMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        var toRecord = target.isInput();
        if (!processedSchema.isTableType(target)) {
            return MethodSpec.methodBuilder(recordTransformMethod(target.getTypeName(), false, toRecord)).build();
        }

        var type = processedSchema.getTableType(target);
        var hasReference = type.hasJavaRecordReference();
        var methodName = recordTransformMethod(type.getName(), hasReference, toRecord);
        if (!type.hasRecordReference()) {
            return MethodSpec.methodBuilder(methodName).build();
        }

        var mapperClass = ClassName.get(GeneratorConfig.outputPackage() + "." + RecordMapperClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, asRecordMapperClass(type.getName(), hasReference, toRecord));
        var useValidation = toRecord && useValidation(type);
        var spec = getDefaultSpecBuilder(methodName, wrapList(type.asTargetClassName(toRecord)), wrapList(type.asSourceClassName(toRecord))).addStatement(
                "$L$T.$L($N, $N, this)",
                useValidation ? CodeBlock.of("var $L = ", VARIABLE_RECORDS) : CodeBlock.of("return "),
                mapperClass,
                recordTransformMethod(hasReference, toRecord),
                VARIABLE_INPUT,
                PATH_NAME
        );

        if (!useValidation) {
            return spec.build();
        }

        return spec
                .addParameter(STRING.className, PATH_INDEX_NAME)
                .addStatement(
                        "$N.addAll($T.$L($N, $N, this))",
                        VALIDATION_ERRORS_NAME,
                        mapperClass,
                        recordValidateMethod(),
                        VARIABLE_RECORDS,
                        PATH_INDEX_NAME
                )
                .addCode(returnWrap(VARIABLE_RECORDS))
                .build();
    }
}
