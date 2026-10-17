package com.dertoo.jsonmock

import com.dertoo.jsonmock.model.JsonCheck
import com.dertoo.jsonmock.ui.MockFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonCheckTest {

    @Test
    fun validDocuments() {
        listOf(
            """{}""", """[]""", """{"a":1}""", """{"promos":[{"id":1,"titulo":"2x1","activa":true,"extra":null}]}""",
            """  [1, -2.5, 3e10, 0.1E-2, "x\"y", "á"]  """, "\"solo texto\"", "42",
            "{\n  \"nested\": {\"a\": [ {}, [] ]}\n}",
        ).forEach { assertNull(it, JsonCheck.validate(it)) }
    }

    @Test
    fun invalidDocumentsReportPosition() {
        val e = JsonCheck.validate("{\n  \"a\": 1,\n  \"b\" 2\n}")
        assertNotNull(e)
        assertEquals(3, e!!.line)
        assertEquals(7, e.column)
        assertTrue(e.message.contains("':'"))

        listOf("""{"a":1,}""", """{a:1}""", """[1 2]""", """{"a":tru}""", """{"a":"sin cerrar}""", """01""", """{} x""", "")
            .forEach { assertNotNull(it, JsonCheck.validate(it)) }
        assertFalse(JsonCheck.isValid("   "))
    }

    @Test
    fun fileNames() {
        assertEquals("GET_v1_usuario_id", MockFiles.suggestName("GET", "/v1/usuario/{id}"))
        assertEquals("ANY_root", MockFiles.suggestName("ANY", "/"))
        assertEquals("promos", MockFiles.sanitize("promos.json"))
        assertEquals("promos_navidad", MockFiles.sanitize(" promos navidad "))
        assertEquals("", MockFiles.sanitize("///"))
    }
}
