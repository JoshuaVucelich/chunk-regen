package com.vwsdigital.chunkregen;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deletes a chunk from an Anvil region file (r.X.Z.mca) while the world is NOT loaded, exactly like vanilla's
 * RegionFile#clear: zero the chunk's 4-byte location entry and 4-byte timestamp entry in the 8 KiB header.
 * The sectors become free space; the game sees "no chunk here" and generates it from the seed.
 * Also removes an oversized-chunk side file (c.X.Z.mcc) if present.
 */
public final class OfflineRegionEditor {
	private OfflineRegionEditor() {}

	/** @return true if the chunk existed in the file and was removed. */
	public static boolean deleteChunk(Path regionDir, int chunkX, int chunkZ) throws IOException {
		Path region = regionDir.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
		Files.deleteIfExists(regionDir.resolve("c." + chunkX + "." + chunkZ + ".mcc"));
		if (!Files.exists(region) || Files.size(region) < 8192) return false;
		int index = (chunkX & 31) + (chunkZ & 31) * 32;
		try (RandomAccessFile raf = new RandomAccessFile(region.toFile(), "rw")) {
			raf.seek(index * 4L);
			int location = raf.readInt();
			raf.seek(index * 4L);
			raf.writeInt(0);
			raf.seek(4096L + index * 4L);
			raf.writeInt(0);
			raf.getFD().sync();
			return location != 0;
		}
	}
}
