package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.recordTransformMethod;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

abstract public class AbstractMapperMethodGenerator extends AbstractSchemaMethodGenerator<GenerationField, RecordObjectSpecification<?>> {
    private final GenerationField localField;
    protected final boolean toRecord;

    public AbstractMapperMethodGenerator(GenerationField localField, ProcessedSchema processedSchema, boolean toRecord) {
        super(processedSchema.getRecordType(localField.getContainerTypeName()), processedSchema);

        this.localField = localField;
        this.toRecord = toRecord;
    }

    public GenerationField getLocalField() {
        return localField;
    }

    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, String inputName, TypeName inputType, TypeName returnType) {
        var builder = getDefaultSpecBuilder(methodName, returnType)
                .addModifiers(Modifier.STATIC)
                .addParameter(inputType, uncapitalize(inputName));

        if (GeneratorConfig.shouldMakeNodeStrategy() && !methodName.equals(METHOD_VALIDATE_NAME)) {
            builder.addParameter(NODE_ID_STRATEGY.className, NODE_ID_STRATEGY_NAME);
        }
        return builder
                .addParameter(STRING.className, PATH_NAME)
                .addParameter(RECORD_TRANSFORMER.className, TRANSFORMER_NAME)
                .addCode(declare(PATH_HERE_NAME, addStringIfNotEmpty(PATH_NAME, "/")));
    }

    public MethodSpec.Builder getMapperSpecBuilder(GenerationField target) {
        var context = MapperContext.createContext(target, toRecord, mapsJavaRecord(), processedSchema);
        var methodName = recordTransformMethod(context.hasJavaRecordReference(), toRecord);

        var fillCode = iterateRecords(context); // Note, do before declaring dependencies.
        var type = processedSchema.getRecordType(target);
        var currentSource = type.asSourceClassName(toRecord);
        var source = wrapListIf(currentSource != null ? currentSource : target.getService().getGenericReturnType(), context.hasSourceName());
        var noRecordIterability = !context.hasSourceName() && target.isIterableWrapped();
        return getDefaultSpecBuilder(methodName, context.getInputVariableName(), source, wrapListIf(context.getReturnType(), noRecordIterability || context.hasRecordReference()))
                .addCode(declare(toRecord ? VARIABLE_ARGS : VARIABLE_SELECT, asMethodCall(TRANSFORMER_NAME, toRecord ? METHOD_ARGS_NAME : METHOD_SELECT_NAME)))
                .addCode(toRecord && context.hasTable() && !context.hasJavaRecordReference() ? declare(CONTEXT_NAME, asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME)) : CodeBlock.empty())
                .addCode(declare(context.getOutputName(), context.getReturnType(), context.hasSourceName() || noRecordIterability))
                .addCode("\n")
                .addCode(declareDependencyClasses(methodName))
                .addCode(fillCode)
                .addCode("\n")
                .addCode(context.getReturnBlock());
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (getLocalObject() == null || getLocalObject().isExplicitlyNotGenerated()) {
            return List.of();
        }

        return List.of(generate(getLocalField()));
    }

    public abstract boolean mapsJavaRecord();

    /**
     * @return Code that declares any dependencies set for this method.
     */
    private CodeBlock declareDependencyClasses(String methodName) {
        var code = CodeBlock.builder();
        dependencyMap
                .getOrDefault(methodName, List.of())
                .stream()
                .distinct()
                .sorted()
                .forEach(dep -> code.add(dep.getDeclarationCode()));

        if (!code.isEmpty()) {
            code.add("\n");
        }

        return code.build();
    }

    /**
     * @return Code for setting the mapping data.
     */
    protected abstract CodeBlock iterateRecords(MapperContext context);
}
