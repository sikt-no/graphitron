package no.sikt.graphitron.emit;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A root read the frame runs: its frame and the connection the store states for it, none in a graph
 * with no tenant column, where it runs on the request's own. Its fetcher is the frame around its
 * launcher.
 */
public record RootFrame(FrameQuery.Row frame, Optional<ConnectionQuery.Row> connection) {
    public RootFrame {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(connection, "connection");
    }

    /**
     * The root reads of a run that run in the frame, by parent type and field, and the Java type the
     * graph's tenant columns agree on, which a routed key is built under. {@code rendered} collects
     * the fields whose frame a fetcher asked for, so the run can tell a frame nothing rendered.
     */
    public record Rows(Map<String, RootFrame> byField, Optional<String> tenantType, Set<String> rendered) {
        public Rows {
            byField = Map.copyOf(byField);
            Objects.requireNonNull(tenantType, "tenantType");
            Objects.requireNonNull(rendered, "rendered");
        }

        /** No root read in the frame: a run with no store. */
        public static Rows empty() {
            return new Rows(Map.of(), Optional.empty(), new HashSet<>());
        }

        /**
         * The frame of each root {@code roots} states whole, with the connection the store states for
         * it: routed by the tenant its arguments name, the key with an empty tenant part for a read of
         * global tables, none in a graph with no tenant column. {@code roots} is the run's one
         * {@link RootQuery#read} answer, the one the launchers are planned from. A root read answers
         * its rows or fails, so a frame stating an outcome to catch into is drift.
         */
        public static Rows framed(Map<String, RootQuery.Result> roots, List<FrameQuery.Row> frames,
                                  Map<String, ConnectionQuery.Row> connections, Optional<String> tenantType) {
            var byCoordinate = new LinkedHashMap<String, FrameQuery.Row>();
            frames.forEach(f -> byCoordinate.put(f.coordinate(), f));
            var out = new LinkedHashMap<String, RootFrame>();
            for (var root : roots.values()) {
                if (!(root instanceof RootQuery.Emitted emitted)) {
                    continue;
                }
                var frame = byCoordinate.get(emitted.coordinate());
                if (frame == null) {
                    throw new IllegalStateException("Graphitron generator bug (root frame): the store states the root "
                        + emitted.coordinate() + " whole and states no frame for it; the queries have drifted");
                }
                if (frame.outcome().isPresent()) {
                    throw new IllegalStateException("Graphitron generator bug (root frame): the root read "
                        + emitted.coordinate() + " states an outcome to catch into, which a read of rows has none of");
                }
                out.put(emitted.parentType() + "." + emitted.fieldName(),
                    new RootFrame(frame, Optional.ofNullable(connections.get(emitted.coordinate()))));
            }
            return new Rows(out, tenantType, new HashSet<>());
        }

        /**
         * The frame the root read at this field runs in, where it runs in one, asked for by the
         * fetcher that renders it.
         */
        public Optional<RootFrame> frameFor(String parentType, String fieldName) {
            var key = parentType + "." + fieldName;
            var frame = Optional.ofNullable(byField.get(key));
            frame.ifPresent(f -> rendered.add(key));
            return frame;
        }

        /** The fields whose frame no fetcher asked for. */
        public List<String> unrendered() {
            return byField.keySet().stream().filter(key -> !rendered.contains(key)).sorted().toList();
        }
    }
}
