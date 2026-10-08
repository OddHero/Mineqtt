package art.rehra.mineqtt.device;

/** Which way a {@link Field} goes: published in the state, accepted in commands, or both. */
public enum Access {
    READ,
    WRITE,
    READ_WRITE;

    public boolean readable() {
        return this != WRITE;
    }

    public boolean writable() {
        return this != READ;
    }
}
