package no.sikt.graphitron.emit.stub;

/** An object an instance method runs on, made with nothing. */
public final class PlainHolder {

    public PlainHolder() {}

    public String echo(String name) {
        return name;
    }
}
