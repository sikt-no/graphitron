package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * DataLoader container kind + dispatch shape for a {@link SourceKey}-shaped field. Pairs with
 * {@link SourceKey} at the field-classifier site: one {@link SourceKey} per field, plus a
 * {@link LoaderRegistration} when the field is DataLoader-backed.
 *
 * <p>Separate from {@link SourceKey} because the same {@link SourceKey} shape can be loaded
 * into either a positional or mapped DataLoader container, and dispatched through either
 * {@code load} or {@code loadMany}: container choice is a per-field decision driven by the
 * source-shape declaration ({@code List<...>} positional vs. {@code Set<...>} mapped) or the
 * {@code loader.loadMany} contract on accessor-many fields, not by the source-row shape itself.
 * Mapped-source declarations ({@code Set<...>} on the {@code @sources}-typed parameter) collapse
 * onto {@code Container.MAPPED_SET}; catalog-FK and {@code List<...>}-source declarations
 * collapse onto {@code Container.POSITIONAL_LIST}.
 *
 * <p>The R75 rooted DML payload case has no {@link LoaderRegistration} — the DataFetcher
 * reads {@code env.getSource()} and uses {@link SourceKey} directly to extract source-row
 * instances. {@link LoaderRegistration} being a separate value (not a field on
 * {@link SourceKey}) is what makes that absence representable.
 *
 * <h2>Components</h2>
 *
 * <ul>
 *   <li>{@link #valueIsList()} — {@code false} when the loader returns one record per key
 *       ({@code load(key) -> Record}); {@code true} when it returns a list per key
 *       ({@code load(key) -> List<Record>}).</li>
 *   <li>{@link #container()} — {@link Container#POSITIONAL_LIST} drives {@code newDataLoader}
 *       (positional: {@code keys[i]} aligns with {@code values[i]});
 *       {@link Container#MAPPED_SET} drives {@code newMappedDataLoader} (the mapped variant
 *       returns a {@code Map<KeyType, V>}).</li>
 *   <li>{@link #dispatch()} — {@link Dispatch#LOAD_ONE} drives
 *       {@code loader.load(key, env)} (one key per fetch site, single value back);
 *       {@link Dispatch#LOAD_MANY} drives
 *       {@code loader.loadMany(keys, contexts)} (one fetch site, many keys, many values back).
 *       Container and dispatch are independent axes: {@code Container.MAPPED_SET} pairs with
 *       both {@link Dispatch#LOAD_ONE} (single-cardinality mapped-source declarations) and
 *       {@link Dispatch#LOAD_MANY} (accessor-many's loadMany contract).</li>
 * </ul>
 *
 * <p>The path-scoped tenant-qualified DataLoader name is computed at fetcher emit time from
 * {@code env.getExecutionStepInfo().getPath()}; it is not carried on this record because
 * path-scoping is a runtime fact, not a build-time one.
 */
public record LoaderRegistration(
    boolean valueIsList,
    Container container,
    Dispatch dispatch
) {

    public LoaderRegistration {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(dispatch, "dispatch");
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

    /** Per-fetch loader-dispatch shape. */
    public enum Dispatch {
        /**
         * One key per fetch site: emits {@code loader.load(key, env)}. The fetch site's
         * key-extraction supplies a single {@code key} local; the loader returns the per-key
         * value (one record, or one list of records when {@link #valueIsList()} is true).
         */
        LOAD_ONE,
        /**
         * Many keys per fetch site: emits
         * {@code loader.loadMany(keys, Collections.nCopies(keys.size(), env))}. The fetch
         * site's key-extraction supplies a {@code keys} list local; the loader returns one
         * record per element-PK key. Today only the accessor-many arm reaches this dispatch
         * (a class-backed parent's typed list-accessor fans out per-element).
         */
        LOAD_MANY
    }
}
