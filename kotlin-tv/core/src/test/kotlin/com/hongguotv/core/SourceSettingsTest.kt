package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceSettingsTest {
    private fun backup(settings: BackupSettings) = BackupData(emptyList(), emptyList(), emptySet(), emptyList(), settings)

    @Test fun legacyInstallAndBackupKeepChineseSourcesAndDisableOverseas() {
        assertEquals(MediaSource.defaultDisabled, LibraryDisk.decode(emptyMap<String, Any>()).settings.disabledSources)
        val json = JSONObject(LibraryBackup.encode(backup(BackupSettings())))
        json.getJSONObject("settings").remove("disabledSources")
        assertEquals(MediaSource.defaultDisabled, LibraryBackup.decode(json.toString()).settings.disabledSources)
    }

    @Test fun enabledOverseasAndDisabledCurrentSourceSurviveRestoreSafely() {
        val settings = BackupSettings(mediaSource = MediaSource.XIFAN, disabledSources = setOf(MediaSource.XIFAN))
        val restored = LibraryBackup.decode(LibraryBackup.encode(backup(settings))).settings
        assertEquals(MediaSource.HONGGUO, restored.mediaSource)
        assertEquals(setOf(MediaSource.XIFAN), restored.disabledSources)
        val disk = LibraryDisk.decode(mapOf("mediaSource" to "xifan", "disabledSources" to setOf("xifan", "hongguo", "unknown"))).settings
        assertEquals(restored, disk)
        assertTrue(LibraryDisk.decode(mapOf("disabledSources" to emptySet<String>())).settings.disabledSources.isEmpty())
    }

    @Test fun overseasFavoritesAndProgressRoundTripWithoutTruncatingIds() {
        val series = listOf(Series("dramabox:123", "Drama"), Series("reelshort:abc:my-show", "Reel"))
        val progress = BackupProgress(series[1], "reelshort:abc:my-show:1:chapter1", 0, 10, 20, false, 100)
        val data = backup(BackupSettings()).copy(favorites = series, history = listOf(progress))
        assertEquals(data, LibraryBackup.decode(LibraryBackup.encode(data)))
    }
}
