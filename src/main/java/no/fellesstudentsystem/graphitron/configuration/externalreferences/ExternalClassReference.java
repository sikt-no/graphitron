package no.fellesstudentsystem.graphitron.configuration.externalreferences;

public class ExternalClassReference implements ExternalReference {
    private final String name;
    private final Class<?> classReference;

    public ExternalClassReference(String name, Class<?> classReference) {
        this.name = name;
        this.classReference = classReference;
    }

    public String getName() {
        return name;
    }

    public Class<?> getClassReference() {
        return classReference;
    }
}
