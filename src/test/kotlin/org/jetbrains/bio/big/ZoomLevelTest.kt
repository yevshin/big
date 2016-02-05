package org.jetbrains.bio.big

import org.jetbrains.bio.Examples
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZoomLevelTest {
    @Test fun testReductionLevel() {
        BigBedFile.read(Examples.get("example1.bb")).use { bbf ->
            assertEquals(bbf.zoomLevels[0].reduction, 3911)
            assertEquals(bbf.zoomLevels[1].reduction, 39110)
        }
    }

    @Test fun testPick() {
        BigBedFile.read(Examples["example1.bb"]).use { bbf ->
            val zoomLevel = bbf.zoomLevels.pick(5000000);
            assertNotNull(zoomLevel);
            assertEquals(zoomLevel!!.reduction, 3911000);

            // desiredReduction <= 1
            assertNull(bbf.zoomLevels.pick(1));

            // diff = desiredReduction - zoomLevel.reductionLevel < 0
            assertNull(bbf.zoomLevels.pick(1000));
        }
    }
}