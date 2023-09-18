package no.fellesstudentsystem.graphql.relay;

import graphql.relay.Connection;

import java.util.List;

/**
 * Interface for handling connections types according to the <a href="https://relay.dev/graphql/connections.htm">cursor connection specification</a>.
 */
public interface ExtendedConnection<T> extends Connection<T> {
    List<T> getNodes();

    Integer getTotalCount();
}
