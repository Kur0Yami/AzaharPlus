package org.citra.citra_emu.utils

import android.content.Context
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.citra.citra_emu.utils.FileUtil.inputStream

/**
 * Fetch custom GPU driver (zip) dari GitHub releases, mirip driver fetcher di Eden.
 * Semua fungsi blocking: panggil dari thread background (Dispatchers.IO / Thread).
 */
object DriverFetcher {
    data class DriverAsset(
        val repo: String,
        val release: String,
        val name: String,
        val url: String,
        val size: Long
    )

    // Sumber driver Turnip (Adreno). Tambah/ubah sesuai kebutuhan.
    val repos = listOf(
        "MrPurple666/purple-turnip",
        "K11MCH1/AdrenoToolsDrivers",
        "Weab-chan/freedreno_turnip-CI"
    )

    /** True kalau SoC Qualcomm (Adreno), jadi driver Turnip relevan. */
    fun isQualcomm(): Boolean {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        return soc.equals("QTI", true) || soc.contains("qualcomm", true) ||
            Build.HARDWARE.equals("qcom", true)
    }

    fun fetchAssets(repo: String, limit: Int = 10): List<DriverAsset> {
        val out = mutableListOf<DriverAsset>()
        val conn = URL("https://api.github.com/repos/" + repo + "/releases?per_page=" + limit)
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            if (conn.responseCode != 200) return out
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            for (i in 0 until arr.length()) {
                val rel = arr.getJSONObject(i)
                val relName = rel.optString("name").ifBlank { rel.getString("tag_name") }
                val assets = rel.getJSONArray("assets")
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val n = a.getString("name")
                    if (n.endsWith(".zip", true)) {
                        out.add(DriverAsset(repo, relName, n, a.getString("browser_download_url"), a.optLong("size")))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    /** Ambil asset dari semua repo; repo yang gagal dilewati. */
    fun fetchAll(): List<DriverAsset> =
        repos.flatMap { runCatching { fetchAssets(it) }.getOrDefault(emptyList()) }

    /**
     * Download zip langsung ke folder gpu_drivers (SAF) lalu validasi meta.json.
     * Return null kalau driver tidak valid / minApi kurang. Blocking: panggil dari background.
     */
    fun downloadToDriverStorage(context: Context, asset: DriverAsset, onProgress: (Int) -> Unit = {}): Pair<DocumentFile, GpuDriverMetadata>? {
        GpuDriverHelper.initializeDirectories()
        val doc = GpuDriverHelper.driverStoragePath.createFile("application/zip", asset.name) ?: return null
        val conn = URL(asset.url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
            var done = 0L
            val output = context.contentResolver.openOutputStream(doc.uri) ?: throw IOException("Gagal membuka output")
            conn.inputStream.use { input ->
                output.use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        } catch (e: IOException) {
            doc.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        val meta = GpuDriverHelper.getMetadataFromZip(doc.inputStream())
        if (meta.name == null || meta.minApi > Build.VERSION.SDK_INT) {
            doc.delete()
            return null
        }
        return Pair(doc, meta)
    }
}
