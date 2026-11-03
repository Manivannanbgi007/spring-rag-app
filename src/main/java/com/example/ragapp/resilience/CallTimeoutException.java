package com.example.ragapp.resilience;

import java.time.Duration;

/** A call to a model took longer than its timeout. It counts as a transient failure (retry is allowed). */
public class CallTimeoutException extends RuntimeException {

    public CallTimeoutException(String name, Duration timeout) {
        super("The call to the " + name + " did not finish within " + timeout.toSeconds() + " seconds");
    }
}
