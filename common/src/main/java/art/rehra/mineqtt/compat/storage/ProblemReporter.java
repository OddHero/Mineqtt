package art.rehra.mineqtt.compat.storage;

//? if <1.21.6 {
/*// What the versions before 1.21.6 need of Minecraft's ProblemReporter: the discarding one that TagValueInput and
// TagValueOutput take. Stonecutter swaps the imports of net.minecraft.util.ProblemReporter for this interface.
public interface ProblemReporter {
    ProblemReporter DISCARDING = new ProblemReporter() {
    };
}
*///?}
