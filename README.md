# Mineqtt

## Control real devices from inside your Minecraft World

Control real life devices with minecraft redstone or display sensor values on your fancy 7 segment redstone display, or
use it to wirelessly connect redstone contraptions in your Minecraft world.

Or maybe you want to know if your virtual nuclear reactor is about to blow up, with Mineqtt you could trigger a real
airhorn/sirene to wake you up before its too late.

## Inter Server Communication - Signals Between Minecraft Worlds

Another Use Case is wireless redstone between multiple servers that are connected to the same mqtt broker. You can also
send signals from Single Player Worlds to online Servers and Vice versa.

## Note of caution

This Mod establishes a connection to a seperate MQTT Server (Configurable). Connecting to public mqtt servers bears the
risk of someone else triggering your redstone or sending unfiltered Data to your server/world or receiving the signals
you send via unsecured public mqtt servers. I suggest using a self hosted MQTT-Broker.

## Roadmap

### Implemented

- Connect to MQTT-Broker with username/password Authentication
- Gui to configure topics for Senders and Receivers
- Portable Topic Debugger (Subscribe/Publish via handheld "Cyberdeck")

### Planned

- Send/Receive Redstone Signal Strength
- Integrate Native Minecraft features like chat, scorebord, achievements,...)

### Showcase

#### Send redstone state to MQTT

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/o80VsFaYbnM" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

#### Receive redstone state from MQTT

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/bIY0cpPkwmU" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

#### Configuring topics using item stacks and named paper

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/w5Pt1TFN1m4" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

