package no.sikt.graphitron.generate;

/**
 * Configuration interface for jOOQ introspection.
 * Extends Validator to reuse jOOQ package configuration,
 * including external references and imports.
 */
public interface Introspector extends Validator {
    /**
     * @return Output path for the LSP configuration JSON file
     */
    String getOutputFile();
}