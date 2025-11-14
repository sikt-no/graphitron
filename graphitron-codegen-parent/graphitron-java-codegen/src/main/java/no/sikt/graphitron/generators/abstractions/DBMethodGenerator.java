package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.contextFieldPrefix;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.mappings.JavaPoetClassName.DSL_CONTEXT;
import static no.sikt.graphitron.mappings.JavaPoetClassName.NODE_ID_STRATEGY;

/**
 * Generic select query generation functionality is contained within this class.
 * @param <T> Field type that this generator operates on.
 */
abstract public class DBMethodGenerator<T extends ObjectField> extends AbstractSchemaMethodGenerator<T, ObjectDefinition> {

    public DBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return super
                .getDefaultSpecBuilder(methodName, returnType)
                .addModifiers(Modifier.STATIC)
                .addParameter(DSL_CONTEXT.className, VariableNames.VAR_CONTEXT)
                .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY);
    }

    /**
     * @return Get the javapoet TypeName for this field's type, and wrap it in a list ParameterizedTypeName if it is iterable.
     */
    @Override
    protected TypeName iterableWrapType(GenerationField field) {
        return wrapListIf(inferFieldTypeName(field, true), field.isIterableWrapped());
    }

    protected List<ParameterSpec> getContextParameters(InputParser parser) {
        return parser
                .getContextInputs()
                .entrySet()
                .stream()
                .map((it) -> ParameterSpec.of(it.getValue(), contextFieldPrefix(it.getKey())))
                .toList();
    }

    protected List<ParameterSpec> getMethodAndContextParameters(InputParser parser) {
        return Stream.concat(
                getMethodParameters(parser).stream(),
                getContextParameters(parser).stream()
        ).toList();
    }

    private List<ParameterSpec> getMethodParameters(InputParser parser) {
        return getMethodParameters(parser.getMethodInputs());
    }

    protected List<ParameterSpec> getMethodParametersWithOrderField(InputParser parser) {
        return getMethodParameters(parser.getMethodInputsWithOrderField());
    }

    private List<ParameterSpec> getMethodParameters(Map<String, InputField> inputs) {
        return inputs
                .entrySet()
                .stream()
                .map((it) -> ParameterSpec.of(iterableWrapType(it.getValue()), inputPrefix(it.getKey())))
                .toList();
    }
}
