package dev.rigspecs.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplatesTest {
    @Test
    fun everyTypeHasTheBasics() {
        VehicleType.entries.forEach { type ->
            val items = DefaultTemplates.forType(type)
            val components = items.map { it.component }.toSet()
            assertTrue("Engine oil" in components, "$type has engine oil")
            assertTrue("Lug nuts" in components, "$type has lug nuts")
            assertTrue(items.any { it.kind == TemplateItem.Kind.PART && it.component == "Oil filter" }, "$type has an oil filter")
            assertTrue(items.filter { it.kind == TemplateItem.Kind.SPEC }.all { it.category != null && it.name.isNotBlank() })
            assertTrue(items.filter { it.kind == TemplateItem.Kind.PART }.all { it.system != null })
            assertEquals(items.size, items.map { it.kind to it.component + "/" + it.name }.toSet().size, "$type has no duplicates")
        }
    }

    @Test
    fun lugNutTorqueIsSafetyCritical() {
        val lug = DefaultTemplates.forType(VehicleType.AMBULANCE).first { it.component == "Lug nuts" && it.name == "Torque" }
        assertTrue(lug.safetyCritical)
        assertEquals("ft-lb", lug.unit)
    }

    @Test
    fun apparatusExtras() {
        val ladder = DefaultTemplates.forType(VehicleType.LADDER).map { it.component }
        assertTrue("Aerial hydraulic oil" in ladder && "Turntable bolts" in ladder && "Pump gearbox oil" in ladder)
        assertTrue("Aerial hydraulic oil" !in DefaultTemplates.forType(VehicleType.ENGINE).map { it.component })
        assertTrue("DEF" in DefaultTemplates.forType(VehicleType.ENGINE).map { it.component })
    }

    @Test
    fun jsonRoundTripAndTolerance() {
        val items = DefaultTemplates.forType(VehicleType.ENGINE)
        assertEquals(items, TemplateJson.decode(TemplateJson.encode(items)))
        assertEquals(emptyList(), TemplateJson.decode("garbage"))
        // Unknown fields from a newer app version are ignored, missing ones default.
        val newer = """[{"kind":"PART","component":"Oil filter","system":"ENGINE","futureField":1}]"""
        assertEquals(listOf(TemplateItem(TemplateItem.Kind.PART, "Oil filter", system = PartSystem.ENGINE)), TemplateJson.decode(newer))
    }
}
