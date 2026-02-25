package no.sikt.graphql.relay;

import graphql.relay.DefaultConnection;
import graphql.relay.Edge;
import graphql.relay.PageInfo;

import java.util.List;

/**
 * Helper class for handling extended connection types based on the
 * <a href="https://relay.dev/graphql/connections.htm">cursor connection specification</a>.
 * The fields {@code nodes} and {@code totalCount} are added to the standard connection type. These fields are not part
 * of the official specification, but are commonly used in GraphQL APIs. They are optional in this implementation and
 * can be omitted if not needed.
 */
public class ConnectionImpl<T> extends DefaultConnection<T> {
    private final List<T> nodes;
    private final Integer totalCount;

    public ConnectionImpl(List<Edge<T>> edges, List<T> nodes, PageInfo pageInfo, Integer totalCount) {
        super(edges, pageInfo);
        this.nodes = (nodes != null) ? nodes : List.of();
        this.totalCount = totalCount;
    }

    public List<T> getNodes() {
        return nodes;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private List<Edge<T>> edges;
        private List<T> nodes = List.of();
        private PageInfo pageInfo;
        private Integer totalCount = 0;

        public Builder() {
        }

        public Builder<T> setEdges(List<Edge<T>> edges) {
            this.edges = edges;
            return this;
        }

        public Builder<T> setNodes(List<T> nodes) {
            this.nodes = (nodes != null) ? nodes : List.of();
            return this;
        }

        public Builder<T> setPageInfo(PageInfo pageInfo) {
            this.pageInfo = pageInfo;
            return this;
        }

        public Builder<T> setTotalCount(Integer totalCount) {
            this.totalCount = totalCount;
            return this;
        }

        public ConnectionImpl<T> build() {
            return new ConnectionImpl<>(edges, nodes, pageInfo, totalCount);
        }

    }
}
