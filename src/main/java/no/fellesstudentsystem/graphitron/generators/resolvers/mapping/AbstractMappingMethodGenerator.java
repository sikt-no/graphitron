package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapList;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapSet;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asRecordName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

abstract public class AbstractMappingMethodGenerator extends AbstractMethodGenerator<InputField> {
    private final InputField localField;

    public AbstractMappingMethodGenerator(InputField localField, ProcessedSchema processedSchema) {
        super(processedSchema.getMutationType(), processedSchema);

        this.localField = localField;
    }

    public InputField getLocalField() {
        return localField;
    }

    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, String inputName, TypeName inputType, TypeName returnType) {
        return getDefaultSpecBuilder(methodName, returnType)
                .addModifiers(Modifier.STATIC)
                .addParameter(wrapList(inputType), uncapitalize(inputName))
                .addParameter(STRING.className, PATH_NAME)
                .addParameter(wrapSet(STRING.className), VARIABLE_ARGUMENTS)
                .addCode(declareBlock(PATH_HERE_NAME, addStringIfNotEmpty(PATH_NAME, "/")));
    }

    public MethodSpec.Builder getMapperSpecBuilder(InputField target) {
        var input = processedSchema.getInputType(target);
        var hasReference = input.hasJavaRecordReference();
        var returnType = hasReference ? input.getJavaRecordTypeName() : input.getRecordClassName();
        var spec = getDefaultSpecBuilder(NameFormat.recordTransformMethod(hasReference), target.getTypeName(), input.getGraphClassName(), wrapList(returnType));
        if (hasReference) {
            spec.addParameter(INPUT_TRANSFORMER.className, TRANSFORMER_NAME);
        } else {
            spec.addParameter(DSL_CONTEXT.className, CONTEXT_NAME);
        }

        spec
                .addCode(declareArrayList(hasReference ? input.getJavaRecordReferenceName() : asRecordName(input.getName()), returnType))
                .addCode("\n");
        return spec;
    }

    protected CodeBlock mapField(String nextPath, String source, String setCall, CodeBlock getCall) {
        return CodeBlock
                .builder()
                .beginControlFlow("if ($L)", argumentsLookup(nextPath))
                .add("$N", source)
                .addStatement(setCall, getCall)
                .endControlFlow()
                .build();
    }

    protected CodeBlock applyEnumConversion(String typeName, CodeBlock getCall) {
        return processedSchema.isEnum(typeName) ? toGraphEnumConverter(typeName, getCall) : getCall;
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (getLocalObject() == null || !getLocalObject().isGenerated()) {
            return List.of();
        }

        return List.of(generate(getLocalField()));
    }

    @Override
    public boolean generatesAll() {
        return getLocalObject() == null || getLocalObject().isGenerated();
    }
}
