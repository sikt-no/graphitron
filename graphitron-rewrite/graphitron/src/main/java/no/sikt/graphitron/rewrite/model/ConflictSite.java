package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.TypeName;

/**
 * One site in a cross-site {@code contextArgument} type-conflict rejection: the {@link MethodRef}
 * coordinate where the name was referenced and the structural {@link TypeName} that site declared.
 *
 * <p>Captured at the classifier producing
 * {@link Rejection.AuthorError.TypeConflict}; consumed by the message renderer and by any
 * future LSP fix-it that wants to navigate to a declaring method. Carrying the typed pair as a
 * dedicated record (rather than a {@code Map.Entry<MethodRef, String>}) lets readers reason about
 * the two fields by name and keeps {@link TypeName} as the structural carrier the rest of the
 * slice already uses.
 */
public record ConflictSite(MethodRef site, TypeName declared) {}
