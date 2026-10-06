# Chunk Regen (Fabric, server-side) — Minecraft 26.3

`/regen` deletes chunks from the world's region files so the normal world generator recreates them from the seed —
floor to ceiling. Typical use: repairing a griefed Nether ceiling (bedrock comes back at Y 123–127 with netherrack below).

- Mod id: `chunk-regen` · Version 1.0.0 · Minecraft 26.3 · Fabric Loader ≥ 0.19.5 · Fabric API · Java 25+
- Server-only (`"environment": "server"`). License: CC0-1.0. Author: VWS Digital.

## Commands (op-only, permission level 2)

| Command | What it does |
|---|---|
| `/regen` | Request regen of the chunk you're standing in (radius 0) |
| `/regen <radius>` | Request regen of a square of (2r+1)² chunks around you in your current dimension (radius in **chunks**, 0–16) |
| `/regen confirm` | Actually run your last request (must be within 60 s) |
| `/regen cancel` | Drop your pending request |
| `/regen status` | Running jobs / chunks still waiting to be deleted |

Nothing happens until `/regen confirm`. The request remembers the dimension and chunk you were in when you typed it.

From the server console / RCON: `execute in minecraft:the_nether positioned <x> <y> <z> run regen 2`, then `regen confirm`.

## What happens on confirm

1. Force-loaded chunks (`/forceload`) are skipped with a warning.
2. The target chunks are written to `<world>/chunk-regen-pending.txt` (survives a crash/restart).
3. Every player in that dimension within *radius + max(view, simulation distance) + ~12* chunks of the area is teleported
   to world spawn (or, if spawn is too close in the same dimension, far enough away along +X). That distance is needed
   because a player's chunk tickets keep partially-loaded chunks in memory ~11 chunks beyond their view distance.
   If teleport fails they are kicked with a message. Players should stay away until it says **Done** (usually seconds).
4. When the game unloads each chunk (after saving it), the mod writes an **empty entry** for it through Minecraft's own chunk
   IO worker — the same path vanilla uses to clear a chunk from an `.mca` region file. The IO worker keeps only the newest
   write per chunk, so the old chunk can't be written back.
5. The next time the chunk loads there is no data, so it is generated fresh from the world seed. Just go back to the area.

If some chunks are still kept loaded after 60 s (a player came back, a portal/ender-pearl ticket, a `/forceload`ed chunk
within ~12 chunks — you get a warning about those up front, etc.) you get a message. They stay in the pending file and are deleted as soon as they unload, or automatically at the **next server start**
(before any world is loaded, by clearing the chunk's entry in the region file header).

## Caveats — read before using

- **Border seams:** a regenerated chunk is generated from the seed but its neighbours are not, so terrain, caves and
  features can have hard edges where they meet old chunks. **Regen the damaged chunks plus one border chunk around them**
  (i.e. use a radius 1 larger than the damage) so the seam lands somewhere harmless.
- Everything in the chunk is lost: player builds, chests, farms. There is no undo — **back up the world first.**
- Entities (mobs, item frames, armor stands, dropped items) are stored separately in the `entities/` folder and are NOT
  deleted; old ones may reappear in the fresh chunk. POI data (beds, workstations, portals) is not cleared either; vanilla
  re-validates POIs against the real blocks.
- Biomes/structures come from the *current* generator/seed. If the world was created on a much older version, regenerated
  chunks will look like modern terrain.
- Players are not teleported back afterwards.
- Running it from the console with nobody online: vanilla pauses an empty server after `pause-when-empty-seconds`
  (default 60), and nothing unloads while paused — the job resumes when someone joins, or finishes at the next restart.
- This was built and compile-tested; the live delete-and-regenerate path should be tried on a **copy of the world** first.
