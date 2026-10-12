package art.rehra.mineqtt.integrations;

/**
 * What a player wants to do with a MineQTT block (design §8.7), judged per block by its access mode and the claim mod.
 * Rights that hold server-wide instead, such as binding a device to Home Assistant ({@code mineqtt:twin.bind}),
 * turning its control on ({@code mineqtt:twin.control}) or making rooms for every device inside them
 * ({@code mineqtt:rooms.admin}, op level 2 by default), are vanilla permission atoms in
 * {@link art.rehra.mineqtt.security.AclContext}; a twin edit needs {@link #CONFIGURE} on the block as well.
 */
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
