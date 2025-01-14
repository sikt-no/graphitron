package no.sikt.graphql.helpers.functions;

import no.sikt.graphql.helpers.transform.AbstractTransformer;

@FunctionalInterface
public interface TransformCall<A extends AbstractTransformer, V0, V1> {
    V1 transform(A transform, V0 input);
}
