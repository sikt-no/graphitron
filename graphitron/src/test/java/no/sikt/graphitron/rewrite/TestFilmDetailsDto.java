package no.sikt.graphitron.rewrite;

/**
 * Reflection backing for the {@code FilmDetails @record { title: String }} fixture
 * payload used by the arg-mapping rows in {@code GraphitronSchemaBuilderTest}. Those rows
 * historically paired {@code String}-returning stub methods with the unbacked payload to test
 * argument mapping in isolation; the dangling-type-reference soundness pass
 * ({@code GraphitronSchemaBuilder.rejectDanglingTypeReferences}) now rejects that shape (the
 * payload type would be dropped while the field still emitted a typeRef to it), so the stubs
 * return this record and {@code FilmDetails} grounds through the ordinary reflection
 * binding.
 */
public record TestFilmDetailsDto(String title) {}
