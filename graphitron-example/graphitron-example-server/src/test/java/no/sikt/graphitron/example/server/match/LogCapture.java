package no.sikt.graphitron.example.server.match;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class LogCapture extends Handler implements AutoCloseable {
    private final List<LogRecord> records = new ArrayList<>();
    private final Logger logger;

    public LogCapture(Class<?> loggerClass) {
        this.logger = Logger.getLogger(loggerClass.getName());
        logger.addHandler(this);
    }

    public List<LogRecord> getRecords() {
        return records;
    }

    public List<String> getMessages() {
        return records.stream().map(LogRecord::getMessage).toList();
    }

    public List<String> getWarnings() {
        return records.stream()
                .filter(r -> r.getLevel().equals(Level.WARNING))
                .map(LogRecord::getMessage)
                .toList();
    }

    @Override
    public void publish(LogRecord record) {
        records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
        logger.removeHandler(this);
    }
}