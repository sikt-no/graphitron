package no.sikt.graphitron.generators.codeinterface.wiring;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphitron.generators.dependencies.Dependency;
import no.sikt.graphitron.generators.resolvers.datafetchers.typeresolvers.TypeResolverClassGenerator;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_HANDLER_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

/**
 * This class generates code for the RuntimeWiring fetching method.
 */
public class WiringMethodGenerator implements MethodGenerator {
    public static final String METHOD_NAME = "getRuntimeWiring";
    private final static String VAR_WIRING = "wiring";
    private final List<ClassGenerator> generators;
    private final boolean includeNode;

    public WiringMethodGenerator(List<ClassGenerator> generators, boolean includeNode) {
        this.generators = generators;
        this.includeNode = includeNode;
    }

    private List<ClassWiringContainer> extractDataFetchers() {
        return generators
                .stream()
                .filter(it -> it instanceof DataFetcherClassGenerator)
                .flatMap(it -> ((DataFetcherClassGenerator<?>) it).getGeneratedDataFetchers().stream())
                .toList();
    }

    private List<ClassWiringContainer> extractTypeResolvers() {
        return generators
                .stream()
                .filter(it -> it instanceof TypeResolverClassGenerator)
                .flatMap(it -> ((TypeResolverClassGenerator) it).getGeneratedTypeResolvers().stream())
                .toList();
    }

    private Map<String, List<ClassWiringContainer>> getWiringByType() {
        return Stream
                .concat(extractDataFetchers().stream(), extractTypeResolvers().stream())
                .collect(Collectors.groupingBy(it -> it.wiring().schemaType(), LinkedHashMap::new, Collectors.toList()));
    }

    public MethodSpec generate() {
        var wiringByType = getWiringByType();

        var code = CodeBlock
                .builder()
                .add(declare(VAR_WIRING, asMethodCall(RUNTIME_WIRING.className, "newRuntimeWiring")));
        wiringByType.forEach((k, v) ->
                code
                        .add("$N.type(", VAR_WIRING)
                        .add(
                                indentIfMultiline(
                                        join(
                                                newTypeWiring(k),
                                                indentIfMultiline(v.stream().map(ClassWiringContainer::toCode).collect(CodeBlock.joining("\n")))
                                        )
                                )
                        )
                        .addStatement(")")  // This is separated because it affects indent.
        );

        code.add(returnWrap(CodeBlock.of("$N.build()", VAR_WIRING)));
        var spec = MethodSpec
                .methodBuilder(METHOD_NAME)
                .addModifiers(Modifier.PUBLIC)
                .returns(RUNTIME_WIRING.className)
                .addModifiers(Modifier.STATIC);
        if (includeNode) {
            spec.addParameter(NODE_ID_HANDLER.className, NODE_ID_HANDLER_NAME);
        }
        return spec
                .addCode(code.build())
                .build();
    }

    private CodeBlock newTypeWiring(String type) {
        return CodeBlock.of("$T.newTypeWiring($S)", TYPE_RUNTIME_WIRING.className, type);
    }

    @Override
    public List<MethodSpec> generateAll() {
        return List.of(generate());
    }

    @Override
    public Set<Dependency> getDependencySet() {
        return Set.of();
    }
}
