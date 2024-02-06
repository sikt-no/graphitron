package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.TransformScope;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.CONTEXT_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.ARRAY_LIST;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class RecordMapperMethodGenerator extends AbstractMappingMethodGenerator {
    public RecordMapperMethodGenerator(InputField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
    }

    @Override
    public MethodSpec generate(InputField target) {
        if (!processedSchema.isTableInputType(target)) {
            return MethodSpec.methodBuilder(recordTransformMethod(false)).build();
        }

        return getMapperSpecBuilder(target)
                .addCode("$L\n", fillRecords(target, "", "", 0))
                .addStatement("return $N", asListedRecordName(target.getTypeName()))
                .build();
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    @NotNull
    protected CodeBlock fillRecords(InputField target, String previousName, String path, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target);
        var hasTable = input.hasTable();
        var targetName = recursion == 0 ? uncapitalize(input.getName()) : uncapitalize(target.getName());
        var name = hasTable ? targetName : previousName;
        var recordName = asRecordName(name);

        var code = CodeBlock.builder();
        var isIterable = target.isIterableWrapped() || recursion == 0;
        var iterableInputName = asIterableIf(targetName, isIterable);
        if (isIterable) {
            if (!hasTable) {
                return empty();
            }

            code
                    .beginControlFlow("$L", ifNotNull(targetName))
                    .beginControlFlow("for (var $L : $N)", iterableInputName, targetName)
                    .addStatement("if ($N == null) continue", iterableInputName)
                    .add(declareRecord(name, input.getRecordClassName()));
        }

        var containedInputs = input.getInputsSortedByNullability().stream().filter(it -> !processedSchema.isTableInputType(it)).collect(Collectors.toList());
        for (var in : containedInputs) {
            var inName = in.getName();
            var inTypeName = in.getTypeName();
            var nextPath = path.isEmpty() ? inName : path + "/" + inName;
            var getCall = CodeBlock.of("$N$L", iterableInputName, in.getMappingFromFieldName().asGetCall());

            if (processedSchema.isInputType(in)) {
                code
                        .add(declareBlock(uncapitalize(inName), getCall))
                        .add(fillRecords(in, name, nextPath, recursion + 1));
            } else {
                code.add(mapField(nextPath, recordName, in.getRecordSetCall("$L"), applyEnumConversion(inTypeName, getCall)));
            }
        }

        if (isIterable) {
            code.add(addToList(asListedName(recordName), recordName)).endControlFlow().endControlFlow();
        }

        if (!code.isEmpty() && recursion == 0) { // Note: This is done after records are filled.
            code.add(applyGlobalTransforms(recordName, input.getRecordClassName(), TransformScope.ALL_MUTATIONS));
        }

        return code.isEmpty() || isIterable ? code.build() : CodeBlock
                .builder()
                .beginControlFlow("$L", ifNotNull(iterableInputName))
                .add(code.build())
                .endControlFlow()
                .build();
    }

    /**
     * @param recordName Name of the record to transform.
     * @param scope      The scope of transforms that should be applied. Currently only {@link TransformScope#ALL_MUTATIONS} is supported.
     * @return CodeBlock where all defined global transforms are applied to the record.
     */
    protected static CodeBlock applyGlobalTransforms(String recordName, TypeName recordTypeName, TransformScope scope) {
        var code = CodeBlock.builder();
        GeneratorConfig
                .getGlobalTransforms(scope)
                .stream()
                .filter(it -> GeneratorConfig.getExternalReferences().contains(it.getName()))
                .map(it -> GeneratorConfig.getExternalReferences().getMethodFrom(it.getName(), it.getMethod(), false))
                .forEach(transform -> code.add(applyTransform(recordName, recordTypeName, transform)));
        return code.build();
    }

    /**
     * @param recordName Name of the record to transform.
     * @param transform  The method that should transform the record.
     * @return CodeBlock where the transform is applied to the record.
     */
    protected static CodeBlock applyTransform(String recordName, TypeName recordTypeName, Method transform) {
        var declaringClass = transform.getDeclaringClass();
        return CodeBlock.builder().addStatement(
                "$N = ($T<$T>) $T.$L($N, $N)",
                asListedName(recordName),
                ARRAY_LIST.className,
                recordTypeName,
                ClassName.get(declaringClass),
                transform.getName(),
                CONTEXT_NAME,
                asListedName(recordName)
        ).build();
    }
}
