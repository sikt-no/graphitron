package no.sikt.graphitron.validation.messages.interfaces;

public interface UserMessage {
    String getMsg();
    default String format(Object... input) {
        return String.format(getMsg(), input);
    }
}
