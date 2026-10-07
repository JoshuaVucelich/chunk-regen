# Chunk Regen

Server-side Fabric mod. `/regen` wipes chunks out of the region files so the normal world generator builds them again from the seed, floor to ceiling. Typical use is fixing a griefed Nether ceiling, since bedrock comes back with the rest of the chunk.

License: CC0-1.0. Author: VWS Digital.

This branch targets Minecraft 26.3. Older releases are on `mc/26.1`, `mc/26.1.1`, `mc/26.1.2`, and `mc/26.2`.

- Mod id: `chunk-regen`
- Version: 1.0.0
- Fabric Loader 0.19.5 or newer, Fabric API, Java 25+
- Server only (`"environment": "server"`)

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
