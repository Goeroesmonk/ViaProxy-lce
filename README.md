# ViaProxy-lce

A [ViaProxy](https://github.com/ViaVersion/ViaProxy) plugin that lets **Java Edition** clients join
**Minecraft Legacy Console Edition (LCE) Revelations** servers.

It adds a server version called **`LCE Revelations`** to ViaProxy. Pick it, enter the server address and connect with any
Java version ViaProxy supports (1.7 up to the newest).

> Work in progress: most of the game works, but expect bugs. See [Known issues](#known-issues).

## Install

1. Download `ViaProxyLCE-x.y.z.jar` from [Releases](../../releases) or [build it](#build).
2. Put it into ViaProxy's `plugins` folder.
3. Start ViaProxy (3.4.0 or newer).
4. Set **Server Version** to `LCE Revelations` and **Server Address** to the LCE server (for example `lce.example.net`).
5. Connect your Java client to ViaProxy (default `localhost:25568`).

Like the LCE client, the plugin follows the server's `_minecraft._tcp` SRV record when you don't type a port.
Your user name is the one of your Java client (with `proxy-online-mode` off, any name works).

## Features

- Play on Revelations servers: world, entities, inventories, chat, sounds, signs, ...
- **Your skin for LCE players:** your Java skin is sent to the server, so LCE players see it.
- **Skins of LCE players** (1.8+ clients): the default skins and the skins of the DLC packs that come with the
  Revelations client are shown. The plugin ships a list of their signed Mojang textures. Skins from packs only the server has
  show as Steve / Alex. Players with a custom skin (like other players using this plugin) get the Java skin of their name.
- **Locator bar** (1.21.6+ clients): the other players show up on the locator bar, each in a color made from their name.
  It shows every player the server sends you: LCE servers send players
  within the server's view distance (up to 512 blocks), also outside your own render distance.
- **World edge** (1.8+ clients): the edge of the LCE world is shown as a world border, with the endless sea
  behind it like on LCE (grass on superflat worlds).
- Server list: shows the player count of the server.

## Config

`plugins/LCERevelations/config.properties`:

| Option | Default | Meaning |
|---|---|---|
| `skin` | `auto` | The skin LCE players see on you. `auto` = the Java skin of your user name (looked up at Mojang), another player's name, a path to a 64x64 / 64x32 PNG, or `none`. LCE only knows 64x32 skins: the second layer is painted onto the first and slim arms are widened. |
| `lce-skins` | `true` | Show the skins of LCE players. |
| `world-border` | `true` | Show the edge of the LCE world as a world border, with the sea behind it. |
| `locator-bar` | `true` | Show the other players on the locator bar of 1.21.6+ clients. |
| `xuid` | `auto` | Your player id. `auto` makes a stable id from your user name. To be the same player as your LCE client, copy the value from its `uid.dat`, e.g. `0xD8A081207D94B205`. |
| `netcode-version` | `560` | LCE network version sent at login (Revelations). Change it if the server says your client is outdated. |
| `srv-lookup` | `true` | Follow SRV records when no port is given. |
| `debug` | `false` | Log every packet and the packets before a protocol error. Turn it on when you report a bug. |

## Known issues

- Skin changes of LCE players show after they come back into view. Capes are not shown.
- The tab list shows you and the players the server has sent you (the same as the locator bar): the LCE player list
  packet has no names, so players far away are missing.
- Revelations' optional encryption (`MC|CKey`) and identity tokens (`MC|CTChallenge`) are not supported. Servers that
  require them don't work.
- The server sends broken packets for entities with ids above 2047 (LCE clients crash on them too). The plugin repairs
  them; if you still get `Unknown LCE packet id`, please open an issue with the log.
- Live play has only been tested on a few servers.

## How it works

LCE (TU19 based) speaks a protocol derived from Java Edition **1.6.4**. The plugin translates it to real 1.6.4 packets, and
ViaLegacy / ViaVersion translate those to your client's version.

- **Transport (Windows64 / Revelations):** after connecting, the server sends one byte (the player's "small id").
  Then both sides send frames of `[u32 big-endian length][bytes]`. The frames together form a stream of packets
  (1 byte id + big-endian fields, strings as `short length + UTF-16`).
- **Login:** client `PreLogin` (netcode version, name) → server `PreLogin` → client `Login` (protocol 78, XUIDs, skin) →
  server `Login` (entity id, game mode, dimension, ...). The plugin does this itself, then gives ViaLegacy a 1.6.4 `Login`.
- **Differences translated:**
  - chunks: zlib + RLE compressed block regions with their own layout → 1.6.4 chunks / multi block changes
  - entity ids are shorts in spawn / move packets, and there are bit-packed "small" move packets (ids 162–165)
  - entity rotations in move packets are **relative** (absolute in Java) → tracked per entity
  - NBT is uncompressed (Java 1.6.4: gzip)
  - chat is a message type + arguments (localised on the console) → English text
  - sounds are an index into LCE's sound list → 1.6.4 sound names
  - disconnect reasons are numbers → text
  - attributes and modifiers use ids instead of names / UUIDs
  - extra fields in health, explosion, sign, respawn, player spawn and container packets
  - skins: the plugin answers the server's texture requests with your skin; other LCE-only packets are dropped
- **Server list pings:** LCE has no status packet, so the plugin sends a `PreLogin` and shows the player count from the reply.

## Build

```sh
./gradlew build
```

The jar is written to `build/libs/`. The build downloads the ViaProxy release jar to compile against.

This project contains no Minecraft source code.

## License

GPL-3.0, same as ViaProxy.
