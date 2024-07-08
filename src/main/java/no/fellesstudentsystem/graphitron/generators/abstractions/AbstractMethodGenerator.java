package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapListIf;

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

        var typeClass = field.getTypeClass();
        if (typeClass == null) {
            throw new IllegalStateException("Field \"" + field.getName() + "\" has a type \"" + field.getTypeName() + "\" that can not be resolved.");
        }

        return typeClass;
    }
}
