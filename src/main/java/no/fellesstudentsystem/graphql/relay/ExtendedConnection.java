package no.fellesstudentsystem.graphql.relay;

import graphql.relay.Connection;

import java.util.List;

public interface ExtendedConnection<T> extends Connection<T> {

    List<T> getNodes();

    Integer getTotalCount();
}
