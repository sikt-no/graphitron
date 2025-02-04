package no.sikt.graphitron.generators.datafetchers.wiring;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.RUNTIME_WIRING;
import static no.sikt.graphitron.mappings.JavaPoetClassName.TYPE_RUNTIME_WIRING;

/**
 * This class generates code for the RuntimeWiring fetching method.
 */
public class WiringMethodGenerator extends DataFetcherMethodGenerator<ObjectField> {
    public static final String METHOD_NAME = "getRuntimeWiring";
    private final static String VAR_WIRING = "wiring";
    private final List<ClassGenerator<? extends GenerationTarget>> generators;

    public WiringMethodGenerator(List<ClassGenerator<? extends GenerationTarget>> generators, ProcessedSchema schema) {
        super(null, schema);
        this.generators = generators;
    }

    private List<ClassWiringContainer> extractDataFetchers() {
        return generators
                .stream()
                .filter(it -> it instanceof DataFetcherClassGenerator)
                .flatMap(it -> ((DataFetcherClassGenerator<?>) it).getGeneratedDataFetchers().stream())
                .collect(Collectors.toList());
    }

    private List<ClassWiringContainer> extractTypeResolvers() {
        return generators
                .stream()
                .filter(it -> it instanceof DataFetcherClassGenerator)
                .flatMap(it -> ((DataFetcherClassGenerator<?>) it).getGeneratedTypeResolvers().stream())
                .collect(Collectors.toList());
    }

    @Override
    public MethodSpec generate(ObjectField dummy) {
        var fetchersByType = extractDataFetchers().stream().collect(Collectors.groupingBy(it -> it.getWiring().getSchemaType()));

        var code = CodeBlock
                .builder()
                .add(declare(VAR_WIRING, asMethodCall(RUNTIME_WIRING.className, "newRuntimeWiring")));
        for (var df : fetchersByType.entrySet()) {
            var fetchers = new ArrayList<CodeBlock>();
            for (var wiringContainer : df.getValue()) {
                var wiring = wiringContainer.getWiring();
                fetchers.add(CodeBlock.of(".dataFetcher($S, $L)", wiring.getSchemaField(), asMethodCall(wiringContainer.getContainerClass(), wiring.getMethodName())));
            }
            code.add(builderBlock(df.getKey(), indentIfMultiline(fetchers.stream().collect(CodeBlock.joining("\n")))));
        }

        var containers = extractTypeResolvers();
        for (var container : containers) {
            var wiring = container.getWiring();
            code.add(builderBlock(wiring.getSchemaType(), CodeBlock.of(".typeResolver($L)", asMethodCall(container.getContainerClass(), wiring.getMethodName()))));
        }
        code.add(returnWrap(CodeBlock.of("$N.build()", VAR_WIRING)));
        return getDefaultSpecBuilder(METHOD_NAME, RUNTIME_WIRING.className)
                .addCode(code.build())
                .build();
    }

    private CodeBlock newTypeWiring(String type) {
        return CodeBlock.of("$T.newTypeWiring($S)", TYPE_RUNTIME_WIRING.className, type);
    }

    private CodeBlock builderBlock(String schemaType, CodeBlock content) {
        return CodeBlock
                .builder()
                .add("$N.type($L", VAR_WIRING, indentIfMultiline(CodeBlock.of("$L$L", newTypeWiring(schemaType), content)))
                .addStatement(")")
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return List.of(generate(null));
    }

    @Override
    public boolean generatesAll() {
        return true;
    }
}
