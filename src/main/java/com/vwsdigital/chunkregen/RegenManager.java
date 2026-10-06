package com.vwsdigital.chunkregen;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a chunk is regenerated while the server runs:
 * <ol>
 *   <li>The chunk is put on a persisted "pending" list (world/chunk-regen-pending.txt).</li>
 *   <li>Every player that is in, or has view of, a target chunk is teleported away so its tickets drop.</li>
 *   <li>When the game unloads the chunk (it saves it first), at the end of that tick we write an EMPTY entry for it through
 *       the vanilla chunk IO worker (ChunkMap#write(pos, IOWorker.STORE_EMPTY) -> RegionFile#clear). That removes the chunk
 *       from the .mca region file, and any queued save of the old chunk is superseded because the IO worker keeps only the
 *       newest write per chunk.</li>
 *   <li>The next time anything loads that chunk, there is no data on disk, so the normal generator recreates it from the seed.</li>
 * </ol>
 * Chunks that do not unload within the timeout (forced/kept loaded) stay on the pending list: they are deleted when they
 * eventually unload, or offline at the next server start before any world loads.
 */
public final class RegenManager {
	public static final int TIMEOUT_TICKS = 20 * 60;       // 60 s
	private static final int REDELETE_WINDOW_TICKS = 20 * 30;
	private static final String PENDING_FILE = "chunk-regen-pending.txt";

	record Key(ResourceKey<Level> dim, int x, int z) {
		ChunkPos pos() { return new ChunkPos(x, z); }
		String line() { return dim.identifier() + " " + x + " " + z; }
	}

	static final class Job {
		final CommandSourceStack source;
		final ResourceKey<Level> dim;
		final List<Key> targets;
		final long startTick;
		final Set<Key> deleted = new HashSet<>();
		boolean timedOutReported = false;
		Job(CommandSourceStack source, ResourceKey<Level> dim, List<Key> targets, long startTick) {
			this.source = source; this.dim = dim; this.targets = targets; this.startTick = startTick;
		}
	}

	private static final Map<Key, Boolean> PENDING = new LinkedHashMap<>(); // value: delete write in flight
	private static final Set<Key> TO_DELETE = new LinkedHashSet<>();
	private static final Map<Key, Long> RECENTLY_DELETED = new HashMap<>();
	private static final List<Job> JOBS = new ArrayList<>();
	private static long tick = 0;

	private RegenManager() {}

	public static boolean busy() { return !JOBS.isEmpty(); }
	public static int pendingCount() { return PENDING.size(); }
	public static int jobCount() { return JOBS.size(); }

	// ------------------------------------------------------------------ startup (offline) deletion

	public static void onServerStarting(MinecraftServer server) {
		reset();
		Path root = server.getWorldPath(LevelResource.ROOT);
		Path file = root.resolve(PENDING_FILE);
		if (!Files.exists(file)) return;
		int done = 0, missing = 0, failed = 0;
		List<String> keep = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(file)) {
				String[] p = line.trim().split("\\s+");
				if (p.length != 3) continue;
				try {
					ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(p[0]));
					int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
					Path regionDir = DimensionType.getStorageFolder(dim, root).resolve("region");
					if (OfflineRegionEditor.deleteChunk(regionDir, x, z)) done++; else missing++;
				} catch (Exception e) {
					failed++;
					keep.add(line);
					ChunkRegen.LOGGER.error("[chunk-regen] Could not delete pending chunk '{}' at startup", line, e);
				}
			}
			if (keep.isEmpty()) Files.deleteIfExists(file); else Files.write(file, keep);
			ChunkRegen.LOGGER.info("[chunk-regen] Startup: removed {} pending chunk(s) from region files ({} already absent, {} failed). They will regenerate from the seed.",
				done, missing, failed);
		} catch (IOException e) {
			ChunkRegen.LOGGER.error("[chunk-regen] Could not read {}", file, e);
		}
	}

	public static void reset() {
		PENDING.clear();
		TO_DELETE.clear();
		RECENTLY_DELETED.clear();
		JOBS.clear();
	}

	// ------------------------------------------------------------------ live regen

	public static void start(CommandSourceStack source, ServerLevel level, ChunkPos center, int radius) {
		MinecraftServer server = level.getServer();
		ResourceKey<Level> dim = level.dimension();
		List<Key> targets = new ArrayList<>();
		int forced = 0;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				ChunkPos cp = new ChunkPos(center.x() + dx, center.z() + dz);
				if (level.getForceLoadedChunks().contains(cp.pack())) { forced++; continue; }
				targets.add(new Key(dim, cp.x(), cp.z()));
			}
		}
		if (forced > 0) {
			int f = forced;
			source.sendFailure(Component.literal("[Chunk Regen] Skipping " + f + " force-loaded chunk(s). Run /forceload remove on them first if you want them regenerated."));
		}
		if (targets.isEmpty()) return;
		List<ChunkPos> blocking = blockingForcedChunks(level, center, radius);
		if (!blocking.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(5, blocking.size()); i++) sb.append(i == 0 ? "" : ", ").append(blocking.get(i).x()).append(",").append(blocking.get(i).z());
			source.sendFailure(Component.literal("[Chunk Regen] Warning: " + blocking.size() + " force-loaded chunk(s) nearby (chunk " + sb
				+ (blocking.size() > 5 ? ", ..." : "") + ") will keep some target chunks in memory, so they can't be deleted until that forceload is removed or the server restarts."));
		}

		for (Key k : targets) PENDING.putIfAbsent(k, Boolean.FALSE);
		persist(server);

		int moved = movePlayers(level, center, radius, targets);
		Job job = new Job(source, dim, targets, tick);
		JOBS.add(job);
		source.sendSuccess(() -> Component.literal("[Chunk Regen] Regenerating " + targets.size() + " chunk(s) in " + dim.identifier()
			+ " around chunk " + center.x() + "," + center.z() + ". Moved " + moved + " player(s) within " + (radius + clearance(server))
			+ " chunks away. Stay away until it says Done (usually a few seconds)."), true);
		ChunkRegen.LOGGER.info("[chunk-regen] Job started: {} chunk(s) in {} radius {} around {},{}", targets.size(), dim.identifier(), radius, center.x(), center.z());
	}

	/**
	 * How far (in chunks) from the edge of the target area a ticket must be so it no longer keeps a chunk holder alive.
	 * A ticket keeps holders (partially-loaded chunks) alive up to ChunkLevel.RADIUS_AROUND_FULL_CHUNK chunks beyond the
	 * fully loaded area, and a player's tickets reach view distance (or simulation distance) around them.
	 */
	static int clearance(MinecraftServer server) {
		int view = Math.max(server.getPlayerList().getViewDistance(), server.getPlayerList().getSimulationDistance());
		return view + ChunkLevel.RADIUS_AROUND_FULL_CHUNK + 2;
	}

	private static boolean within(ChunkPos a, ChunkPos center, int dist) {
		return Math.abs(a.x() - center.x()) <= dist && Math.abs(a.z() - center.z()) <= dist;
	}

	/** Force-loaded chunks close enough to keep the target chunks in memory. */
	static List<ChunkPos> blockingForcedChunks(ServerLevel level, ChunkPos center, int radius) {
		List<ChunkPos> out = new ArrayList<>();
		int reach = radius + ChunkLevel.RADIUS_AROUND_FULL_CHUNK + 2;
		for (long packed : level.getForceLoadedChunks()) {
			ChunkPos cp = ChunkPos.unpack(packed);
			if (within(cp, center, radius)) continue; // the target itself; reported separately
			if (within(cp, center, reach)) out.add(cp);
		}
		return out;
	}

	private static int movePlayers(ServerLevel level, ChunkPos center, int radius, List<Key> targets) {
		MinecraftServer server = level.getServer();
		ChunkMap chunkMap = level.getChunkSource().chunkMap;
		int safeChunks = radius + clearance(server);
		Set<ServerPlayer> affected = new LinkedHashSet<>();
		for (Key k : targets) affected.addAll(chunkMap.getPlayers(k.pos(), false));
		for (ServerPlayer p : level.players()) {
			if (within(p.chunkPosition(), center, safeChunks)) affected.add(p);
		}
		if (affected.isEmpty()) return 0;

		LevelData.RespawnData rd = server.overworld().getRespawnData();
		ServerLevel destLevel = server.getLevel(rd.dimension());
		if (destLevel == null) destLevel = server.overworld();
		BlockPos spawn = rd.pos();
		BlockPos dest;
		ChunkPos spawnChunk = new ChunkPos(spawn.getX() >> 4, spawn.getZ() >> 4);
		if (destLevel != level || !within(spawnChunk, center, safeChunks + 1)) {
			int y = Math.max(spawn.getY(), destLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ()));
			dest = new BlockPos(spawn.getX(), y, spawn.getZ());
		} else {
			destLevel = level;
			int x = center.getMiddleBlockX() + (safeChunks + 2) * 16;
			int z = center.getMiddleBlockZ();
			dest = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
		}

		int moved = 0;
		for (ServerPlayer p : affected) {
			boolean ok = false;
			try {
				ok = p.teleportTo(destLevel, dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, Set.of(), p.getYRot(), p.getXRot(), true);
			} catch (Throwable t) {
				ChunkRegen.LOGGER.warn("[chunk-regen] Teleport of {} failed", p.getScoreboardName(), t);
			}
			if (ok) {
				moved++;
				p.sendSystemMessage(Component.literal("[Chunk Regen] You were moved to " + dest.getX() + " " + dest.getY() + " " + dest.getZ()
					+ " in " + destLevel.dimension().identifier() + " because the chunks near you are being regenerated."));
			} else {
				p.connection.disconnect(Component.literal("Chunks near you are being regenerated. Please rejoin in a minute."));
				moved++;
			}
		}
		return moved;
	}

	public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
		if (PENDING.isEmpty() && RECENTLY_DELETED.isEmpty()) return;
		ChunkPos cp = chunk.getPos();
		Key k = new Key(level.dimension(), cp.x(), cp.z());
		// Fabric fires this just BEFORE vanilla queues the chunk's final save, so the actual delete happens at end of tick.
		if (PENDING.containsKey(k) || RECENTLY_DELETED.containsKey(k)) TO_DELETE.add(k);
	}

	public static void tick(MinecraftServer server) {
		tick++;
		if (PENDING.isEmpty() && TO_DELETE.isEmpty() && JOBS.isEmpty() && RECENTLY_DELETED.isEmpty()) return;

		// Every second, catch pending chunks that are simply not loaded (never loaded, or unloaded before we started watching).
		if (tick % 20 == 0) {
			for (Map.Entry<Key, Boolean> e : PENDING.entrySet()) {
				if (e.getValue()) continue;
				ServerLevel level = server.getLevel(e.getKey().dim());
				if (level != null && level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(e.getKey().pos().pack()) == null) TO_DELETE.add(e.getKey());
			}
		}

		if (!TO_DELETE.isEmpty()) {
			Iterator<Key> it = TO_DELETE.iterator();
			while (it.hasNext()) {
				Key k = it.next();
				it.remove();
				ServerLevel level = server.getLevel(k.dim());
				if (level == null) continue;
				ChunkMap chunkMap = level.getChunkSource().chunkMap;
				if (chunkMap.getUpdatingChunkIfPresent(k.pos().pack()) != null) continue; // reloaded again; wait for next unload
				if (PENDING.containsKey(k)) PENDING.put(k, Boolean.TRUE);
				chunkMap.write(k.pos(), IOWorker.STORE_EMPTY).whenComplete((v, err) -> server.execute(() -> {
					if (err != null) {
						ChunkRegen.LOGGER.error("[chunk-regen] Failed to delete chunk {} {} in {}", k.x(), k.z(), k.dim().identifier(), err);
						if (PENDING.containsKey(k)) PENDING.put(k, Boolean.FALSE);
						return;
					}
					boolean wasPending = PENDING.remove(k) != null;
					RECENTLY_DELETED.put(k, tick + REDELETE_WINDOW_TICKS);
					for (Job j : JOBS) if (j.targets.contains(k)) j.deleted.add(k);
					if (wasPending) persist(server);
				}));
			}
		}

		RECENTLY_DELETED.values().removeIf(expiry -> expiry < tick);

		Iterator<Job> jit = JOBS.iterator();
		while (jit.hasNext()) {
			Job j = jit.next();
			if (j.deleted.size() >= j.targets.size()) {
				jit.remove();
				j.source.sendSuccess(() -> Component.literal("[Chunk Regen] Done: " + j.targets.size()
					+ " chunk(s) deleted from the region file. They regenerate from the seed (floor to ceiling) the next time they load - just walk/fly back."), true);
				ChunkRegen.LOGGER.info("[chunk-regen] Job finished: {} chunk(s) deleted in {}", j.targets.size(), j.dim.identifier());
			} else if (!j.timedOutReported && tick - j.startTick > TIMEOUT_TICKS) {
				j.timedOutReported = true;
				jit.remove();
				int left = j.targets.size() - j.deleted.size();
				j.source.sendFailure(Component.literal("[Chunk Regen] " + j.deleted.size() + "/" + j.targets.size()
					+ " chunk(s) deleted; " + left + " are still kept loaded (nearby player, portal/pearl ticket, etc.). They stay queued in "
					+ PENDING_FILE + " and will be deleted as soon as they unload, or automatically at the next server start."));
				ChunkRegen.LOGGER.warn("[chunk-regen] Job timed out with {} chunk(s) still loaded; left pending.", left);
			}
		}
	}

	private static void persist(MinecraftServer server) {
		Path file = server.getWorldPath(LevelResource.ROOT).resolve(PENDING_FILE);
		try {
			if (PENDING.isEmpty()) {
				Files.deleteIfExists(file);
			} else {
				List<String> lines = new ArrayList<>();
				for (Key k : PENDING.keySet()) lines.add(k.line());
				Files.write(file, lines);
			}
		} catch (IOException e) {
			ChunkRegen.LOGGER.error("[chunk-regen] Could not write {}", file, e);
		}
	}
}
