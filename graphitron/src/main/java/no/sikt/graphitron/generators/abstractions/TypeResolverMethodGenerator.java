package no.sikt.graphitron.generators.abstractions;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.interfaces.TypeResolverTarget;
import no.sikt.graphitron.generators.wiring.WiringContainer;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declare;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asTypeResolverMethodName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_OBJECT;
import static no.sikt.graphitron.mappings.JavaPoetClassName.MAP;
import static no.sikt.graphitron.mappings.JavaPoetClassName.TYPE_RESOLVER;

/**
 * Abstract class for generating type resolvers.
 */
abstract public class TypeResolverMethodGenerator<T extends TypeResolverTarget> extends AbstractMethodGenerator<T> {
    protected final List<WiringContainer> typeResolverWiring = new ArrayList<>();

    public TypeResolverMethodGenerator(ProcessedSchema processedSchema) {
        super(null, processedSchema);
    }

    public MethodSpec.Builder getDefaultSpecBuilder(String methodName) {
        return getDefaultSpecBuilder(asTypeResolverMethodName(methodName), TYPE_RESOLVER.className)
                .addModifiers(Modifier.STATIC);
    }

    public CodeBlock wrapResolver(CodeBlock code) {
        return CodeBlock
                .builder()
                .beginControlFlow("return $N ->", VARIABLE_ENV)
                .add(declare(VARIABLE_OBJECT, CodeBlock.of("$N.getObject()", VARIABLE_ENV)))
                .beginControlFlow("if (!($N instanceof $T))", VARIABLE_OBJECT, MAP.className)
                .add(returnWrap("null"))
                .endControlFlow()
                .add(code)
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
                .build();
    }

    @Override
    public List<WiringContainer> getTypeResolverWiring() {
        return typeResolverWiring;
    }
}
