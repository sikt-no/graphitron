package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractMethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.recordTransformMethod;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.PATH_INDEX_NAME;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.PATH_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.LIST;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.STRING;

public class TransformerMethodGenerator extends AbstractMethodGenerator<InputField> {
    protected static final String VARIABLE_INPUT = "input", VARIABLE_RECORDS = "records";

    public TransformerMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(InputField target) {
        if (!processedSchema.isInputType(target)) {
            return MethodSpec.methodBuilder(recordTransformMethod(target.getTypeName(), false)).build();
        }

        var input = processedSchema.getInputType(target);
        var hasReference = input.hasJavaRecordReference();
        if (!input.hasTable() && !hasReference) {
            return MethodSpec.methodBuilder(recordTransformMethod(target.getTypeName(), false)).build();
        }

        var methodName = recordTransformMethod(target.getTypeName(), hasReference);
        var returnType = hasReference ? input.getJavaRecordTypeName() : input.getRecordClassName();

        var spec = getDefaultSpecBuilder(methodName, returnType)
                .addParameter(input.getGraphClassName(), VARIABLE_INPUT)
                .addParameter(STRING.className, PATH_NAME);
        if (recordValidationEnabled() && !hasReference) {
            spec.addParameter(STRING.className, PATH_INDEX_NAME);
        }
        spec.addStatement(
                "return $N($T.of($N), $N$L).stream().findFirst().orElse(new $T())",
                methodName,
                LIST.className,
                VARIABLE_INPUT,
                PATH_NAME,
                recordValidationEnabled() && !hasReference ? CodeBlock.of(", $N", PATH_INDEX_NAME) : empty(),
                returnType
        );

        return spec.build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (getLocalObject() == null || !getLocalObject().isGenerated()) {
            return List.of();
        }

        return getLocalObject()
                .getFields()
                .stream()
                .flatMap(field -> processedSchema.findTableOrRecordFields(field).stream())
                .collect(Collectors.toMap(processedSchema::getInputType, Function.identity(), (it1, it2) -> it1)) // Filter duplicates if multiple fields use the same input type.
                .values()
                .stream()
                .map(this::generate)
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        return getLocalObject() != null && getLocalObject().isGenerated();
    }
}
