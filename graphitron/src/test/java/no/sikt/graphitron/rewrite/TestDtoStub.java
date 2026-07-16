package no.sikt.graphitron.rewrite;

/**
 * Minimal consumer-authored bean stub. Used in two places:
 * <ul>
 *   <li>{@code @source} Sources parameters: a non-{@link org.jooq.TableRecord} element type
 * is rejected at classification time.</li>
 *   <li>{@code @service} Arg parameters paired with an SDL input-object slot: detected as an
 * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean} target.
 *       A record shape ensures the bean is structurally populatable; under the strict
 *       instantiation policy, a plain class without a public no-arg constructor would fail-fast at
 *       generation time rather than silently producing a {@code ClassCastException} at runtime.</li>
 * </ul>
 */
public record TestDtoStub(String id) {}
