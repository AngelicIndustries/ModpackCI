package io.github.angelicindustries.modpackci;

/** An expected failure. Main prints only its message, without a stack trace. */
public final class Fail extends RuntimeException {
    public Fail(String message) {
        super(message);
    }

    public Fail(String message, Throwable cause) {
        super(message, cause);
    }
}
