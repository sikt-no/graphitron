/**
 * Renderers: typed interpreters, one per command kind, each a total function from a command record
 * to emitted output. Renderers hold the emit library and nothing else; every decision about what
 * to emit was already made by the producer that minted the command.
 *
 * <p>Bottom-right corner of the command/plan/render triangle. Import rules, build-enforced by
 * {@code PackageImportDirectionTest}: renderers may import {@code no.sikt.graphitron.command} and
 * the emit library ({@code no.sikt.graphitron.javapoet}); nothing here may import the model or
 * legacy core ({@code no.sikt.graphitron.rewrite}) or {@code no.sikt.graphitron.plan}. The first
 * renderer arrives with the projection command; until then the package carries only this contract.
 */
package no.sikt.graphitron.render;
