package no.sikt.graphitron.definitions.helpers;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import no.sikt.graphql.directives.GenerationDirectiveParam;

import static no.sikt.graphql.directives.GenerationDirective.SERVICE;

/**
 * Class that contains extended information about a service.
 */
public class ServiceWrapper extends CodeReferenceWrapper {
    public <T extends NamedNode<T> & DirectivesContainer<T>> ServiceWrapper(T field) {
        super(field, SERVICE, GenerationDirectiveParam.SERVICE);
    }
}
