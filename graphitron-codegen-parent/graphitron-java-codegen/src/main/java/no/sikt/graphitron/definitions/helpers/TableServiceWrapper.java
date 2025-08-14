package no.sikt.graphitron.definitions.helpers;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import no.sikt.graphql.directives.GenerationDirectiveParam;

import static no.sikt.graphql.directives.GenerationDirective.TABLE_SERVICE;

/**
 * Class that contains extended information about a table service.
 */
public class TableServiceWrapper extends CodeReferenceWrapper {
    public <T extends NamedNode<T> & DirectivesContainer<T>> TableServiceWrapper(T field) {
        super(field, TABLE_SERVICE, GenerationDirectiveParam.SERVICE);
    }
}
