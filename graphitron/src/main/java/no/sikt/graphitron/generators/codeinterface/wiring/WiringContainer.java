package no.sikt.graphitron.generators.codeinterface.wiring;

import com.palantir.javapoet.ClassName;

import java.util.List;

public record WiringContainer(String methodName, String schemaType, String schemaField, boolean isFetcher) {
    public WiringContainer(String methodName, String schemaType, String schemaField) {
        this(methodName, schemaType, schemaField, true);
    }

    public ClassWiringContainer asClassWiring(ClassName className) {
        return new ClassWiringContainer(this, className);
    }

    public static List<ClassWiringContainer> asClassWiring(List<WiringContainer> containers, ClassName className) {
        return containers.stream().map(it -> it.asClassWiring(className)).toList();
    }
}
