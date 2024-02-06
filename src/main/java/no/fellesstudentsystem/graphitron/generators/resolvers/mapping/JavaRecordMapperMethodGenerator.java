package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.PATH_HERE_NAME;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.TRANSFORMER_NAME;
import static no.fellesstudentsystem.graphitron.mappings.ReflectionHelpers.classHasMethod;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class JavaRecordMapperMethodGenerator extends AbstractMappingMethodGenerator {
    public JavaRecordMapperMethodGenerator(InputField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
    }

    @Override
    public MethodSpec generate(InputField target) {
        if (!processedSchema.isJavaRecordInputType(target)) {
            return MethodSpec.methodBuilder(recordTransformMethod(true)).build();
        }

        return getMapperSpecBuilder(target)
                .addCode("$L\n", fillRecords(target, "", "", null, 0))
                .addStatement("return $N", asListedName(processedSchema.getInputType(target).getJavaRecordReferenceName()))
                .build();
    }

    /**
     * @return CodeBlock for the mapping of a record.
     */
    @NotNull
    protected CodeBlock transformRecord(InputField target, String path, boolean isJava) {
        return CodeBlock.of(
                "$N.$L($N, $N + $S$L)",
                TRANSFORMER_NAME,
                recordTransformMethod(target.getTypeName(), isJava),
                target.getName(),
                PATH_HERE_NAME,
                path,
                recordValidationEnabled() && !isJava ? CodeBlock.of(", $N + $S", PATH_HERE_NAME, path) : empty() // This one may need more work. Does not actually include indices here, but not sure if needed.
        );
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    @NotNull
    protected CodeBlock fillRecords(InputField target, String previousName, String path, MethodMapping recordMappingBackup, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target);
        var hasRecordReference = input.hasJavaRecordReference();
        var hasTableOrRecordReference = input.hasTable() || hasRecordReference;
        var reference = input.getJavaRecordReference();
        var referenceName = input.getJavaRecordReferenceName();
        var targetName = recursion == 0 ? uncapitalize(input.getName()) : uncapitalize(target.getName());
        var name = hasTableOrRecordReference ? (hasRecordReference ? uncapitalize(referenceName) : targetName) : previousName;

        var code = CodeBlock.builder();
        var isIterable = target.isIterableWrapped() && !hasRecordReference || recursion == 0;
        var iterableInputName = asIterableIf(targetName, isIterable);
        if (isIterable) {
            if (!hasTableOrRecordReference) {
                return empty(); // Can not allow this, because input type may contain multiple fields. These can not be mapped to a single field in any reasonable way.
            }

            code
                    .beginControlFlow("$L", ifNotNull(targetName))
                    .beginControlFlow("for (var $L : $N)", iterableInputName, targetName)
                    .addStatement("if ($N == null) continue", iterableInputName)
                    .add(declareVariable(referenceName, input.getJavaRecordTypeName()));
        }

        for (var in : input.getInputsSortedByNullability()) {
            var inName = in.getName();
            var inNameLower = uncapitalize(inName);
            var inTypeName = in.getTypeName();
            var nextPath = path.isEmpty() ? inName : path + "/" + inName;
            var isInput = processedSchema.isInputType(in);
            var newInput = processedSchema.getInputType(in);
            var nextHasReference = isInput && newInput.hasJavaRecordReference();
            var methodMapping = in.hasRecordFieldName() || recordMappingBackup == null ? in.getMappingForJavaRecord() : recordMappingBackup;
            if (hasRecordReference && !classHasMethod(reference, methodMapping.asSet()) && (!isInput || newInput.hasTable())) {
                continue;
            }

            var getCall = CodeBlock.of("$N$L", iterableInputName, in.getMappingFromFieldName().asGetCall());
            if (isInput) {
                code.add(declareBlock(inNameLower, getCall));
            }

            if (isInput && (newInput.hasTable() || nextHasReference)) {
                code
                        .beginControlFlow("if ($N != null && $L)", inNameLower, argumentsLookup(nextPath))
                        .add("$N", name)
                        .addStatement(methodMapping.asSetCall("$L"), transformRecord(in, nextPath, nextHasReference))
                        .endControlFlow()
                        .add("\n");
            } else if (isInput) {
                code.add(fillRecords(in, name, nextPath, methodMapping, recursion + 1));
            } else {
                code.add(mapField(nextPath, name, methodMapping.asSetCall("$L"), applyEnumConversion(inTypeName, getCall)));
            }
        }

        if (isIterable) {
            if (hasTableOrRecordReference) {
                code.add(addToList(asListedName(name), name));
            }
            code.endControlFlow().endControlFlow();
        }

        return code.isEmpty() || isIterable ? code.build() : CodeBlock
                .builder()
                .beginControlFlow("$L", ifNotNull(iterableInputName))
                .add(code.build())
                .endControlFlow()
                .build();
    }
}
