package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.generators.dependencies.Dependency;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;

/**
 * An abstract generator that contains methods that are common between both DB-method generators and resolver generators.
 */
abstract public class AbstractSchemaMethodGenerator<T extends GenerationTarget, U extends GenerationTarget> implements MethodGenerator {
    protected final U localObject;
    protected final ProcessedSchema processedSchema;
    protected Map<String, List<Dependency>> dependencyMap = new HashMap<>();

    public AbstractSchemaMethodGenerator(U localObject, ProcessedSchema processedSchema) {
        this.localObject = localObject;
        this.processedSchema = processedSchema;
    }

    /**
     * @return The object that this generator is attempting to build methods for.
     */
    public U getLocalObject() {
        return localObject;
    }

    protected JOOQMapping getLocalTable() {
        if (!(getLocalObject() instanceof RecordObjectSpecification<? extends GenerationField> localRecordObject)) {
            return null;
        }

        if (localRecordObject.hasTable()) {
            return localRecordObject.getTable();
        }
        return Optional
                .ofNullable(processedSchema.getPreviousTableObjectForObject(localRecordObject))
                .map(RecordObjectSpecification::getTable)
                .orElse(null);
    }

    /**
     * @param methodName The name of the method.
     * @param returnType The return type of the method, as a javapoet {@link TypeName}.
     * @return The default builder for this class' methods, with any common settings applied.
     */
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return MethodSpec
                .methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);
    }

    @Override
    public Map<String, List<Dependency>> getDependencyMap() {
        return dependencyMap;
    }

    /**
     * @return Get the javapoet TypeName for this field's type, and wrap it in a list ParameterizedTypeName if it is iterable.
     */
    protected TypeName iterableWrapType(GenerationField field) {
        return wrapListIf(inferFieldTypeName(field, false), field.isIterableWrapped());
    }

    /**
     * @return Get the javapoet TypeName for this field's type.
     */
    protected TypeName inferFieldTypeName(GenerationField field, boolean checkRecordReferences) {
        if (processedSchema.isRecordType(field)) {
            var type = processedSchema.getRecordType(field);
            if (!checkRecordReferences || !type.hasRecordReference()) {
                return type.getGraphClassName();
            }
            return type.getRecordClassName();
        }

        if (processedSchema.isEnum(field)) {
            return processedSchema.getEnum(field).getGraphClassName();
        }

        var typeName = field.getTypeName();

        var typeClass = field.getTypeClass();
        if (typeClass == null) {
            throw new IllegalStateException("Field \"" + field.getName() + "\" has a type \"" + typeName + "\" that can not be resolved.");
        }

        return typeClass;
    }

    /**
     * @return Any DataFetcher wiring this generator produces.
     */
    public List<WiringContainer> getDataFetcherWiring() {
        return List.of();
    }

    /**
     * @return Any TypeRespolver wiring this generator produces.
     */
    public List<WiringContainer> getTypeResolverWiring() {
        return List.of();
    }

    /**
     * @return The complete javapoet {@link MethodSpec} based on the provided target.
     */
    abstract public MethodSpec generate(T target);
}
