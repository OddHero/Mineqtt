# Developing MineQTT

Notes for building, testing and releasing MineQTT from source. The user documentation is in the
[wiki](https://github.com/OddHero/Mineqtt/wiki); the architecture is described in
[mineqtt-architecture.md](mineqtt-architecture.md).

## Project layout

The build is a [Stonecutter](https://stonecutter.kikugie.dev/) multi-version project, set up like
[minecraft-mod-template](https://github.com/OddHero/minecraft-mod-template).

- `common`, `fabric` and `neoforge` each have one Gradle project per Minecraft version, such as `:fabric:1.21.4`.
- The versions are listed in [`settings.gradle`](../settings.gradle).
- Each version's dependency versions are in [`versions/<minecraft>/gradle.properties`](../versions).
- The sources in `src/` are written for the active version: 26.2, set in [`stonecutter.gradle`](../stonecutter.gradle).
- The other versions compile a copy that Stonecutter rewrites.

How the copies are rewritten:

- `//? if >=1.21.5 {` … `//?} else {` … `//?}` comments choose code per version. The branch for the other versions
  stays commented out.
- `stonecutter.gradle` renames classes and methods that only changed their name (`Identifier`, `GuiGraphicsExtractor`,
  …).
- It also swaps imports for the stand-ins in `art.rehra.mineqtt.compat`. That package also holds the helpers that hide
  API changes behind one call.
- [`common/build.gradle`](../common/build.gradle) rewrites the data files a version reads differently: recipes before
  1.21.2, the concrete tag before 26.2, and advancements on 26.3.

## Working on another version

1. In the IDE, run the Gradle task *Set active project to 1.21.4* (group `stonecutter`), or the one for the version
   you want.
2. Work on that version.
3. Run *Reset active project* before you commit. The active version's spelling is what the repository holds.

Minecraft up to 1.21.11 needs Java 21, 26.x needs Java 25. Gradle looks for both in the usual places.

## Building

| Command | What it does |
|---|---|
| `./gradlew build` | Builds and checks every version |
| `./gradlew :fabric:1.21.4:build` | Builds one version and loader |
| `./gradlew collectJars` | Copies all release jars to `build/libs` |

The client game tests run from 1.21.4 on.

## Development launches

Gradle generates IntelliJ run configurations, grouped under **Fabric** and **NeoForge**. Each loader has four:

| Configuration | What it starts |
|---|---|
| `Server:<loader>` | A dedicated server without a GUI |
| `Client:<loader>` | A client with DevAuth explicitly disabled |
| `Client:<loader>:Main` | A client with DevAuth, using the `main` account |
| `Client:<loader>:Alt` | A client with DevAuth, using the `alt` account |

- The login variants use the accounts in your existing DevAuth configuration. Account setup and tokens stay local;
  see the [DevAuth documentation](https://github.com/DJtheRedstoner/DevAuth#configuration-file).
- All four configurations for a loader share that loader's existing run directory.
- To regenerate the configurations, run `./gradlew :fabric:26.2:ideaSyncTask :neoforge:26.2:ideaSyncTask` (or the
  version you work on).
- The matching Gradle launch tasks are `runServer`, `runClient`, `runClientDevMain` and `runClientDevAlt` in each
  version's loader project, such as `:fabric:26.2:runClient`.
- If an existing IntelliJ configuration keeps old JVM arguments after a dependency upgrade, remove that configuration
  and regenerate it. Loom preserves JVM arguments in existing application configurations.

## Checks and end-to-end tests

- `./gradlew check` runs the regression checks of every version.
- [`e2e/`](../e2e/README.md) holds the Docker environment (Mosquitto 2 and 1.5, Home Assistant) and the manual
  end-to-end checklist.

### A broker's no-local behaviour

A Redstone Output whose chunk loads after a Redstone Input on the same server changed its Raw Link topic needs the
broker to deliver the server's own retained message on an MQTT 5 no-local subscribe (Mosquitto 2 does). To test a
broker, run:

```sh
./gradlew :common:26.2:verifyBrokerSmoke --args=host:port
```

"own retained message on a no-local subscribe: yes" means the broker delivers it.

## Releases

GitHub Actions builds both loaders for every Minecraft version and runs `./gradlew build` (all checks) on every push
to `main` and on pull requests. The jars are attached to the run for two weeks.

A release publishes the version in [`gradle.properties`](../gradle.properties) (`mod_version`) for Fabric and
NeoForge on every Minecraft version: a GitHub release with all jars, and one Modrinth version per loader and Minecraft
version (numbered `<mod_version>+<minecraft>`), each with that version's section of [`CHANGELOG.md`](../CHANGELOG.md).

1. Set `mod_version`, add its `## <version>` section to `CHANGELOG.md` and merge to `main`.
2. Push a tag that equals the version: `git tag 0.8.0 && git push origin 0.8.0` (`v0.8.0` works as well). Or start the
   *Release* workflow by hand on the Actions tab.

- A version with `-alpha`, `-beta` or `-rc` is published as a pre-release on GitHub and as an alpha or beta on
  Modrinth.
- The workflow needs the repository secret `MODRINTH_TOKEN`: a Modrinth personal access token with the *Create
  versions* scope.
- Re-running a failed release is safe. The GitHub release gets its files replaced, and a jar that Modrinth already
  lists is skipped.
- To see what would be uploaded without sending anything, run
  `MODRINTH_DEBUG=true MODRINTH_TOKEN=x ./gradlew :fabric:26.2:modrinth :neoforge:26.2:modrinth`.

## Worlds from v2 test builds

Worlds that a v2 test build (before 0.6.0) already ran need two notes when they load in a release:

- A Raw Link saved by such a build that the allowlist now refuses was moved once, as a channel the allowlist does
  not cover is moved to `mineqtt/shared/<name>`, and the player who set it (or its owner) was told.
- A test build that already migrated v1 lamps left an empty "MineQTT RGB LED" device next to each migrated lamp in
  Home Assistant. The device config now names both devices, and each lamp keeps its `light.…` entity id and history.
  - Home Assistant 2026.10 logs "Invalid device info … already registered" for those lamps and does not add their
    sensors until its next restart. Then it keeps one of the two devices (which one varies) and removes the other.
  - Older versions such as 2025.2 keep both devices without errors. Delete the empty device under Settings → Devices;
    if that fails with "Unknown error", restart Home Assistant and delete it again.
  - Either way a lamp can end up on the device the test build made. That device has a different device id and not the
    old device's area, so device automations aimed at the old device stop working. Check each lamp's area and device
    automations afterwards and set them again.
