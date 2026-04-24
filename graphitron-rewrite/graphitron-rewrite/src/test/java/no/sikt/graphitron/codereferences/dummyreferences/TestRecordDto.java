package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * A Java {@code record} class used to verify that the builder classifies
 * {@code @record(record: {className: "...TestRecordDto"})} as
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.JavaRecordType}.
 */
public record TestRecordDto(String name, int value) {}
