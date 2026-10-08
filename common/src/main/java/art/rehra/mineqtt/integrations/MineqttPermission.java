package art.rehra.mineqtt.integrations;

/** What a player wants to do with a MineQTT block (design §8.7). */
public enum MineqttPermission {
    /** Toggle, press, send a command, open the screen. */
    USE("mineqtt:use"),
    /** Everything else: topics, settings and the items in the screen's slots. */
    CONFIGURE("mineqtt:configure");

    private final String permissionKey;

    MineqttPermission(String s) {
        this.permissionKey = s;
    }
    
    public String getPermissionKey() {
        return permissionKey;
    }
}
