package no.fellesstudentsystem.graphql.helpers.functions;

import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;

@FunctionalInterface
public interface TransformCall<V0, V1> {
    V1 transform(AbstractTransformer transform, V0 input);
}
