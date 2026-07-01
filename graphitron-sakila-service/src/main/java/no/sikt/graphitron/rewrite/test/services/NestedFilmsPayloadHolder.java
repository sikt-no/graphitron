package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * R370 compilation-tier fixture (producer #1): the nested-carrier counterpart of
 * {@link CreateFilmsPayload}. Where {@code CreateFilmsPayload} is a top-level record (binary name
 * has no {@code $}), this carrier is a <em>nested</em> record ({@code Payload} enclosed here), so
 * its binary name is {@code NestedFilmsPayloadHolder$Payload}.
 *
 * <p>The {@code films()} accessor returns {@code List<FilmRecord>}; the classifier auto-derives an
 * {@code AccessorCall}-keyed, list-cardinality {@code SourceKey} via
 * {@code FieldBuilder.deriveAccessorRecordParentSource} (producer #1), whose {@code AccessorRef}
 * carries the parent backing {@code ClassName}. That {@code ClassName} is consumed by
 * {@code GeneratorUtils.buildAccessorKeyMany} (and {@code buildAccessorKeySingle}) to cast
 * {@code env.getSource()} to the parent backing class. Before R370 the nested carrier cast as
 * {@code Outer$Nested} and failed {@code javac}; this fixture is the only compiling witness that
 * guards producer #1 independently of the polymorphic producer #2 witness
 * ({@link NestedOccupantCarrierService}).
 */
public final class NestedFilmsPayloadHolder {

    private NestedFilmsPayloadHolder() {}

    /** Nested record carrier exposing a typed {@code List<FilmRecord>} accessor. */
    public record Payload(List<FilmRecord> films) {}
}
