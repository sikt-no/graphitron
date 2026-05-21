/**
 * Marker package. This module ships per-platform tree-sitter native libraries
 * under {@code lib/<os>-<arch>/} on the classpath and has no Java code of its
 * own. The package exists so {@code maven-source-plugin} and
 * {@code maven-javadoc-plugin} produce non-empty source + javadoc archives,
 * which Maven Central requires for every published release.
 *
 * <p>The library-loading logic lives in graphitron-lsp's
 * {@code BundledLibraryLookup}; this jar is a pure resource carrier.
 */
package no.sikt.graphitron.natives;
