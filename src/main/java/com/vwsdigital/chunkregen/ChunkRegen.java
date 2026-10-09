package com.vwsdigital.chunkregen;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = ChunkRegen.MOD_ID, dist = Dist.DEDICATED_SERVER)
public class ChunkRegen {
	public static final String MOD_ID = "chunk-regen";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public ChunkRegen() {
		// ServerAboutToStartEvent runs before worlds load, matching Fabric SERVER_STARTING (just before initServer).
		// Pending chunks must be cleared from the region files before the first load.
		// ChunkEvent.Unload runs after the chunk save. RegenManager writes the empty entry at end of tick,
		// so that write is newer than the save and the region slot stays clear.
		NeoForge.EVENT_BUS.addListener(ChunkRegen::registerCommands);
		NeoForge.EVENT_BUS.addListener(ChunkRegen::onAboutToStart);
		NeoForge.EVENT_BUS.addListener(ChunkRegen::onChunkUnload);
		NeoForge.EVENT_BUS.addListener(ChunkRegen::onServerTick);
		NeoForge.EVENT_BUS.addListener(ChunkRegen::onStopped);

		LOGGER.info("[{}] Loaded. Commands: /regen [radius] then /regen confirm | /regen cancel | /regen status", MOD_ID);
	}

	private static void registerCommands(RegisterCommandsEvent event) {
		RegenCommand.register(event.getDispatcher());
	}

	private static void onAboutToStart(ServerAboutToStartEvent event) {
		RegenManager.onServerStarting(event.getServer());
	}

	private static void onChunkUnload(ChunkEvent.Unload event) {
		if (!(event.getLevel() instanceof ServerLevel level)) return;
		RegenManager.onChunkUnload(level, event.getChunk());
	}

	private static void onServerTick(ServerTickEvent.Post event) {
		RegenManager.tick(event.getServer());
	}

	private static void onStopped(ServerStoppedEvent event) {
		RegenManager.reset();
	}
}
