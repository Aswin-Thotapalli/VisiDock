package com.thotapalli.visidock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CardImageClientTest {
    @Test fun rejectsInsecureOrAmbiguousServiceAddresses() {
        listOf("", "http://images.example.com", "https://user:password@images.example.com", "https://images.example.com?token=secret", "https://images.example.com#fragment", "https:/broken").forEach { value ->
            assertFalse("Accepted unsafe address: $value", runCatching { imageApiBase(value) }.isSuccess)
        }
    }

    @Test fun endpointsKeepCardIdentifiersWithinTheirOwnPath() {
        assertEquals("https://images.example.com/v1/cards/card-123/preview.jpg", imageApiEndpoint("https://images.example.com/", "card-123", "preview.jpg").toString())
        listOf("../other", "a/b", "a?b", "a#b", "", "a%2Fb").forEach { id ->
            assertFalse(runCatching { imageApiEndpoint("https://images.example.com", id, "original") }.isSuccess)
        }
        assertFalse(runCatching { imageApiEndpoint("https://images.example.com", "card-123", "../original") }.isSuccess)
    }

    @Test fun serviceFailuresAreActionableWithoutReflectingServerContent() {
        assertEquals("Your image service session expired. Sign in again.", imageApiError(401))
        assertEquals("Choose an image smaller than 20 MB.", imageApiError(413))
        assertEquals("This card image is no longer available.", imageApiError(404))
        assertEquals("The image service is temporarily unavailable. Your draft is still here; try again shortly.", imageApiError(503))
    }
}
