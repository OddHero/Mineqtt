# Minecraft dedicated server with the Fabric loader for one MineQTT test lane (e2e/lanes/lane.sh).
# Everything except MineQTT itself is downloaded at build time (vanilla server jar, loader libraries, Fabric API and
# Architectury in the versions of gradle.properties, checked against Maven's sha1), so the image builds the same on this
# workstation and on a remote Docker host, needs no host Gradle cache, and a lane start fetches nothing. The MineQTT jar
# is shipped per lane by lane.sh (docker cp into the lane's mods volume); the entrypoint installs it on every start.

# The RCON client for lanes without host ports, compiled once (the runtime image has no compiler).
FROM eclipse-temurin:25-jdk AS rcon
COPY Rcon.java /src/Rcon.java
RUN javac --release 25 -d /out /src/Rcon.java

FROM eclipse-temurin:25-jre

ARG MC_VERSION=26.2
ARG LOADER_VERSION=0.19.5
ARG INSTALLER_VERSION=1.1.2
ARG FABRIC_API_VERSION=0.160.0+26.2
ARG ARCHITECTURY_VERSION=21.1.9

RUN useradd --system --uid 1500 --create-home --home-dir /home/minecraft minecraft \
    && mkdir -p /server/world /server/mods /server/config /opt/mineqtt \
    && chown -R minecraft:minecraft /server

ADD https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API_VERSION}/fabric-api-${FABRIC_API_VERSION}.jar /opt/deps/fabric-api.jar
ADD https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API_VERSION}/fabric-api-${FABRIC_API_VERSION}.jar.sha1 /opt/deps/fabric-api.jar.sha1
ADD https://maven.architectury.dev/dev/architectury/architectury-fabric/${ARCHITECTURY_VERSION}/architectury-fabric-${ARCHITECTURY_VERSION}.jar /opt/deps/architectury.jar
ADD https://maven.architectury.dev/dev/architectury/architectury-fabric/${ARCHITECTURY_VERSION}/architectury-fabric-${ARCHITECTURY_VERSION}.jar.sha1 /opt/deps/architectury.jar.sha1
RUN cd /opt/deps \
    && for j in fabric-api architectury; do echo "$(cut -d' ' -f1 "$j.jar.sha1")  $j.jar" | sha1sum -c - || exit 1; done \
    && rm -f /opt/deps/*.sha1 && chmod 0644 /opt/deps/*.jar

WORKDIR /server
ADD --chown=minecraft:minecraft \
    https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${LOADER_VERSION}/${INSTALLER_VERSION}/server/jar \
    /server/fabric-server-launch.jar

USER minecraft
# Without eula.txt the server downloads everything, writes eula.txt and stops; the exit code is not guaranteed zero.
# The ls fails the build if the download did not land.
RUN (java -jar fabric-server-launch.jar nogui || true) \
    && ls -la /server/.fabric/server /server/libraries \
    && rm -rf /server/eula.txt /server/server.properties /server/logs

USER root
COPY --from=rcon /out /opt/rcon
COPY entrypoint.sh /usr/local/bin/lane-server
RUN sed -i 's/\r$//' /usr/local/bin/lane-server && chmod 0755 /usr/local/bin/lane-server \
    && printf '#!/bin/sh\nexec java -Xshare:auto -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx32m -cp /opt/rcon Rcon "$@"\n' \
       > /usr/local/bin/lane-rcon && chmod 0755 /usr/local/bin/lane-rcon
USER minecraft

# Game port (only published for a client that joins from the host) and RCON.
EXPOSE 25565 25575
ENTRYPOINT ["/usr/local/bin/lane-server"]
