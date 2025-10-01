package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.AliasWrapper;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.generators.dependencies.Dependency;
import no.sikt.graphitron.generators.dependencies.ServiceDependency;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.*;

import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;

/**
 * An abstract generator that contains methods that are common between both DB-method generators and resolver generators.
 */
abstract public class AbstractSchemaMethodGenerator<T extends GenerationTarget, U extends GenerationTarget> implements MethodGenerator {
    protected final U source;
    protected final RecordObjectSpecification<?> sourceContainer;
    protected final ProcessedSchema processedSchema;
    protected Map<String, List<Dependency>> dependencyMap = new HashMap<>();

    public AbstractSchemaMethodGenerator(U source, ProcessedSchema processedSchema) {
        this.source = source;
        this.processedSchema = processedSchema;
        this.sourceContainer = (source instanceof GenerationField sourceField) ? processedSchema.getRecordType(sourceField.getContainerTypeName()) : null;
    }

    /**
     * @return The object or field that this generator is attempting to build methods for.
     */
    public U getSource() {
        return source;
    }

    /**
     * @return The object containing the field that this generator is attempting to build methods for.
     */
    public RecordObjectSpecification<?> getSourceContainer() {
        return sourceContainer;
    }

    protected JOOQMapping getSourceTable() {
        var sourceContainer = getSourceContainer();
        if (sourceContainer == null) {
            return null;
        }

        if (sourceContainer.hasTable()) {
            return sourceContainer.getTable();
        }

        return Optional
                .ofNullable(processedSchema.getPreviousTableObjectForObject(sourceContainer))
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

    protected ServiceDependency createServiceDependency(GenerationField target) {
        var dependency = new ServiceDependency(target.getExternalMethod().getClassName());
        dependencyMap.computeIfAbsent(target.getName(), (s) -> new ArrayList<>()).add(dependency);
        return dependency;
    }

    /**
     * @return Code that declares any service dependencies set for this generator.
     */
    protected CodeBlock declareAllServiceClasses(String targetName) {
        return declareAllServiceClasses(targetName, false);
    }

    protected CodeBlock declareAllServiceClasses(String targetName, boolean excludeCtx) {
        var code = CodeBlock.builder();
        dependencyMap
                .getOrDefault(targetName, List.of())
                .stream()
                .filter(dep -> dep instanceof ServiceDependency) // Inelegant solution, but it should work for now.
                .distinct()
                .sorted()
                .map(dep -> (ServiceDependency) dep)
                .forEach(dep -> code.add(dep.getDeclarationCode(excludeCtx)));
        return code.build();
    }

    protected CodeBlock declareAllServiceClassesInAliasSet(Set<AliasWrapper> aliasSet) {
        for (var alias: aliasSet) {
            if (alias.hasTableMethod()){
                createServiceDependency(alias.getReferenceObjectField());
            }
        }

        var codeBlocks = aliasSet.stream()
                .map(AliasWrapper::getReferenceObjectField)
                .filter(Objects::nonNull)
                .filter(field -> dependencyMap.containsKey(field.getName()))
                .flatMap(field -> dependencyMap.get(field.getName()).stream())
                .filter(dep -> dep instanceof ServiceDependency)
                .distinct()
                .map(dep -> ((ServiceDependency) dep).getDeclarationCode(true))
                .collect(CodeBlock.joining());

        return CodeBlock.builder().add(codeBlocks).build();
    }
}
