package no.sikt.graphitron.example.service;

public class CustomBusinessException extends RuntimeException {
    public CustomBusinessException(String message) {
        super(message);
    }
}
