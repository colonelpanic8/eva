package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.InvocationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DawarichPackageTest {
    private val json =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "docs/examples/dawarich.json") }
            .first { it.isFile }
            .readText()
    private val definition = PackageCodec.decode(json)

    @Test
    fun `bearer is explicit validated and participates in the digest`() {
        assertTrue(definition.httpBindings().all { it.credential == "dawarich" && it.credentialScheme == "bearer" })
        val basic = PackageCodec.decode(json.replace("\"credentialScheme\": \"bearer\",", ""))
        assertTrue(basic.httpBindings().all { it.credentialScheme == "basic" })
        assertNotEquals(definition.digest, basic.digest)
        assertThrows(IllegalArgumentException::class.java) { PackageCodec.decode(json.replace("\"bearer\"", "\"oauth\"")) }
        assertThrows(IllegalArgumentException::class.java) { PackageCodec.decode(json.replace("\"credential\": \"dawarich\",", "")) }
        assertThrows(IllegalArgumentException::class.java) { PackageCodec.decode(json.replaceFirst("\"bearer\"", "\"basic\"")) }
    }

    @Test
    fun `bounded requests encode timezone and require geographic and time bounds`() {
        val times = mapOf("start_at" to "2019-03-01T00:00:00-08:00", "end_at" to "2019-04-01T00:00:00-07:00")
        definition.capabilities.forEach { cap ->
            val args = times.filterKeys { it in cap.inputSchema.getValue("properties").toString() }.toMutableMap()
            if (cap.name.endsWith("in_area")) {
                args.putAll(
                    mapOf(
                        "sw_lat" to "37.7",
                        "sw_lng" to "-122.5",
                        "ne_lat" to "37.8",
                        "ne_lng" to "-122.4",
                    ),
                )
            }
            val binding = cap.binding as DeclarativeBinding.Http
            val request = BindingArguments(cap, args).http(binding)
            assertEquals("bearer", request.credentialScheme)
            assertEquals("GET", request.method)
            assertTrue(request.url.contains("per_page=20"))
            assertTrue(request.url.contains("page=1"))
            assertFalse(request.url.contains("api_key"))
            if (cap.name != "places") assertTrue(request.url.contains("start_at=2019-03-01T00%3A00%3A00-08%3A00"))
            if (cap.name.endsWith("in_area")) assertThrows(Exception::class.java) { BindingArguments(cap, times).http(binding) }
        }
    }

    @Test
    fun `v1_14 serializers project points visits nullable places and page local name filters`() {
        fun result(
            name: String,
            body: String,
            args: Map<String, String> = emptyMap(),
        ) = BindingResults.http(definition.capabilities.single { it.name == name }, HttpResponse(200, body), args)
        val point = result("points", """[{"id":1,"timestamp":1551400000,"latitude":"37.7","longitude":"-122.4","country_name":null}]""")
        assertEquals(InvocationStatus.COMPLETED, point.status)
        assertTrue(point.message.contains("timestamp=1551400000"))
        val visit =
            result(
                "visits",
                """[{"id":2,"name":"Cafe","started_at":"2019-03-01T12:00:00Z","ended_at":null,"status":"suggested",
                  "place":{"id":null,"latitude":null,"longitude":null}}]""",
            )
        assertTrue(visit.message.contains("status=\"suggested\""))
        assertTrue(visit.message.contains("place_id=null"))
        val places = """[{"id":3,"name":"Cafe","latitude":37.7,"longitude":-122.4,"visits_count":2},
            {"id":4,"name":"Park","latitude":37.8,"longitude":-122.5,"visits_count":1}]"""
        val filtered = result("places", places, mapOf("q" to "cAF"))
        assertTrue(filtered.message.contains("Cafe"))
        assertFalse(filtered.message.contains("Park"))
        assertEquals(InvocationStatus.COMPLETED, result("visits", "[]").status)
    }
}
