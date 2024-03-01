package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.RecordObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.getNodeQueryCallBlock;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.PATH_HERE_NAME;
import static no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;

/**
 * An abstract generator that contains methods that are common between both DB-method generators and resolver generators.
 */
abstract public class AbstractMethodGenerator<T extends GenerationTarget> implements MethodGenerator<T> {
    protected final ObjectDefinition localObject;
    protected final ProcessedSchema processedSchema;
    protected Set<Dependency> dependencySet = new HashSet<>();

    public AbstractMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        this.localObject = localObject;
        this.processedSchema = processedSchema;
    }

    /**
     * @return The object that this generator is attempting to build methods for.
     */
    public ObjectDefinition getLocalObject() {
        return localObject;
    }

    protected JOOQMapping getLocalTable() {
        var localObject = getLocalObject();
        if (localObject.hasTable()) {
            return localObject.getTable();
        }
        return Optional
                .ofNullable(processedSchema.getPreviousTableObjectForObject(localObject))
                .map(RecordObjectDefinition::getTable)
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
    protected TypeName inputIterableWrap(InputField field) {
        var typeClass = processedSchema.isInputType(field) ? processedSchema.getInputType(field).getGraphClassName() : field.getTypeClass();
        if (typeClass == null && processedSchema.isEnum(field)) {
            typeClass = processedSchema.getEnum(field).getGraphClassName();
        }
        return wrapListIf(typeClass, field.isIterableWrapped());
    }

    /**
     * @return Get the javapoet TypeName for this field's type, and wrap it in a list ParameterizedTypeName if it is iterable.
     */
    protected TypeName objectIterableWrap(ObjectField field) {
        var typeClass = processedSchema.isObject(field) ? processedSchema.getObject(field).getGraphClassName() : field.getTypeClass();
        if (typeClass == null && processedSchema.isEnum(field)) {
            typeClass = processedSchema.getEnum(field).getGraphClassName();
        }

        // TODO: Throw error if typeClass is null, return types will fail with nullpointer after this.

        return wrapListIf(typeClass, field.isIterableWrapped());
    }

    @NotNull
    protected CodeBlock createIdFetch(GenerationField field, String varName, String path, boolean atResolver) {
        dependencySet.add(new QueryDependency(asQueryClass(field.getTypeName()), SAVE_DIRECTORY_NAME));
        return getNodeQueryCallBlock(field, varName, !atResolver ? CodeBlock.of("$N + $S", PATH_HERE_NAME, path) : CodeBlock.of("$S", path), false, field.isIterableWrapped(), atResolver);
    }
}
