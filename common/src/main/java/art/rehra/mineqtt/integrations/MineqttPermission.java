package art.rehra.mineqtt.integrations;

public enum MineqttPermission {
    INTERACT("mineqtt:interact");

    private final String permissionKey;

    MineqttPermission(String s) {
        this.permissionKey = s;
    }
    
    public String getPermissionKey() {
        return permissionKey;
    }
}
