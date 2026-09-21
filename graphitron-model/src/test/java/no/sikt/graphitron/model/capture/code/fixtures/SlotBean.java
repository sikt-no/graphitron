package no.sikt.graphitron.model.capture.code.fixtures;

import java.util.List;

/** A class that is not a record, which offers what its getters name. */
public final class SlotBean {

    public String getTitle() {
        return "";
    }

    /** A second spelling of one property, which is two rows for one slot name. */
    public String isTitle() {
        return "";
    }

    /** The other prefix, over a type a container wraps. */
    public List<String> getTags() {
        return List.of();
    }

    /** No prefix at all, so no property and no slot. */
    public String title() {
        return "";
    }

    /** A getter shape that takes an argument, which the rule refuses. */
    public String getTitled(String suffix) {
        return suffix;
    }

    /** get with nothing after it is not a property either. */
    public String get() {
        return "";
    }
}
