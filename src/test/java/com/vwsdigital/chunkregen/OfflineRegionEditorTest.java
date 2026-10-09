package com.vwsdigital.chunkregen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup regen path does not call the world generator. It clears the Anvil location and timestamp
 * entries the same way vanilla {@code RegionFile#clear} does, so the next load generates the chunk from the seed.
 * That header layout is shared by 1.21.11 and the 26.x line.
 */
class OfflineRegionEditorTest {
	@TempDir
	Path regionDir;

	@Test
	void clearsLocationAndTimestampWithoutTouchingNeighbors() throws IOException {
		int chunkX = 3;
		int chunkZ = 7;
		int neighborX = 4;
		int neighborZ = 7;
		Path region = regionFile(chunkX, chunkZ);
		writeHeader(region);
		int index = index(chunkX, chunkZ);
		int neighbor = index(neighborX, neighborZ);
		writeInt(region, index * 4L, 0x00000201);
		writeInt(region, 4096L + index * 4L, 0xAABBCCDD);
		writeInt(region, neighbor * 4L, 0x11111111);
		writeInt(region, 4096L + neighbor * 4L, 0x22222222);

		assertTrue(OfflineRegionEditor.deleteChunk(regionDir, chunkX, chunkZ));

		assertEquals(0, readInt(region, index * 4L));
		assertEquals(0, readInt(region, 4096L + index * 4L));
		assertEquals(0x11111111, readInt(region, neighbor * 4L));
		assertEquals(0x22222222, readInt(region, 4096L + neighbor * 4L));
		assertTrue(Files.exists(region));
	}

	@Test
	void secondDeleteReportsTheChunkAlreadyAbsent() throws IOException {
		Path region = regionFile(0, 0);
		writeHeader(region);
		writeInt(region, 0, 0x00000201);

		assertTrue(OfflineRegionEditor.deleteChunk(regionDir, 0, 0));
		assertFalse(OfflineRegionEditor.deleteChunk(regionDir, 0, 0));
	}

	@Test
	void negativeChunksUseTheSignedRegionAndLocalIndex() throws IOException {
		int chunkX = -1;
		int chunkZ = -33;
		Path region = regionFile(chunkX, chunkZ);
		assertEquals(regionDir.resolve("r.-1.-2.mca"), region);
		writeHeader(region);
		int index = index(chunkX, chunkZ);
		assertEquals(31 + 31 * 32, index);
		writeInt(region, index * 4L, 0x00000302);

		assertTrue(OfflineRegionEditor.deleteChunk(regionDir, chunkX, chunkZ));
		assertEquals(0, readInt(region, index * 4L));
		assertEquals(0, readInt(region, 4096L + index * 4L));
	}

	@Test
	void chunk32IsADifferentRegionFile() throws IOException {
		Path origin = regionFile(0, 0);
		Path next = regionFile(32, 0);
		writeHeader(origin);
		writeHeader(next);
		writeInt(origin, 0, 0x00000201);
		writeInt(next, 0, 0x00000401);

		assertTrue(OfflineRegionEditor.deleteChunk(regionDir, 32, 0));

		assertEquals(0x00000201, readInt(origin, 0));
		assertEquals(0, readInt(next, 0));
	}

	@Test
	void removesExternalChunkEvenWhenTheRegionIsMissing() throws IOException {
		Path external = regionDir.resolve("c.5.6.mcc");
		Files.write(external, new byte[] {1, 2, 3});

		assertFalse(OfflineRegionEditor.deleteChunk(regionDir, 5, 6));
		assertFalse(Files.exists(external));
	}

	@Test
	void removesExternalChunkAlongsideTheHeaderClear() throws IOException {
		Path region = regionFile(5, 6);
		writeHeader(region);
		int index = index(5, 6);
		writeInt(region, index * 4L, 0x00000201);
		Path external = regionDir.resolve("c.5.6.mcc");
		Files.write(external, new byte[] {9});

		assertTrue(OfflineRegionEditor.deleteChunk(regionDir, 5, 6));
		assertFalse(Files.exists(external));
		assertEquals(0, readInt(region, index * 4L));
	}

	@Test
	void shortOrMissingRegionDoesNotThrow() throws IOException {
		assertFalse(OfflineRegionEditor.deleteChunk(regionDir, 1, 1));
		Path shortFile = regionFile(1, 1);
		Files.write(shortFile, new byte[100]);
		assertFalse(OfflineRegionEditor.deleteChunk(regionDir, 1, 1));
		assertEquals(100, Files.size(shortFile));
	}

	private Path regionFile(int chunkX, int chunkZ) {
		return regionDir.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
	}

	private static int index(int chunkX, int chunkZ) {
		return (chunkX & 31) + (chunkZ & 31) * 32;
	}

	private static void writeHeader(Path region) throws IOException {
		Files.createDirectories(region.getParent());
		Files.write(region, new byte[8192]);
	}

	private static void writeInt(Path file, long offset, int value) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
			raf.seek(offset);
			raf.writeInt(value);
		}
	}

	private static int readInt(Path file, long offset) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			raf.seek(offset);
			return raf.readInt();
		}
	}
}
