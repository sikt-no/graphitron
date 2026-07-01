package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.enums.MpaaRating;

/**
 * R261 site-E fixture: a consumer input bean whose member is a jOOQ enum ({@link MpaaRating},
 * constants {@code G, PG, PG_13, R, NC_17}). Bound to an SDL enum that carries a value name with no
 * matching Java constant, the generated {@code MpaaRating.valueOf((String) ...)} throws
 * {@code IllegalArgumentException} at runtime. The enum-constant parity check must reject this at
 * generation time with {@code WireCoercionError.EnumConstantDivergence}.
 */
public record TestWireEnumBean(MpaaRating rating) {}
