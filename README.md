# ViaProxy-lce
# To Much is still broken
A [ViaProxy](https://github.com/ViaVersion/ViaProxy) plugin that lets **Java Edition** clients join
**Minecraft Legacy Console Edition (LCE) Revelations** servers.

It adds a server version called **`LCE Revelations`** to ViaProxy. Pick it, enter the server address, and connect with any
Java version ViaProxy supports (1.7 up to the newest).

## Install

1. Download `ViaProxyLCE-x.y.z.jar` from [Releases](../../releases) or build it yourself (see below).
2. Put it into ViaProxy's `plugins` folder.
3. Start ViaProxy (3.4.0 or newer).
4. Set **Server Version** to `LCE Revelations` and **Server Address** to the LCE server (for example `lce.example.net`).
5. Connect your Java client to ViaProxy (default `localhost:25568`).

Like the LCE client, the plugin follows the server's `_minecraft._tcp` SRV record when you don't type a port.

## Config

`plugins/LCERevelations/config.properties`:

| Option | Default | Meaning |
|---|---|---|
| `netcode-version` | `560` | LCE network version sent at login (Revelations). Change it if the server says your client is outdated. |
| `xuid` | `auto` | Your player id. `auto` makes a stable id from your user name. To appear as the same player as your LCE client, copy the value from its `uid.dat`, e.g. `0xD8A081207D94B205`. |
| `srv-lookup` | `true` | Follow SRV records when no port is given. |
| `debug` | `false` | Log dropped / unknown packets and every packet sent to ViaLegacy. |

Your user name is the one you use in your Java client (with `proxy-online-mode` off, any name works).

## How it works

LCE (TU19 based) speaks a protocol derived from Java Edition **1.6.4**. The plugin translates it to real 1.6.4 packets, and
ViaLegacy / ViaVersion translate those to your client's version.

- **Transport (Windows64 / Revelations):** after connecting, the server sends one byte (the player's "small id").
  Then both sides send frames of `[u32 big-endian length][bytes]`. The frames together form a stream of packets
  (1 byte id + big-endian fields, strings as `short length + UTF-16`).
- **Login:** client `PreLogin` (netcode version, name) → server `PreLogin` → client `Login` (protocol 78, XUIDs) → server
  `Login` (entity id, game mode, dimension, ...). The plugin does this itself, then gives ViaLegacy a 1.6.4 `Login`.
- **Differences translated:**
  - chunks: LCE sends zlib + RLE compressed block regions with its own layout → 1.6.4 chunk sections / multi block changes
  - entity ids are shorts in spawn / move packets, and there are bit-packed "small" move packets (ids 162–165)
  - entity rotations in move packets are **relative** in LCE (absolute in Java) → tracked per entity
  - NBT is sent uncompressed (Java 1.6.4: gzip)
  - chat is a message type + arguments (localised on the console) → English text
  - sounds are an index into LCE's sound list → 1.6.4 sound names
  - disconnect reasons are numbers → text
  - attributes and modifiers use ids instead of names / UUIDs
  - extra fields in health, explosion, sign, respawn, player spawn, container packets
  - LCE-only packets (textures, skins, game rules, trades, ...) are dropped
- Server list pings: LCE has no status packet, so the plugin sends a `PreLogin` and shows the player count from the reply.

## Status

- Tested by replaying a recorded session from a real Revelations server (login, ~380 chunks, thousands of entity
  packets, chat) through ViaProxy into a 1.8 client, and checking the packets the client sent back (movement, chat,
  keep alive). Live play on a real server still needs more testing.
- Revelations' optional encryption (`MC|CKey` / `MC|CAck` / `MC|COn`) and identity tokens (`MC|CTChallenge`) are **not
  supported** yet. Servers that require them will not work.
- Skins, capes and other LCE-only features are not shown.
- Entities with ids above 2047 are not expected (LCE packs them into 11 bits).

## Build

```sh
./gradlew build
```

The jar is written to `build/libs/`. The build downloads the ViaProxy release jar to compile against.

This project contains no Minecraft source code. The protocol was worked out from network captures of the Revelations
client.

## License

GPL-3.0, same as ViaProxy.
