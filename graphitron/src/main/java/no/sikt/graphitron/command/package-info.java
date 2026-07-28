/**
 * Command records and their sealed arms: pure data describing what the core has decided to emit,
 * with no knowledge of how emission happens.
 *
 * <p>This package is the data floor of the command/plan/render triangle. Its import rules are
 * mechanical and build-enforced (see {@code PackageImportDirectionTest}): nothing from the emit
 * library ({@code no.sikt.graphitron.javapoet}), nothing from {@code no.sikt.graphitron.plan} or
 * {@code no.sikt.graphitron.render}, and from the legacy model only a named, enumerated allowlist
 * of ref types ({@code TableRef}, {@code ColumnRef}, {@code MethodRef}, {@code JoinStep},
 * {@code On}, {@code CallParam}, {@code CallSiteExtraction}) plus graphql-java's
 * {@code FieldCoordinates}. The allowlist is the migration dial: it empties as each entry moves to
 * a shared pure-data floor, and the check enforces the list rather than a blanket ban so the model's
 * ref vocabulary is borrowed, never copied.
 */
package no.sikt.graphitron.command;
