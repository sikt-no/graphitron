package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.helpers.ServiceWrapper;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.recordTransformMethod;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.RECORD_TRANSFORMER;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.STRING;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

abstract public class AbstractMapperMethodGenerator<T extends GenerationField> extends AbstractMethodGenerator<T> {
    private final T localField;
    protected final boolean toRecord;

    public AbstractMapperMethodGenerator(T localField, ProcessedSchema processedSchema, boolean toRecord) {
        super(processedSchema.getRecordType(localField.getContainerTypeName()), processedSchema);

        this.localField = localField;
        this.toRecord = toRecord;
    }

    public T getLocalField() {
        return localField;
    }

    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, String inputName, TypeName inputType, TypeName returnType) {
        return getDefaultSpecBuilder(methodName, returnType)
                .addModifiers(Modifier.STATIC)
                .addParameter(inputType, uncapitalize(inputName))
                .addParameter(STRING.className, PATH_NAME)
                .addParameter(RECORD_TRANSFORMER.className, TRANSFORMER_NAME)
                .addCode(declare(PATH_HERE_NAME, addStringIfNotEmpty(PATH_NAME, "/")));
    }

    public MethodSpec.Builder getMapperSpecBuilder(T target) {
        var context = MapperContext.createContext(target, toRecord, mapsJavaRecord(), processedSchema);
        var methodName = recordTransformMethod(context.hasJavaRecordReference(), toRecord);

        var fillCode = iterateRecords(context); // Note, do before declaring dependencies.
        var type = processedSchema.getRecordType(target);
        var source = wrapListIf(getSource(type.asSourceClassName(toRecord), target), context.hasSourceName());
        var noRecordIterability = !context.hasSourceName() && target.isIterableWrapped();
        return getDefaultSpecBuilder(methodName, context.getInputVariableName(), source, wrapListIf(context.getReturnType(), noRecordIterability || context.hasRecordReference()))
                .addCode(declare(toRecord ? VARIABLE_ARGUMENTS : VARIABLE_SELECT, asMethodCall(TRANSFORMER_NAME, toRecord ? METHOD_ARGS_NAME : METHOD_SELECT_NAME)))
                .addCode(toRecord && context.hasTable() && !context.hasJavaRecordReference() ? declare(CONTEXT_NAME, asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME)) : empty())
                .addCode(context.hasSourceName() || noRecordIterability ? declareArrayList(context.getOutputName(), context.getReturnType()) : declareVariable(context.getOutputName(), context.getReturnType()))
                .addCode("\n")
                .addCode(declareDependencyClasses())
                .addCode(fillCode)
                .addCode("\n")
                .addCode(context.getReturnBlock());
    }

    private TypeName getSource(ClassName currentSource, GenerationField target) {
        if (currentSource != null) {
            return currentSource;
        }

        return ClassName.get(new ServiceWrapper(target, processedSchema).getMethod().getGenericReturnType());
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
     * @return Code that declares any dependencies set for this generator.
     */
    private CodeBlock declareDependencyClasses() {
        var code = CodeBlock.builder();
        dependencySet
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
