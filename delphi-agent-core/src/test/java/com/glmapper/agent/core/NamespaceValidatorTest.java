package com.glmapper.agent.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class NamespaceValidatorTest {

    @Test
    void defaultNamespaceIsAlwaysValid() {
        assertDoesNotThrow(() -> NamespaceValidator.validate("default"));
        assertDoesNotThrow(() -> NamespaceValidator.validate(AgentConstants.DEFAULT_NAMESPACE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc",            // minimum length
            "my-tenant",
            "tenant-123",
            "a-b-c",
            "abcdefghijklmnopqrstuvwxyz0123456789",
    })
    void validNamespacesAccepted(String namespace) {
        assertDoesNotThrow(() -> NamespaceValidator.validate(namespace));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "AB",             // uppercase
            "My-Tenant",      // mixed case
            "1tenant",        // starts with digit
            "-tenant",        // starts with hyphen
            "ab",             // too short (2 chars)
            "a",              // too short (1 char)
            "tenant_name",    // underscore not allowed
            "tenant.name",    // dot not allowed
            "tenant/name",    // slash not allowed
            "tenant\\name",   // backslash not allowed
            "tenant name",    // space not allowed
    })
    void invalidNamespacesRejected(String namespace) {
        assertThrows(IllegalArgumentException.class, () -> NamespaceValidator.validate(namespace));
    }

    @Test
    void nullNamespaceRejected() {
        assertThrows(IllegalArgumentException.class, () -> NamespaceValidator.validate(null));
    }

    @Test
    void blankNamespaceRejected() {
        assertThrows(IllegalArgumentException.class, () -> NamespaceValidator.validate("   "));
    }

    @Test
    void emptyNamespaceRejected() {
        assertThrows(IllegalArgumentException.class, () -> NamespaceValidator.validate(""));
    }

    @Test
    void tooLongNamespaceRejected() {
        // 65 chars (1 start + 64 more = too long)
        String longNamespace = "a" + "b".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> NamespaceValidator.validate(longNamespace));
    }

    @Test
    void exactly64CharsIsValid() {
        // 64 chars total: 1 start letter + 63 more
        String namespace = "a" + "b".repeat(63);
        assertEquals(64, namespace.length());
        assertDoesNotThrow(() -> NamespaceValidator.validate(namespace));
    }
}
