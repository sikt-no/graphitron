package no.sikt.graphitron.configuration.externalreferences;

public record ExternalClassReference(String name, Class<?> classReference) implements ExternalReference { }
