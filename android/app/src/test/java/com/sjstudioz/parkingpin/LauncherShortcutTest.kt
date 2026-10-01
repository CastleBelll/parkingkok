package com.sjstudioz.parkingpin

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * docs/02 §17 `주차 위치`. The shortcut lives in XML and its action in Kotlin; a typo in
 * either is a shortcut that silently opens home, so the two spellings are compared here.
 */
class LauncherShortcutTest {

    @Test
    fun `the shortcut sends the action MainActivity listens for`() {
        // Arrange — Gradle runs unit tests from the module directory.
        val xml = File("src/main/res/xml/shortcuts.xml").readText()

        // Assert
        assertTrue(
            "shortcuts.xml must use ACTION_OPEN_ACTIVE_PARKING",
            "android:action=\"$ACTION_OPEN_ACTIVE_PARKING\"" in xml,
        )
        assertTrue("android:targetClass=\"com.sjstudioz.parkingpin.MainActivity\"" in xml)
    }
}
