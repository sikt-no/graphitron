package no.sikt.graphitron.model.capture.code.fixtures;

/**
 * A bean whose members are partly its own and partly its superclass's. What an author can fill on
 * one of these is both, so a reading that stopped at the declared methods would offer half of it.
 */
public class BeanChild extends BeanBase {

    private int year;

    /** Its own. */
    public void setYear(int year) {
        this.year = year;
    }
}
