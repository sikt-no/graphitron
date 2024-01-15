package no.fellesstudentsystem.graphitron.configuration.externalreferences;

public class ExternalClassReference {
    private String name;
    private String fullyQualifiedClassName;

    public ExternalClassReference() {}

    public ExternalClassReference(String name, String fullyQualifiedClassName) {
        this.name = name;
        this.fullyQualifiedClassName = fullyQualifiedClassName;
    }

    public String getName() {
        return name;
    }

    public String getFullyQualifiedClassName() {
        return fullyQualifiedClassName;
    }
}
