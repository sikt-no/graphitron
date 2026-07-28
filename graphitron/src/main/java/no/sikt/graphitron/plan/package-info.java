/**
 * Producers: the core steps that derive command relations from the classified model. The plan is
 * produced eagerly and completely before any rendering, so the emit decisions are assertable as
 * data.
 *
 * <p>Middle corner of the command/plan/render triangle. Import rules, build-enforced by
 * {@code PackageImportDirectionTest}: producers read the model (a transitional allowance that
 * narrows as the model grows a pure-data floor) and mint {@code no.sikt.graphitron.command}
 * records; nothing here may import the emit library ({@code no.sikt.graphitron.javapoet}) or
 * {@code no.sikt.graphitron.render}.
 */
package no.sikt.graphitron.plan;
