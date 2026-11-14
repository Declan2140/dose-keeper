package app.dosekeeper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiteRotationTest {

    private val sites = listOf("Left thigh", "Right thigh", "Left abdomen", "Right abdomen")

    @Test fun `first site when nothing is logged yet`() {
        assertEquals("Left thigh", SiteRotation.next(sites, null))
    }

    @Test fun `moves on to the site after the last one`() {
        assertEquals("Right thigh", SiteRotation.next(sites, "Left thigh"))
        assertEquals("Right abdomen", SiteRotation.next(sites, "left abdomen "))
    }

    @Test fun `wraps round after the last site`() {
        assertEquals("Left thigh", SiteRotation.next(sites, "Right abdomen"))
    }

    @Test fun `a site that was removed from the list starts over`() {
        assertEquals("Left thigh", SiteRotation.next(sites, "Upper arm"))
    }

    @Test fun `no sites means no suggestion`() {
        assertNull(SiteRotation.next(emptyList(), "Left thigh"))
    }

    @Test fun `one site is always that site`() {
        assertEquals("Left thigh", SiteRotation.next(listOf("Left thigh"), "Left thigh"))
    }

    @Test fun `stored text round trips and drops blanks and repeats`() {
        val parsed = SiteRotation.parse(" Left thigh \n\nRight thigh\nleft thigh\n")
        assertEquals(listOf("Left thigh", "Right thigh"), parsed)
        assertEquals("Left thigh\nRight thigh", SiteRotation.format(parsed))
        assertNull(SiteRotation.format(listOf(" ", "")))
        assertEquals(emptyList(), SiteRotation.parse(null))
    }
}
