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

    private val nextVersion = BuildConfig.VERSION_CODE.toLong() + 1

    private fun fixture() =
        File(context.cacheDir, "update-test.apk").also {
            Assume.assumeTrue(
                "Host must stage the signed versionCode=$nextVersion fixture",
                it.exists(),
            )
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
        Network.Update(nextVersion, "update-test-fixture", "", "", sha256(file), certificate())

    private fun release(
        tag: String,
        preview: Boolean,
        draft: Boolean = false,
        withManifest: Boolean = true,
    ) =
        org.json
            .JSONObject()
            .put("tag_name", tag)
            .put("prerelease", preview)
            .put("draft", draft)
            .put(
                "assets",
                org.json.JSONArray().also { assets ->
                    for (name in
                        listOfNotNull(
                            if (withManifest) "annota-update.json" else null,
                            "Annota.apk",
                        )) assets.put(
                        org.json
                            .JSONObject()
                            .put("name", name)
                            .put(
                                "browser_download_url",
                                "https://github.com/dpan538/Annota/releases/download/$tag/$name",
                            )
                    )
                },
            )

    @Test
    fun previewBuildsCanDiscoverPrereleasesButProductionAndDraftBoundariesRemain() {
        val network = Network(context)
        val releases =
            org.json
                .JSONArray()
                .put(release("v0.1.5-dev", true))
                .put(release("v1.0.0", false))
                .put(release("v2.0.0-dev", true, draft = true))
                .put(release("v0.1.4-dev", true, withManifest = false))
        assertTrue(Network.previewUpdates("0.1.5-dev", false))
        assertTrue(Network.previewUpdates("1.0.0-beta", false))
        assertFalse(Network.previewUpdates("1.0.0", false))
        assertEquals(
            listOf("v0.1.5-dev", "v1.0.0"),
            network.releaseCandidates(releases, true).map { it.getString("tag_name") },
        )
        assertEquals(
            listOf("v1.0.0"),
            network.releaseCandidates(releases, false).map { it.getString("tag_name") },
        )
    }

    @Test
    fun manifestUsesIntegerVersionAndRequiresTheActualApkAssetAndIdentity() {
        val network = Network(context)
        val release = release("v0.1.5-dev", true)
        val manifest =
            org.json
                .JSONObject()
                .put("schemaVersion", 1)
                .put("packageName", context.packageName)
                .put("versionCode", nextVersion)
                .put("versionName", "0.1.6-dev")
                .put("apk", "Annota.apk")
                .put("sha256", "a".repeat(64))
                .put("certificateSha256", certificate())
        assertEquals(nextVersion, network.updateFromManifest(release, manifest)!!.versionCode)
        assertNull(
            network.updateFromManifest(
                release,
                org.json
                    .JSONObject(manifest.toString())
                    .put("versionCode", BuildConfig.VERSION_CODE),
            )
        )
        for ((key, value) in
            listOf("packageName" to "another.app", "apk" to "missing.apk", "sha256" to "invalid")) {
            try {
                network.updateFromManifest(
                    release,
                    org.json.JSONObject(manifest.toString()).put(key, value),
                )
                fail("Invalid $key accepted")
            } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
    }

    @Test
    fun cryptographicSignatureHashPackageAndVersion() {
        val file = fixture()
        Network(context).verify(file, info(file))
        try {
            Network(context).verify(file, info(file).copy(sha = "0".repeat(64)))
            fail("Wrong hash accepted")
        } catch (_: IllegalArgumentException) {}
        try {
            Network(context).verify(file, info(file).copy(versionName = "unexpected-name"))
            fail("Wrong version name accepted")
        } catch (_: IllegalArgumentException) {}
        try {
            Network(context).verify(file, info(file).copy(versionCode = nextVersion + 1))
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
