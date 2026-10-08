package art.rehra.mineqtt.security;

import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.security.C2SGuard.Facts;
import art.rehra.mineqtt.security.C2SGuard.Rejection;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The pure parts of the security layer, run by Gradle's verifySecurityGuards task: the token bucket and per-player
 * rate limits, the order of the {@link C2SGuard} checks, the field caps and the {@link DeviceAccess} decision table.
 * Plain Java, no game bootstrap needed.
 */
public final class SecurityGuardsCheck {
    private static final long SECOND = 1_000_000_000L;

    static void main(String[] args) {
        tokenBucket();
        rateLimit();
        guardOrder();
        fieldCaps();
        accessTable();
        System.out.println("Security guard checks passed.");
    }

    private static void tokenBucket() {
        long[] now = {5 * SECOND};
        TokenBucket bucket = new TokenBucket(20, 20, () -> now[0]);
        for (int i = 0; i < 20; i++) check(bucket.tryTake(), "A full bucket allows a burst of its capacity, token " + i);
        check(!bucket.tryTake(), "The 21st action within the same instant is refused");
        now[0] += SECOND / 20;
        check(bucket.tryTake(), "One token is back after 1/20 s");
        check(!bucket.tryTake(), "And only one");
        now[0] += 10 * SECOND;
        for (int i = 0; i < 20; i++) check(bucket.tryTake(), "A long pause refills to the capacity, token " + i);
        check(!bucket.tryTake(), "Never beyond the capacity");
        now[0] -= SECOND;
        check(!bucket.tryTake(), "A clock stepping back refills nothing");
        now[0] += SECOND + SECOND / 20;
        check(bucket.tryTake(), "Nor does it drain the bucket");

        long[] slow = {0};
        TokenBucket publishes = new TokenBucket(2, 2, () -> slow[0]);
        check(publishes.tryTake() && publishes.tryTake() && !publishes.tryTake(), "2 per second: a burst of two");
        slow[0] += SECOND / 4;
        check(!publishes.tryTake(), "Half a token after 1/4 s is not enough");
        slow[0] += SECOND / 4;
        check(publishes.tryTake(), "A whole token after 1/2 s");

        rejects(() -> new TokenBucket(0, 1, () -> 0), "a capacity of zero");
        rejects(() -> new TokenBucket(1, 0, () -> 0), "a refill rate of zero");
        rejects(() -> new TokenBucket(1, Double.NaN, () -> 0), "a NaN refill rate");
    }

    private static void rateLimit() {
        long[] now = {0};
        RateLimit limit = new RateLimit(2, 2, () -> now[0]);
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        check(limit.tryTake(steve) && limit.tryTake(steve) && !limit.tryTake(steve), "Steve uses up his bucket");
        check(limit.tryTake(alex), "Alex has his own bucket");
        limit.forget(steve);
        check(limit.tryTake(steve), "A player who left and came back starts with a full bucket");
        limit.clear();
        check(limit.tryTake(alex) && limit.tryTake(alex), "Clearing refills everyone");
    }

    private static void guardOrder() {
        List<String> asked = new ArrayList<>();
        check(C2SGuard.decide(facts(asked, -1)).isEmpty() && asked.size() == 7, "Every fact holds: accepted after asking all seven");
        Rejection[] expected = Rejection.values();
        for (int failing = 0; failing < expected.length; failing++) {
            asked.clear();
            Optional<Rejection> rejection = C2SGuard.decide(facts(asked, failing));
            check(rejection.equals(Optional.of(expected[failing])), "Failing fact " + failing + " is " + expected[failing] + ", got " + rejection);
            check(asked.size() == failing + 1, "Nothing after the failing fact " + expected[failing] + " is asked: " + asked);
        }
        check(asked.getFirst().equals("tokenTaken"), "The rate limit comes first, so a flood of bad payloads is cut off too");
    }

    private static void fieldCaps() {
        String prefix = "mineqtt/shared/";
        check(C2SGuard.fitsTopic(prefix + "x".repeat(256 - prefix.length())), "A 256-byte topic fits");
        check(!C2SGuard.fitsTopic(prefix + "x".repeat(257 - prefix.length())), "A 257-byte topic does not");
        check(!C2SGuard.fitsTopic("é".repeat(129)), "Topics count UTF-8 bytes: 129 two-byte characters are 258 bytes");
        check(C2SGuard.fitsPayload("x".repeat(1024)), "A 1 KiB payload fits");
        check(!C2SGuard.fitsPayload("x".repeat(1025)), "1025 bytes do not");
        check(!C2SGuard.fitsPayload("€".repeat(342)), "Payloads count UTF-8 bytes: 342 three-byte characters are 1026 bytes");
    }

    private static void accessTable() {
        for (MineqttPermission need : MineqttPermission.values()) {
            for (AccessMode mode : AccessMode.values()) {
                check(DeviceAccess.permits(need, mode, true, false, never()), "An op may " + need + " a " + mode + " device without a claim check");
                check(DeviceAccess.permits(need, mode, false, true, never()), "The owner may " + need + " a " + mode + " device without a claim check");
            }
            check(!DeviceAccess.permits(need, AccessMode.PRIVATE, false, false, never()), "Nobody else may " + need + " a private device");
            check(DeviceAccess.permits(need, AccessMode.CLAIM, false, false, () -> true), "A claim-trusted player may " + need + " a claim device");
            check(!DeviceAccess.permits(need, AccessMode.CLAIM, false, false, () -> false), "An untrusted player may not " + need + " a claim device");
        }
        check(DeviceAccess.permits(MineqttPermission.USE, AccessMode.PUBLIC, false, false, never()), "Anyone may use a public device");
        check(DeviceAccess.permits(MineqttPermission.CONFIGURE, AccessMode.PUBLIC, false, false, () -> true), "A claim-trusted player configures a public device");
        check(!DeviceAccess.permits(MineqttPermission.CONFIGURE, AccessMode.PUBLIC, false, false, () -> false), "An untrusted player may not configure a public device");
        check(AccessMode.DEFAULT == AccessMode.CLAIM, "Devices follow the claim by default");
    }

    /** Facts that all hold except the one at {@code failing} (-1: none), recording which were asked. */
    private static Facts facts(List<String> asked, int failing) {
        String[] names = {"tokenTaken", "menuOpen", "menuValid", "loaded", "inReach", "blockEntityMatches", "permitted"};
        BooleanSupplier[] suppliers = new BooleanSupplier[names.length];
        for (int i = 0; i < names.length; i++) {
            int index = i;
            suppliers[i] = () -> {
                asked.add(names[index]);
                return index != failing;
            };
        }
        return new Facts(suppliers[0], suppliers[1], suppliers[2], suppliers[3], suppliers[4], suppliers[5], suppliers[6]);
    }

    private static BooleanSupplier never() {
        return () -> {
            throw new AssertionError("The claim must not be asked");
        };
    }

    private static void rejects(Runnable build, String description) {
        try {
            build.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Accepted " + description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
