package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DBMethodGenerator;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declare;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

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

    public UpdateDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
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

        var spec = getDefaultSpecBuilder(asQueryMethodName(target.getName(), getLocalObject().getName()), TypeName.INT);

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
                code.add(declare(VARIABLE_RECORD_LIST, ARRAY_LIST.className));
                recordInputs.forEach((name, type) -> code.addStatement("$N.$L($N)", VARIABLE_RECORD_LIST, type.isIterableWrapped() ? "addAll" : "add", name));
            }
            code.addStatement(
                    "return $N.transactionResult(config -> $T.stream($T.using(config).$L($N).execute()).sum())",
                    VariableNames.CONTEXT_NAME,
                    ARRAYS.className,
                    DSL.className,
                    recordMethod,
                    batchInputVariable
            );
        }

        return spec
                .addCode(code.build())
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        var localObject = getLocalObject();
        if (localObject.isExplicitlyNotGenerated()) {
            return List.of();
        }
        return localObject
                .getFields()
                .stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(ObjectField::hasMutationType)
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
