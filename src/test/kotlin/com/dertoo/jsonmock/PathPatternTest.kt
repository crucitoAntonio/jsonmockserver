package com.dertoo.jsonmock

import com.dertoo.jsonmock.model.MockRule
import com.dertoo.jsonmock.model.PathPattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathPatternTest {

    @Test
    fun exactMatchIgnoresQueryAndTrailingSlash() {
        assertTrue(PathPattern.matches("/v1/usuario", "/v1/usuario"))
        assertTrue(PathPattern.matches("/v1/usuario", "/v1/usuario/?page=2"))
        assertTrue(PathPattern.matches("v1/usuario", "/v1/usuario"))
        assertFalse(PathPattern.matches("/v1/usuario", "/v1/usuarios"))
        assertFalse(PathPattern.matches("/v1/usuario", "/v1/usuario/1"))
    }

    @Test
    fun placeholdersMatchOneSegment() {
        assertTrue(PathPattern.matches("/v1/usuario/{id}", "/v1/usuario/42"))
        assertTrue(PathPattern.matches("/v1/*/perfil", "/v1/abc/perfil"))
        assertFalse(PathPattern.matches("/v1/usuario/{id}", "/v1/usuario"))
        assertFalse(PathPattern.matches("/v1/usuario/{id}", "/v1/usuario/42/x"))
    }

    @Test
    fun doubleWildcardMatchesAnyDepth() {
        assertTrue(PathPattern.matches("/v1/**", "/v1"))
        assertTrue(PathPattern.matches("/v1/**", "/v1/a/b/c"))
        assertTrue(PathPattern.matches("/**/perfil", "/v1/usuario/1/perfil"))
        assertFalse(PathPattern.matches("/v1/**", "/v2/a"))
    }

    @Test
    fun fullUrlUsesOnlyPath() {
        assertTrue(PathPattern.matches("https://api.miapp.com/v1/usuario", "/v1/usuario"))
    }

    @Test
    fun ruleChecksMethod() {
        val rule = MockRule().apply { method = "POST"; path = "/login" }
        assertTrue(rule.matches("post", "/login"))
        assertFalse(rule.matches("GET", "/login"))
        rule.method = MockRule.ANY
        assertTrue(rule.matches("GET", "/login"))
    }
}
