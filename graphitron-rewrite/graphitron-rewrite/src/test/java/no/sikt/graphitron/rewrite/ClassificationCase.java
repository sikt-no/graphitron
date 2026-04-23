package no.sikt.graphitron.rewrite;

import java.util.Set;

/**
 * Implemented by every classification enum constant in
 * {@link GraphitronSchemaBuilderTest}. The {@link #variants()} set declares which
 * sealed leaves of {@link no.sikt.graphitron.rewrite.model.GraphitronField} and
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType} a test case demonstrates
 * classification for. The variant-coverage meta-test aggregates these sets across all
 * cases and requires full coverage (minus a documented allowlist).
 *
 * <p>A case usually covers one variant. Cases that classify a whole type tree (e.g. a
 * {@code TableInputType} case whose assertion lambda also verifies {@code InputField}
 * children) may return multiple.
 */
public interface ClassificationCase {
    /** The sealed leaves this case is the primary classification coverage for. */
    Set<Class<?>> variants();
}
