package com.margelo.nitro.nitrovto

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Utility functions for remote model loading.
 */
object LoaderUtils {

    private const val TAG = "LoaderUtils"
    private const val DEFAULT_BUFFER_SIZE = 8192
    private const val CACHE_DIR = "glb_cache"

    /**
     * Load a GLB file from a remote URL.
     * Uses a file cache to avoid re-downloading.
     * This method performs network I/O and should be called from a background thread.
     */
    fun loadModelFromUrl(context: Context, urlString: String): ByteArray {
        Log.d(TAG, "Loading GLB from URL: $urlString")

        // Check cache first
        val cacheFile = getCacheFile(context, urlString)
        if (cacheFile.exists()) {
            Log.d(TAG, "Loading from cache: ${cacheFile.absolutePath}")
            return loadFromFile(cacheFile)
        }

        // Download from URL
        val bytes = downloadFromUrl(urlString)

        // Save to cache
        saveToCache(cacheFile, bytes)

        return bytes
    }

    private fun getCacheFile(context: Context, urlString: String): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val filename = hashUrl(urlString) + ".glb"
        return File(cacheDir, filename)
    }

    private fun hashUrl(urlString: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(urlString.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun loadFromFile(file: File): ByteArray {
        val bytes = file.readBytes()
        Log.d(TAG, "Loaded ${bytes.size} bytes from cache")
        return bytes
    }

    private fun saveToCache(file: File, bytes: ByteArray) {
        try {
            file.writeBytes(bytes)
            Log.d(TAG, "Saved ${bytes.size} bytes to cache: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to cache: ${e.message}")
        }
    }

    private fun downloadFromUrl(urlString: String): ByteArray {
        val url = URL(urlString)
        val urlConnection = url.openConnection() as HttpURLConnection

        try {
            urlConnection.connectTimeout = 15000
            urlConnection.readTimeout = 30000
            urlConnection.connect()

            val responseCode = urlConnection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP error code: $responseCode")
            }

            val inputStream = BufferedInputStream(urlConnection.inputStream)
            val byteArrayOutputStream = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead)
            }

            inputStream.close()

            val bytes = byteArrayOutputStream.toByteArray()
            Log.d(TAG, "Downloaded ${bytes.size} bytes from URL")

            return bytes
        } finally {
            urlConnection.disconnect()
        }
    }
}
