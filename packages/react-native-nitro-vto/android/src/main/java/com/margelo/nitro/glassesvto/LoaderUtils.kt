package com.margelo.nitro.nitrovto

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility functions for loading remote GLB files.
 */
object LoaderUtils {

    private const val TAG = "LoaderUtils"
    private const val DEFAULT_BUFFER_SIZE = 8192

    /**
     * Load a GLB file from a remote URL into a direct ByteBuffer.
     * This method performs network I/O and should be called from a background thread.
     */
    fun loadFromUrl(urlString: String): ByteBuffer {
        Log.d(TAG, "Loading GLB from URL: $urlString")

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

            val byteBuffer = ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes)
            byteBuffer.rewind()

            return byteBuffer
        } finally {
            urlConnection.disconnect()
        }
    }
}
