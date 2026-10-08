package art.rehra.mineqtt.config;

/** A boolean setting whose default depends on the server type. */
public enum AutoBoolean {
    AUTO,
    TRUE,
    FALSE;

    /** The effective value; {@code autoValue} is what {@link #AUTO} means on this server. */
    public boolean resolve(boolean autoValue) {
        return switch (this) {
            case AUTO -> autoValue;
            case TRUE -> true;
            case FALSE -> false;
        };
    }
}
