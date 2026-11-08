package no.sikt.graphitron.rewrite.test.services;

/** An object an instance method runs on, made with nothing. */
public final class CallPlainHolderService {

    public CallPlainHolderService() {}

    public String echo(String name) {
        return name;
    }
}
