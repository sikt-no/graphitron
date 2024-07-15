package no.fellesstudentsystem.graphitron.generators.db.update;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.fields.containedtypes.MutationType;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.context.InputParser;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.declareVariable;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

/**
 * Generator that creates the default data mutation methods.
 */
public class UpdateDBMethodGenerator extends DBMethodGenerator<ObjectField> {
    private static final Map<MutationType, String> mutationConverter = Map.of(
            MutationType.UPDATE, "batchUpdate",
            MutationType.DELETE, "batchDelete",
            MutationType.INSERT, "batchInsert",
            MutationType.UPSERT, "batchMerge"
    );

    private static final String VARIABLE_RECORD_LIST = "recordList";

    private final ObjectField localField;

    public UpdateDBMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(processedSchema.getMutationType(), processedSchema);
        this.localField = localField;
    }

    /**
     * @param target A {@link ObjectField} for which a mutation method should be generated for.
     *                       This must reference a field located within the Mutation type and with the
     *                       "{@link GenerationDirective#MUTATION mutationType}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField target) {
        var recordMethod = mutationConverter.get(target.getMutationType());
        if (recordMethod == null) {
            return MethodSpec.methodBuilder(target.getName()).build();
        }

        var spec = getDefaultSpecBuilder(target.getName(), TypeName.INT);

        var inputs = new InputParser(target, processedSchema).getMethodInputsWithOrderField();
        inputs.forEach((inputName, inputType) -> spec.addParameter(iterableWrapType(inputType), inputName));

        var recordInputs = inputs
                .entrySet()
                .stream()
                .filter(it -> processedSchema.hasJOOQRecord(it.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var code = CodeBlock.builder();
        if (recordInputs.isEmpty()) {
            code.add(returnWrap("0"));
        } else {
            String batchInputVariable;
            if (recordInputs.size() == 1) {
                batchInputVariable = recordInputs.keySet().stream().findFirst().get();
            } else {
                batchInputVariable = VARIABLE_RECORD_LIST;
                code.add(declareVariable(VARIABLE_RECORD_LIST, ARRAY_LIST.className));
                recordInputs.forEach((name, type) -> code.addStatement("$N.$L($N)", VARIABLE_RECORD_LIST, type.isIterableWrapped() ? "addAll" : "add", name));
            }
            code.beginControlFlow("return $N.transactionResult(configuration -> ", VariableNames.CONTEXT_NAME);
            code.addStatement("$T transactionCtx = $T.using(configuration)", DSL_CONTEXT.className, DSL.className);
            code.addStatement(
                    "return $T.stream(transactionCtx.$L($N).execute()).sum()",
                    ARRAYS.className,
                    recordMethod,
                    batchInputVariable
            );
            code.endControlFlow(")");
        }

        return spec
                .addCode(code.build())
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (localField.isGeneratedWithResolver() && localField.hasMutationType() && !localField.hasServiceReference()) {
            return List.of(generate(localField));
        }
        return List.of();
    }

    @Override
    public boolean generatesAll() {
        return localField.isGeneratedWithResolver() && localField.hasMutationType() && !localField.hasServiceReference();
    }
}
