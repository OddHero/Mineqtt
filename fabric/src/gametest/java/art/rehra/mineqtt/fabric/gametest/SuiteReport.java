package art.rehra.mineqtt.fabric.gametest;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The soft checks of one suite. Every result is logged and appended to {@link #FILE_NAME} in the game directory, which
 * {@code :fabric:runClientGameTest} reads for its verdict: a {@code SUITE PASS} line per suite and no {@code FAIL} line.
 * A suite keeps going after a failed check, so one run shows every failure.
 */
final class SuiteReport {
    static final String FILE_NAME = "mineqtt-gametest-report.txt";
    private static final Logger LOG = LoggerFactory.getLogger("MineQTT-GameTest");

    private final String suite;
    private final List<String> failures = new ArrayList<>();

    SuiteReport(String suite) {
        this.suite = suite;
    }

    void check(boolean condition, String what) {
        write((condition ? "PASS " : "FAIL ") + suite + ": " + what);
        if (!condition) failures.add(what);
    }

    /** A part of the suite that cannot run in this environment; reported, never silently passed. */
    void skip(String what, String reason) {
        write("SKIP " + suite + ": " + what + " (" + reason + ")");
    }

    void info(String text) {
        LOG.info("[{}] {}", suite, text);
    }

    /** The suite stopped on an exception: a timeout, a crash or a hard assertion. */
    void abort(Throwable error) {
        write("FAIL " + suite + ": aborted: " + error);
    }

    /** Ends the suite; throws when a check failed, which stops the client with a failure. */
    void finish() {
        if (failures.isEmpty()) {
            write("SUITE PASS " + suite);
            return;
        }
        write("SUITE FAIL " + suite);
        throw new AssertionError(suite + " failed: " + failures);
    }

    private void write(String line) {
        LOG.info(line);
        Path report = FabricLoader.getInstance().getGameDir().resolve(FILE_NAME);
        try {
            Files.writeString(report, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the game test report " + report, e);
        }
    }
}
