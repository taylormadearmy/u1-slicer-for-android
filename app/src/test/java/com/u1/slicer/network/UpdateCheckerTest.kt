package com.u1.slicer.network

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {

    // --- parseLatestRelease: extracts version from GitHub API JSON ---

    @Test
    fun `parseLatestRelease extracts tag_name without v prefix`() {
        val json = """{"tag_name":"v1.5.49","assets":[{"name":"u1-slicer-v1.5.49.apk","browser_download_url":"https://github.com/download/u1-slicer-v1.5.49.apk"}]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("1.5.49", result?.version)
    }

    @Test
    fun `parseLatestRelease handles tag without v prefix`() {
        val json = """{"tag_name":"1.5.49","assets":[{"name":"app.apk","browser_download_url":"https://example.com/app.apk"}]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("1.5.49", result?.version)
    }

    @Test
    fun `parseLatestRelease returns null for malformed JSON`() {
        assertNull(UpdateChecker.parseLatestRelease("not json"))
    }

    @Test
    fun `parseLatestRelease returns null for missing tag_name`() {
        val json = """{"assets":[]}"""
        assertNull(UpdateChecker.parseLatestRelease(json))
    }

    @Test
    fun `parseLatestRelease extracts first APK download URL from assets`() {
        val json = """{"tag_name":"v1.5.49","html_url":"https://github.com/taylormadearmy/u1-slicer-for-android/releases/tag/v1.5.49","assets":[
            {"name":"u1-slicer-v1.5.49.apk","browser_download_url":"https://github.com/download/u1-slicer-v1.5.49.apk"},
            {"name":"source.zip","browser_download_url":"https://github.com/download/source.zip"}
        ]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("https://github.com/download/u1-slicer-v1.5.49.apk", result?.downloadUrl)
    }

    @Test
    fun `parseLatestRelease extracts html_url as releaseUrl`() {
        val json = """{"tag_name":"v1.5.49","html_url":"https://github.com/taylormadearmy/u1-slicer-for-android/releases/tag/v1.5.49","assets":[
            {"name":"u1-slicer-v1.5.49.apk","browser_download_url":"https://github.com/download/u1-slicer-v1.5.49.apk"}
        ]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("https://github.com/taylormadearmy/u1-slicer-for-android/releases/tag/v1.5.49", result?.releaseUrl)
    }

    @Test
    fun `parseLatestRelease falls back to release page when no APK asset`() {
        val json = """{"tag_name":"v1.5.49","html_url":"https://github.com/taylormadearmy/u1-slicer-for-android/releases/tag/v1.5.49","assets":[]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("https://github.com/taylormadearmy/u1-slicer-for-android/releases/tag/v1.5.49", result?.downloadUrl)
    }

    // --- isNewer: semantic version comparison ---

    @Test
    fun `isNewer returns true when remote patch is higher`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.5.49", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns true when remote minor is higher`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.6.0", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns true when remote major is higher`() {
        assertTrue(UpdateChecker.isNewer(remote = "2.0.0", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns false when versions are equal`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.5.48", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns false when current is newer`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.5.47", current = "1.5.48"))
    }

    @Test
    fun `isNewer handles different segment counts gracefully`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.6", current = "1.5.48"))
        assertFalse(UpdateChecker.isNewer(remote = "1.5", current = "1.5.48"))
    }
}
