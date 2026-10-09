package com.vwsdigital.chunkregen;

/**
 * One line of {@code <world>/chunk-regen-pending.txt}: {@code <dimension id> <chunkX> <chunkZ>}.
 * The dimension id is stored as text so this format can be checked without booting the game.
 * {@link Integer#parseInt} failures propagate; the caller keeps that line for the next start.
 */
final class PendingChunkRecord {
	final String dimensionId;
	final int x;
	final int z;

	PendingChunkRecord(String dimensionId, int x, int z) {
		this.dimensionId = dimensionId;
		this.x = x;
		this.z = z;
	}

	/** @return null when the line does not have exactly three fields */
	static PendingChunkRecord parse(String line) {
		String[] p = line.trim().split("\\s+");
		if (p.length != 3) return null;
		return new PendingChunkRecord(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]));
	}

	String line() {
		return dimensionId + " " + x + " " + z;
	}
}
