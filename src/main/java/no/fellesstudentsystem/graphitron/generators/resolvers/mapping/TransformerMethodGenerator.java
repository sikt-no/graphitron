package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.RecordObjectDefinition;
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

public class TransformerMethodGenerator extends AbstractMethodGenerator<GenerationField> {
    protected static final String VARIABLE_INPUT = "input", VARIABLE_RECORDS = "records";

    public TransformerMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        var typeName = target.getTypeName();
        var toRecord = target.isInput();
        if (!processedSchema.isTableType(target)) {
            return MethodSpec.methodBuilder(recordTransformMethod(typeName, false, toRecord)).build();
        }

        var type = processedSchema.getTableType(target);
        if (!type.hasRecordReference()) {
            return MethodSpec.methodBuilder(recordTransformMethod(typeName, false, toRecord)).build();
        }

        var methodName = recordTransformMethod(typeName, type.hasJavaRecordReference(), toRecord);
        var returnType = type.asTargetClassName(toRecord);

        var spec = getDefaultSpecBuilder(methodName, returnType, type.asSourceClassName(toRecord));
        var useValidation = toRecord && useValidation(type);
        if (useValidation) {
            spec.addParameter(STRING.className, PATH_INDEX_NAME);
        }
        spec.addStatement(
                "return $N($T.of($N), $N$L).stream().findFirst().orElse($L)",
                methodName,
                LIST.className,
                VARIABLE_INPUT,
                PATH_NAME,
                useValidation ? CodeBlock.of(", $N", PATH_INDEX_NAME) : empty(),
                toRecord ? CodeBlock.of("new $T()", returnType) : CodeBlock.of("null")
        );

        return spec.build();
    }

    protected MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType, TypeName source) {
        return getDefaultSpecBuilder(methodName, returnType)
                .addParameter(source, VARIABLE_INPUT)
                .addParameter(STRING.className, PATH_NAME);
    }

    protected static boolean useValidation(RecordObjectDefinition<?, ?> type) {
        return recordValidationEnabled() && type.hasTable() && !type.hasJavaRecordReference();
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
                .filter(processedSchema::isTableType)
                .collect(Collectors.toMap(processedSchema::getTableType, Function.identity(), (it1, it2) -> it1)) // Filter duplicates if multiple fields use the same input type.
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
