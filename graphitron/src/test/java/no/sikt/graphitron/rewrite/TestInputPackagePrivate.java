package no.sikt.graphitron.rewrite;

/**
 * Fixture: a package-private record. Used by the classifier test that pins the
 * non-public-bean rejection — generated fetchers live in a different package and cannot reach
 * package-private bean classes, so a silent acceptance here would produce code that fails at
 * javac rather than at generation time.
 */
record TestInputPackagePrivate(String title) {}
