package no.fellesstudentsystem.graphitron.generators.exception;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.getRecordValidation;
import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.wrapSet;
import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.wrapStringMap;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asGetMethodName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

public class MutationExceptionStrategyConfigurationGenerator implements ClassGenerator<ObjectDefinition> {
    private static final String PAYLOAD_FOR_MUTATION_FIELD_NAME = "payloadForMutation";
    private static final String MUTATIONS_FOR_EXCEPTION_FIELD = "mutationsForException";
    private static final ParameterizedTypeName MUTATIONS_FOR_EXCEPTIONS_TYPE =
            ParameterizedTypeName.get(MAP.className, ParameterizedTypeName.get(CLASS.className,
                    WildcardTypeName.subtypeOf(THROWABLE.className)), wrapSet(STRING.className));
    private static final TypeName PAYLOAD_FOR_MUTATIONS_TYPE = wrapStringMap(PAYLOAD_CREATOR.className);
    private final ProcessedSchema processedSchema;

    public MutationExceptionStrategyConfigurationGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec("GeneratedMutationExceptionStrategyConfiguration", List.of())
                .addMethod(createConstructor(target))
                .build();
    }

    private MethodSpec createConstructor(ObjectDefinition target) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addCode(CodeBlock.builder()
                        .addStatement("$N = new $T<>()", MUTATIONS_FOR_EXCEPTION_FIELD, HASH_MAP.className)
                        .addStatement("$N = new $T<>()", PAYLOAD_FOR_MUTATION_FIELD_NAME, HASH_MAP.className)
                        .add(createConstructorContentForMutations(target))
                        .build())
                .build();
    }

    private CodeBlock createConstructorContentForMutations(ObjectDefinition mutationTypeDefinition) {
        var codeBuilder = CodeBlock.builder();

        mutationTypeDefinition.getFields().forEach(mutation -> {
            var ctx = new UpdateContext(mutation, processedSchema);
            if (ctx.getValidationErrorException().isPresent()) {
                String mutationName = mutation.getName();
                codeBuilder.addStatement("$N.computeIfAbsent($T.class, k -> new $T<>()).add($S)",
                                MUTATIONS_FOR_EXCEPTION_FIELD, VALIDATION_VIOLATION_EXCEPTION.className, HASH_SET.className, mutationName)
                        .add("$N.put($S, ", PAYLOAD_FOR_MUTATION_FIELD_NAME, mutationName)
                        .beginControlFlow("errors ->")
                        .addStatement("var payload = new $T()", processedSchema.getObject(mutation.getTypeName()).getGraphClassName());

                ctx.getAllErrors().forEach(errorField -> codeBuilder.addStatement(
                        "payload" + errorField.getMappingFromFieldName().asSetCall("($T<$T>) errors"),
                        LIST.className, ctx.getErrorTypeDefinition(errorField.getTypeName()).getGraphClassName()));

                codeBuilder.addStatement("return payload")
                        .endControlFlow(")");
            }
        });
        return codeBuilder.build();
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {

        if (getRecordValidation().getSchemaErrorType().isPresent()) {
            Optional.ofNullable(processedSchema.getMutationType())
                    .map(this::generate)
                    .ifPresent(spec -> writeToFile(spec, path, packagePath, getDefaultSaveDirectoryName()));
        }
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath) {
        writeToFile(generatedClass, path, packagePath, getDefaultSaveDirectoryName());
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath, String directoryOverride) {
        var file = JavaFile
                .builder(packagePath + "." + directoryOverride, generatedClass)
                .indent("    ")
                .build();
        try {
            file.writeTo(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return "exception";
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<MethodGenerator<? extends AbstractField>> generators) {
        return TypeSpec.classBuilder(className)
                .addSuperinterface(MUTATION_EXCEPTION_STRATEGY_CONFIGURATION.className)
                .addModifiers(Modifier.PUBLIC)
                .addField(FieldSpec.builder(MUTATIONS_FOR_EXCEPTIONS_TYPE, MUTATIONS_FOR_EXCEPTION_FIELD, Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(PAYLOAD_FOR_MUTATIONS_TYPE, PAYLOAD_FOR_MUTATION_FIELD_NAME, Modifier.PRIVATE, Modifier.FINAL).build())
                .addMethod(createGetMutationsForExceptionsMethod())
                .addMethod(createGetPayloadForMutationsMethod());
    }

    private static MethodSpec createGetMutationsForExceptionsMethod() {
        return MethodSpec
                .methodBuilder(asGetMethodName(MUTATIONS_FOR_EXCEPTION_FIELD))
                .returns(MUTATIONS_FOR_EXCEPTIONS_TYPE)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(OVERRIDE.className)
                .addStatement("return $N", MUTATIONS_FOR_EXCEPTION_FIELD)
                .build();
    }

    private static MethodSpec createGetPayloadForMutationsMethod() {
        return MethodSpec
                .methodBuilder(asGetMethodName(PAYLOAD_FOR_MUTATION_FIELD_NAME))
                .returns(PAYLOAD_FOR_MUTATIONS_TYPE)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(OVERRIDE.className)
                .addStatement("return $N", PAYLOAD_FOR_MUTATION_FIELD_NAME)
                .build();
    }
}
