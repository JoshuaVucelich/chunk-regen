# Chunk Regen

Server-side NeoForge mod. `/regen` wipes chunks out of the region files so the normal world generator builds them again from the seed, floor to ceiling. Typical use is fixing a griefed Nether ceiling, since bedrock comes back with the rest of the chunk.

License: CC0-1.0. Author: VWS Digital.

This branch is the NeoForge port for Minecraft 26.2. NeoForge 26.3 is still beta (`26.3.0.x-beta`), so the port targets the newest stable line that matches a Fabric build: NeoForge `26.2.0.89` on Minecraft 26.2. Fabric releases are on `mc/26.1`, `mc/26.1.1`, `mc/26.1.2`, `mc/26.2`, `mc/26.3`, and `mc/1.21.11`.

- Mod id: `chunk-regen`
- Version: 1.1.0+26.2-neoforge
- NeoForge 26.2.0.89 or newer on the 26.2 line, Minecraft `[26.2,26.3)`, Java 25
- ModDevGradle 2.0.148
- Dedicated server only (`@Mod(dist = Dist.DEDICATED_SERVER)`)

The regen path is the same as Fabric: pending chunks are cleared from the Anvil header before worlds load (`ServerAboutToStartEvent`, before `initServer` on Fabric), and a live regen writes `IOWorker.STORE_EMPTY` at end of tick. NeoForge fires `ChunkEvent.Unload` after the chunk save, so the empty write has to stay at end of tick or the save would put the old chunk back. `./gradlew test` checks that header clear, including negative chunk coordinates.

## Commands

Ops only (permission level 2). Nothing is deleted until you confirm.

| Command | What it does |
|---|---|
| `/regen` | Ask to wipe the chunk you are standing in |
| `/regen <radius>` | Ask to wipe a square around you. Radius is in chunks, 0 to 16, so 2 means a 5 by 5 |
| `/regen confirm` | Run the last request. You have 60 seconds |
| `/regen cancel` | Drop the pending request |
| `/regen status` | Show running jobs and chunks still waiting |

The request remembers the dimension and chunk you were in when you typed it.

From the server console:

```
execute in minecraft:the_nether positioned <x> <y> <z> run regen 2
regen confirm
```

## What confirm does

Players near the area are moved out of the way so the chunks can unload. The mod then clears those chunks from the region file. The next time a chunk loads, there is no saved data, so Minecraft generates it fresh from the seed.

Force-loaded chunks are skipped. If something keeps a chunk loaded (a player walks back, a portal ticket, a forceload nearby), it stays queued and is wiped once it unloads, or on the next server start.

Builds in the wiped chunks are gone. Back up the world first. Entities and POI data are stored separately and are not deleted. A fresh chunk next to old ones can have a hard seam, so include a border chunk if that matters.
