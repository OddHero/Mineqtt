#!/bin/sh
# Starts the lane's Fabric server. Runs on every container start, so a restart picks up a rebuilt MineQTT jar.
#   /opt/mineqtt   the lane's mods volume: lane.sh ships the host-built MineQTT jar into it as mineqtt.jar (docker cp)
#   /opt/deps      Fabric API and Architectury, downloaded into the image at build time
#   /server/world  named volume (removed by lane.sh down)
set -eu
cd /server

# Mods: the bundled MineQTT jar (never the -raw jar, which lacks HiveMQ, or -sources) plus the dependencies.
rm -f mods/*.jar
found=""
for jar in /opt/mineqtt/*.jar; do
    case "$jar" in
        *-raw.jar|*-sources.jar|*-dev*.jar) continue ;;
    esac
    [ -f "$jar" ] || continue
    [ -z "$found" ] || { echo "lane-server: more than one MineQTT jar in /opt/mineqtt: $found $jar" >&2; exit 1; }
    found="$jar"
done
[ -n "$found" ] || { echo "lane-server: no MineQTT jar in /opt/mineqtt, lane.sh ships it on up and reload" >&2; exit 1; }
cp "$found" mods/
for jar in /opt/deps/*.jar; do
    [ -f "$jar" ] && cp "$jar" mods/
done
echo "lane-server: mods: $(ls mods | tr '\n' ' ')"

echo "eula=true" > eula.txt

# Written once per container, so edits made inside a running lane survive a restart; recreating the container resets them.
if [ ! -f server.properties ]; then
    cat > server.properties <<EOF
online-mode=false
enforce-secure-profile=false
level-type=minecraft\:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
generate-structures=false
spawn-monsters=false
difficulty=peaceful
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=4
motd=MineQTT lane ${LANE_ID:-?}
server-port=25565
enable-rcon=true
rcon.port=25575
rcon.password=${RCON_PASSWORD:-mineqtt-lane}
broadcast-rcon-to-ops=false
pause-when-empty-seconds=${PAUSE_WHEN_EMPTY_SECONDS:-60}
EOF
fi

if [ ! -f config/mineqtt.json ]; then
    cat > config/mineqtt.json <<EOF
{
  "configVersion": 2,
  "broker": "${MINEQTT_BROKER:-tcp://mosquitto:1883}",
  "bridgeId": "${MINEQTT_BRIDGE_ID:-}",
  "ha": {
    "expose": "${MINEQTT_HA_EXPOSE:-AUTO}"
  },
  "server": {
    "keepTickingWhenEmpty": ${MINEQTT_KEEP_TICKING:-false}
  }
}
EOF
fi

# The health check greps the current log only.
rm -f logs/latest.log

exec java ${JAVA_OPTS:--Xms512M -Xmx1G} -jar fabric-server-launch.jar nogui
