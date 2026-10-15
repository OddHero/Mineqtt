package art.rehra.mineqtt.compat.permissions;

//? if <1.21.11 {
/*import net.minecraft.resources.Identifier;

// A permission of {@link PermissionSet}: an operator level, or an atom of a mod.
public interface Permission {

    record Atom(Identifier id) implements Permission {
        public static Atom create(Identifier id) {
            return new Atom(id);
        }
    }

    record HasCommandLevel(int level) implements Permission {
    }
}
*///?}
