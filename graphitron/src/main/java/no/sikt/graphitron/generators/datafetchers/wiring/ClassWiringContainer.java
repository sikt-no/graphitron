package no.sikt.graphitron.generators.datafetchers.wiring;

import com.palantir.javapoet.ClassName;

public class ClassWiringContainer {
    private final WiringContainer wiring;
    private final ClassName containerClass;

    public ClassWiringContainer(WiringContainer wiring, ClassName containerClass) {
        this.wiring = wiring;
        this.containerClass = containerClass;
    }

    public WiringContainer getWiring() {
        return wiring;
    }

    public ClassName getContainerClass() {
        return containerClass;
    }
}
