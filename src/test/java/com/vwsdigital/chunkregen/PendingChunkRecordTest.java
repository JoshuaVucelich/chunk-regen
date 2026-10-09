package com.vwsdigital.chunkregen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The pending file is what startup regen reads before any world is loaded. */
class PendingChunkRecordTest {
	@Test
	void roundTripsDimensionAndChunkCoordinates() {
		PendingChunkRecord rec = PendingChunkRecord.parse("minecraft:the_nether -12 40");
		assertEquals("minecraft:the_nether", rec.dimensionId);
		assertEquals(-12, rec.x);
		assertEquals(40, rec.z);
		assertEquals("minecraft:the_nether -12 40", rec.line());
	}

	@Test
	void trimsAndCollapsesWhitespace() {
		PendingChunkRecord rec = PendingChunkRecord.parse("  minecraft:overworld   0\t1  ");
		assertEquals("minecraft:overworld", rec.dimensionId);
		assertEquals(0, rec.x);
		assertEquals(1, rec.z);
	}

	@Test
	void skipsLinesThatAreNotThreeFields() {
		assertNull(PendingChunkRecord.parse(""));
		assertNull(PendingChunkRecord.parse("   "));
		assertNull(PendingChunkRecord.parse("minecraft:overworld 1"));
		assertNull(PendingChunkRecord.parse("minecraft:overworld 1 2 extra"));
	}

	@Test
	void badCoordinatesThrowSoTheCallerCanKeepTheLine() {
		assertThrows(NumberFormatException.class, () -> PendingChunkRecord.parse("minecraft:overworld x 2"));
		assertThrows(NumberFormatException.class, () -> PendingChunkRecord.parse("minecraft:overworld 1 z"));
	}
}
