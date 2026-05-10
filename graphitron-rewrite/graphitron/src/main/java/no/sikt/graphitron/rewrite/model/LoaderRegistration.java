package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * DataLoader identity + container kind for a {@link SourceKey}-shaped field. Pairs with
 * {@link SourceKey} at the field-classifier site: one {@link SourceKey} per field, plus a
 * {@link LoaderRegistration} when the field is DataLoader-backed.
 *
 * <p>Separate from {@link SourceKey} because the same {@link SourceKey} shape can be loaded
 * into either a positional or mapped DataLoader container — container choice is a per-field
 * decision driven by the source-shape declaration ({@code List<...>} positional vs.
 * {@code Set<...>} mapped) or the {@code loader.loadMany} contract on accessor-many fields,
 * not by the source-row shape itself. Today's {@code Mapped*Keyed} family in
 * {@link BatchKey} collapses onto {@code Container.MAPPED_SET}; the catalog-FK and
 * {@code List<...>}-source declarations collapse onto {@code Container.POSITIONAL_LIST}.
 *
 * <p>The R75 rooted DML payload case has no {@link LoaderRegistration} — the DataFetcher
 * reads {@code env.getSource()} and uses {@link SourceKey} directly to extract source-row
 * instances. {@link LoaderRegistration} being a separate value (not a field on
 * {@link SourceKey}) is what makes that absence representable.
 *
 * <h2>Components</h2>
 *
 * <ul>
 *   <li>{@link #loaderName()} — the path-scoped tenant-qualified loader name, computed at
 *       emit time the same way today's {@code GeneratorUtils.buildDataLoaderName} computes
 *       it. Stored as a string here rather than re-derived at every consumer.</li>
 *   <li>{@link #valueIsList()} — {@code false} when the loader returns one record per key
 *       ({@code load(key) -> Record}); {@code true} when it returns a list per key
 *       ({@code load(key) -> List<Record>}).</li>
 *   <li>{@link #container()} — {@link Container#POSITIONAL_LIST} drives {@code newDataLoader}
 *       (positional: {@code keys[i]} aligns with {@code values[i]});
 *       {@link Container#MAPPED_SET} drives {@code newMappedDataLoader} (the mapped variant
 *       returns a {@code Map<KeyType, V>}).</li>
 * </ul>
 */
public record LoaderRegistration(
    String loaderName,
    boolean valueIsList,
    Container container
) {

    public LoaderRegistration {
        Objects.requireNonNull(loaderName, "loaderName");
        Objects.requireNonNull(container, "container");
    }

    /** DataLoader factory shape. */
    public enum Container {
        /**
         * Positional: keys arrive as {@code List<KeyType>}, results return as
         * {@code List<V>} aligned by index. Drives
         * {@code DataLoaderFactory.newDataLoader(...)}.
         */
        POSITIONAL_LIST,
        /**
         * Mapped: keys arrive as {@code Set<KeyType>}, results return as
         * {@code Map<KeyType, V>}. Drives
         * {@code DataLoaderFactory.newMappedDataLoader(...)}.
         */
        MAPPED_SET
    }
}
