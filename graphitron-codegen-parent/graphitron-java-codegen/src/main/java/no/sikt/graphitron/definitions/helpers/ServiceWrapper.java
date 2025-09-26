package no.sikt.graphitron.definitions.helpers;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import no.sikt.graphql.directives.GenerationDirectiveParam;

import static no.sikt.graphql.directives.GenerationDirective.SERVICE;
import static no.sikt.graphql.directives.GenerationDirective.TABLE_METHOD;

/**
 * Class that contains extended information about a service.
 */
public class ServiceWrapper extends CodeReferenceWrapper {
    public <T extends NamedNode<T> & DirectivesContainer<T>> ServiceWrapper(T field) {
        super(field, field.hasDirective(SERVICE.getName()) ? SERVICE : TABLE_METHOD,
                field.hasDirective(SERVICE.getName()) ? GenerationDirectiveParam.SERVICE : GenerationDirectiveParam.TABLE_METHOD_REFERENCE);
    }
}
