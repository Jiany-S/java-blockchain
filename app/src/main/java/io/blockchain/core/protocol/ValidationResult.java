package io.blockchain.core.protocol;

public final class ValidationResult {
    public final boolean ok;
    public final ProtocolError error;
    public final String message;

    private ValidationResult(boolean ok, ProtocolError error, String message) {
        this.ok = ok; this.error = error; this.message = message;
    }
    public static ValidationResult ok() { return new ValidationResult(true, null, null); }
    public static ValidationResult error(ProtocolError e, String msg) { return new ValidationResult(false, e, msg); }

    @Override public String toString() {
        return ok ? "OK" : ("ERR["+error+"]: "+message);
    }
}
