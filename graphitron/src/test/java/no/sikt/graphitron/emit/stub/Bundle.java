package no.sikt.graphitron.emit.stub;

import java.util.List;

/** A record holding a list of records, and a member a grouping's field fills. */
public record Bundle(String name, Integer capacity, List<Draft> films) {}
