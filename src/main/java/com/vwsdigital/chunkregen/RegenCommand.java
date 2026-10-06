package com.vwsdigital.chunkregen;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class RegenCommand {
	public static final int MAX_RADIUS = 16;
	private static final long CONFIRM_WINDOW_MS = 60_000;

	private record Request(ResourceKey<Level> dim, ChunkPos center, int radius, long created) {}

	private static final Map<String, Request> REQUESTS = new HashMap<>();

	private RegenCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("regen")
			.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
			.executes(ctx -> request(ctx, 0))
			.then(argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
				.executes(ctx -> request(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))
			.then(literal("confirm").executes(RegenCommand::confirm))
			.then(literal("cancel").executes(RegenCommand::cancel))
			.then(literal("status").executes(RegenCommand::status))
		);
	}

	private static int request(CommandContext<CommandSourceStack> ctx, int radius) throws CommandSyntaxException {
		ServerLevel level = ctx.getSource().getLevel();
		ChunkPos center = new ChunkPos(BlockPos.containing(ctx.getSource().getPosition()).getX() >> 4, BlockPos.containing(ctx.getSource().getPosition()).getZ() >> 4);
		REQUESTS.put(key(ctx.getSource()), new Request(level.dimension(), center, radius, System.currentTimeMillis()));
		int side = radius * 2 + 1;
		int minX = (center.x() - radius) * 16, minZ = (center.z() - radius) * 16;
		int maxX = (center.x() + radius) * 16 + 15, maxZ = (center.z() + radius) * 16 + 15;
		ctx.getSource().sendSuccess(() -> Component.literal("[Chunk Regen] This will DELETE and regenerate from the seed " + side + "x" + side + " = " + (side * side)
			+ " chunk(s) in " + level.dimension().identifier() + ", blocks " + minX + "," + minZ + " to " + maxX + "," + maxZ
			+ ", floor to ceiling. Everything built there is lost. Players nearby will be moved. Type /regen confirm within 60s, or /regen cancel."), false);
		return 1;
	}

	private static int confirm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		Request r = REQUESTS.remove(key(src));
		if (r == null || System.currentTimeMillis() - r.created() > CONFIRM_WINDOW_MS) {
			src.sendFailure(Component.literal("[Chunk Regen] Nothing to confirm (or it expired). Run /regen <radius> first."));
			return 0;
		}
		if (RegenManager.busy()) {
			src.sendFailure(Component.literal("[Chunk Regen] A regen is already running; wait for it to finish (/regen status)."));
			return 0;
		}
		ServerLevel level = src.getServer().getLevel(r.dim());
		if (level == null) {
			src.sendFailure(Component.literal("[Chunk Regen] Dimension " + r.dim().identifier() + " is not loaded."));
			return 0;
		}
		RegenManager.start(src, level, r.center(), r.radius());
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		boolean had = REQUESTS.remove(key(ctx.getSource())) != null;
		ctx.getSource().sendSuccess(() -> Component.literal(had ? "[Chunk Regen] Cancelled." : "[Chunk Regen] Nothing pending."), false);
		return 1;
	}

	/** Players are keyed by UUID; console/RCON/command blocks by their name (use "execute in <dim> positioned <x> <y> <z> run regen ..."). */
	private static String key(CommandSourceStack src) {
		ServerPlayer p = src.getPlayer();
		return p != null ? p.getUUID().toString() : "source:" + src.getTextName();
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.literal("[Chunk Regen] Running jobs: " + RegenManager.jobCount()
			+ ", chunks waiting to be deleted: " + RegenManager.pendingCount()), false);
		return 1;
	}
}
