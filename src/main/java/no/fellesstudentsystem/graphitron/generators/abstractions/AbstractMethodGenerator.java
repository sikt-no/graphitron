package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;

/**
 * An abstract generator that contains methods that are common between both DB-method generators and resolver generators.
 */
abstract public class AbstractMethodGenerator<T extends GenerationTarget> implements MethodGenerator<T> {
    protected final RecordObjectSpecification<? extends GenerationField> localObject;
    protected final ProcessedSchema processedSchema;
    protected Set<Dependency> dependencySet = new HashSet<>();

    public AbstractMethodGenerator(RecordObjectSpecification<? extends GenerationField> localObject, ProcessedSchema processedSchema) {
        this.localObject = localObject;
        this.processedSchema = processedSchema;
    }

    /**
     * @return The object that this generator is attempting to build methods for.
     */
    public RecordObjectSpecification<? extends GenerationField> getLocalObject() {
        return localObject;
    }

    protected JOOQMapping getLocalTable() {
        var localObject = getLocalObject();
        if (localObject.hasTable()) {
            return localObject.getTable();
        }
        return Optional
                .ofNullable(processedSchema.getPreviousTableObjectForObject(localObject))
                .map(RecordObjectSpecification::getTable)
                .orElse(null);
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return MethodSpec
                .methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);
    }

    /**
     * @return Set of dependencies found during generation up to this point.
     */
    @Override
    public Set<Dependency> getDependencySet() {
        return dependencySet;
    }

    /**
     * @return Get the javapoet TypeName for this field's type, and wrap it in a list ParameterizedTypeName if it is iterable.
     */
    protected TypeName iterableWrap(GenerationField field) {
        var typeClass = processedSchema.isRecordType(field) ? processedSchema.getRecordType(field).getGraphClassName() : field.getTypeClass();
        if (typeClass == null && processedSchema.isEnum(field)) {
            typeClass = processedSchema.getEnum(field).getGraphClassName();
        }

        // TODO: Throw error if typeClass is null, return types will fail with nullpointer after this.

        return wrapListIf(typeClass, field.isIterableWrapped());
    }

    protected void registerQueryDependency(String fieldType) {
        dependencySet.add(new QueryDependency(asQueryClass(fieldType), SAVE_DIRECTORY_NAME));
    }

    protected void registerQueryDependencies(MapperContext mapperContext) {
        registerQueryDependencies(mapperContext, new HashSet<>(), 0);
    }

    protected void registerQueryDependencies(MapperContext mapperContext, HashSet<String> seenTypes, int recursion) {
        recursionCheck(recursion);
        if (!mapperContext.targetIsType() || seenTypes.contains(mapperContext.getTargetType().getName())) {
            return;
        }

        seenTypes.add(mapperContext.getTargetType().getName());

        mapperContext
                .getTargetType()
                .getFields()
                .stream()
                .filter(it -> !it.isExplicitlyNotGenerated())
                .forEach(innerField -> {
                    var innerContext = mapperContext.iterateContext(innerField);
                    if (
                            !innerContext.shouldUseException()
                                    && !innerContext.getPreviousContext().hasRecordReference()
                                    && innerContext.targetIsType()
                                    && !innerContext.shouldUseStandardRecordFetch()
                                    && innerContext.hasRecordReference()
                    ) {
                        registerQueryDependency(innerField.getTypeName());
                    } else {
                        registerQueryDependencies(innerContext, seenTypes, recursion + 1);
                    }
                });
    }
}
