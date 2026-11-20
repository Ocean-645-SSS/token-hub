package com.tokenhub.model;
public class ModelProviderException extends RuntimeException {
    public enum Kind {
        INVALID_INPUT(false, false, false, false),
        REQUEST_REJECTED(false, false, false, false),
        CONNECT_FAILED(true, true, true, false),
        THROTTLED(false, true, true, false),
        OVERLOADED(false, true, true, false),
        OUTCOME_UNKNOWN(false, false, true, true);

        private final boolean retryable;
        private final boolean failover;
        private final boolean breakerFailure;
        private final boolean uncertain;

        Kind(boolean retryable, boolean failover, boolean breakerFailure, boolean uncertain) {
            this.retryable = retryable;
            this.failover = failover;
            this.breakerFailure = breakerFailure;
            this.uncertain = uncertain;
        }
    }

    private final Kind kind;

    public ModelProviderException(String message, boolean retryable, Throwable cause) {
        this(message, retryable ? Kind.CONNECT_FAILED : Kind.REQUEST_REJECTED, cause);
    }

    public ModelProviderException(String message, Kind kind, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public boolean clientInputInvalid() { return kind == Kind.INVALID_INPUT; }
    public boolean retryable() { return kind.retryable; }
    public boolean allowsFailover() { return kind.failover; }
    public boolean countsAgainstBreaker() { return kind.breakerFailure; }
    public boolean outcomeUnknown() { return kind.uncertain; }
}
