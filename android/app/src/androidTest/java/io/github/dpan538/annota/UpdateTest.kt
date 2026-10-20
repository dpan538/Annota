package io.github.dpan538.annota

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture() =
        File(context.cacheDir, "update-test.apk").also {
            Assume.assumeTrue("Host must stage the signed versionCode=3 fixture", it.exists())
        }

    @Suppress("DEPRECATION")
    private fun certificate(): String {
        val bytes =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo!!
                .apkContentsSigners[0]
                .toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun info(file: File) =
        Network.Update(3, "0.1.2-update-fixture", "", "", sha256(file), certificate())

    @Test
    fun cryptographicSignatureHashPackageAndVersion() {
        val file = fixture()
        Network(context).verify(file, info(file))
        try {
            Network(context).verify(file, info(file).copy(sha = "0".repeat(64)))
            fail("Wrong hash accepted")
        } catch (_: IllegalArgumentException) {}
        try {
            Network(context).verify(file, info(file).copy(versionCode = 4))
            fail("Wrong version accepted")
        } catch (_: IllegalArgumentException) {}
        try {
            Network(context).verify(file, info(file).copy(certificate = "0".repeat(64)))
            fail("Wrong certificate accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun tamperedApkRejectedEvenWithMatchingDownloadHash() {
        val original = fixture()
        val changed = File(context.cacheDir, "tampered.apk")
        original.copyTo(changed, true)
        val bytes = changed.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        changed.writeBytes(bytes)
        try {
            Network(context).verify(changed, info(changed))
            fail("Tampered signature accepted")
        } catch (_: IllegalArgumentException) {} finally {
            changed.delete()
        }
    }
}
