package no.sikt.graphitron.configuration.externalreferences;

public class ExternalMojoClassReference implements ExternalReference {
    private String name;
    private String fullyQualifiedClassName;

    public ExternalMojoClassReference() {}

    public ExternalMojoClassReference(String name, String fullyQualifiedClassName) {
        this.name = name;
        this.fullyQualifiedClassName = fullyQualifiedClassName;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Class<?> getClassReference() {
        try {
            return Class.forName(fullyQualifiedClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Could not find external class. ", e);
        }
    }
}
