package no.sikt.graphitron.generate;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;

import java.util.List;
import java.util.Set;

/**
 * Configuration interface for jOOQ introspection.
 * Extends Validator to reuse jOOQ package configuration.
 */
public interface Introspector extends Validator {
    /**
     * @return Output path for the LSP configuration JSON file
     */
    String getOutputFile();

    /**
     * @return External reference elements for code generation
     */
    List<? extends ExternalReference> getExternalReferences();

    /**
     * @return Package imports for resolving external references
     */
    Set<String> getExternalReferenceImports();
}