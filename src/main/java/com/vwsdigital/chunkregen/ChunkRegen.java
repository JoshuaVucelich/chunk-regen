package com.vwsdigital.chunkregen;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChunkRegen implements ModInitializer {
	public static final String MOD_ID = "chunk-regen";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			RegenCommand.register(dispatcher));

		// Before any world is loaded: apply regens that could not finish while the server was running.
		ServerLifecycleEvents.SERVER_STARTING.register(RegenManager::onServerStarting);
		ServerChunkEvents.CHUNK_UNLOAD.register(RegenManager::onChunkUnload);
		ServerTickEvents.END_SERVER_TICK.register(RegenManager::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> RegenManager.reset());

		LOGGER.info("[{}] Loaded. Commands: /regen [radius] then /regen confirm | /regen cancel | /regen status", MOD_ID);
	}
}
