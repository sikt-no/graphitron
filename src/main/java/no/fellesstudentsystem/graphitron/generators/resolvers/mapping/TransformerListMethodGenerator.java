package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapList;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.STRING;

public class TransformerListMethodGenerator extends TransformerMethodGenerator {

    public TransformerListMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(InputField target) {
        if (!processedSchema.isInputType(target)) {
            return MethodSpec.methodBuilder(recordTransformMethod(target.getTypeName(), false)).build();
        }

        var input = processedSchema.getInputType(target);
        var hasReference = input.hasJavaRecordReference();
        var methodName = recordTransformMethod(input.getName(), hasReference);
        if (!input.hasTable() && !hasReference) {
            return MethodSpec.methodBuilder(methodName).build();
        }

        var mapperClass = ClassName.get(GeneratorConfig.outputPackage() + "." + RecordMapperClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, asRecordMapperClass(input.getName(), hasReference));
        var spec = getDefaultSpecBuilder(methodName, wrapList(hasReference ? input.getJavaRecordTypeName() : input.getRecordClassName()))
                .addParameter(wrapList(input.getGraphClassName()), VARIABLE_INPUT)
                .addParameter(STRING.className, PATH_NAME)
                .addStatement(
                        "var $L = $T.$L($N, $N, $N, $N$L)",
                        VARIABLE_RECORDS,
                        mapperClass,
                        recordTransformMethod(hasReference),
                        VARIABLE_INPUT,
                        PATH_NAME,
                        VariableNames.VARIABLE_ARGUMENTS,
                        CONTEXT_NAME,
                        hasReference ? ", this" : ""
                );

        if (recordValidationEnabled() && input.hasTable() && !hasReference) {
            spec
                    .addParameter(STRING.className, PATH_INDEX_NAME)
                    .addStatement(
                            "$N.addAll($T.$L($N, $N, $N, $N))",
                            VALIDATION_ERRORS_NAME,
                            mapperClass,
                            recordValidateMethod(),
                            VARIABLE_RECORDS,
                            PATH_INDEX_NAME,
                            VariableNames.VARIABLE_ARGUMENTS,
                            ENV_NAME
                    );
        }

        return spec.addStatement("return $N", VARIABLE_RECORDS).build();
    }
}
