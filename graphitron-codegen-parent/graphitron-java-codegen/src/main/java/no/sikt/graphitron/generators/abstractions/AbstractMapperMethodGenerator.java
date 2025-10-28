package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.generators.dependencies.Dependency;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.addStringIfNotEmpty;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.asMethodCall;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.recordTransformMethod;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapArrayList;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.listedOutputPrefix;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.outputPrefix;
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
        return getDefaultSpecBuilder(methodName, returnType)
                .addModifiers(Modifier.STATIC)
                .addParameter(inputType, uncapitalize(inputName))
                .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy() && !methodName.equals(METHOD_VALIDATE_NAME), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY)
                .addParameter(STRING.className, VAR_PATH_NAME)
                .addParameter(RECORD_TRANSFORMER.className, VAR_TRANSFORMER)
                .declare(VAR_PATH_HERE, addStringIfNotEmpty(VAR_PATH_NAME, "/"));
    }

    public MethodSpec.Builder getMapperSpecBuilder(GenerationField target) {
        var context = MapperContext.createContext(target, toRecord, mapsJavaRecord(), processedSchema);
        var methodName = recordTransformMethod(context.hasJavaRecordReference(), toRecord);

        var fillCode = iterateRecords(context); // Note, do before declaring dependencies.
        var type = processedSchema.getRecordType(target);
        var currentSource = type.asSourceClassName(toRecord);
        var source = wrapListIf(currentSource != null ? currentSource : target.getExternalMethod().getGenericReturnType(), context.hasSourceName());
        var noRecordIterability = !context.hasSourceName() && target.isIterableWrapped();
        var hasIterable = context.hasSourceName() || noRecordIterability;
        return getDefaultSpecBuilder(methodName, context.getInputVariableName(), source, wrapListIf(context.getReturnType(), noRecordIterability || context.hasRecordReference()))
                .declare(toRecord ? VAR_ARGS : VAR_SELECT, asMethodCall(VAR_TRANSFORMER, toRecord ? METHOD_ARGS_NAME : METHOD_SELECT_NAME))
                .declareIf(toRecord && context.hasTable() && !context.hasJavaRecordReference(), VAR_CONTEXT, asMethodCall(VAR_TRANSFORMER, METHOD_CONTEXT_NAME))
                .declareNewIf(hasIterable, listedOutputPrefix(context.getOutputName()), wrapArrayList(context.getReturnType()))
                .declareNewIf(!hasIterable, outputPrefix(context.getOutputName()), context.getReturnType())
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
        var dependencies = dependencyMap
                .getOrDefault(methodName, List.of())
                .stream()
                .distinct()
                .sorted()
                .map(Dependency::getDeclarationCode)
                .toList();

        var code = CodeBlock.builder();
        return code.addAll(dependencies).addIf(!code.isEmpty(), "\n").build();
    }

    /**
     * @return Code for setting the mapping data.
     */
    protected abstract CodeBlock iterateRecords(MapperContext context);
}
