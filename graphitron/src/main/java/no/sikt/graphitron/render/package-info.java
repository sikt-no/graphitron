/**
 * Renderers: typed interpreters, one per command kind, each a total function from a command record
 * to emitted output. Renderers hold the emit library and nothing else; every decision about what
 * to emit was already made by the producer that minted the command.
 *
 * <p>Bottom-right corner of the command/plan/render triangle. Import rules, build-enforced by
 * {@code PackageImportDirectionTest}: renderers may import {@code no.sikt.graphitron.command} and
 * the emit library ({@code no.sikt.graphitron.javapoet}); from the legacy core only the borrowed
 * ref dial the commands themselves ride, and never {@code no.sikt.graphitron.plan}. Beside the
 * renderers sit the shared emission fragments (joins, routine calls, lookup rows) that migrated
 * and unmigrated hosts read through one derivation.
 */
package no.sikt.graphitron.render;
