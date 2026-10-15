package art.rehra.mineqtt.compat.permissions;

//? if <1.21.11 {
/*// An operator level as a {@link PermissionSet}: it holds every command level up to its own.
public record LevelBasedPermissionSet(int level) implements PermissionSet {
    public static final LevelBasedPermissionSet ALL = new LevelBasedPermissionSet(0);
    public static final LevelBasedPermissionSet MODERATOR = new LevelBasedPermissionSet(1);
    public static final LevelBasedPermissionSet GAMEMASTER = new LevelBasedPermissionSet(2);
    public static final LevelBasedPermissionSet ADMIN = new LevelBasedPermissionSet(3);
    public static final LevelBasedPermissionSet OWNER = new LevelBasedPermissionSet(4);

    public static LevelBasedPermissionSet forLevel(int level) {
        return new LevelBasedPermissionSet(Math.max(0, Math.min(4, level)));
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return permission instanceof Permission.HasCommandLevel command && command.level() <= level;
    }
}
*///?}
