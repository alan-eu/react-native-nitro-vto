package com.margelo.nitro.nitrovto

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility functions for loading assets.
 */
object LoaderUtils {

    /**
     * Load an asset file into a direct ByteBuffer.
     */
    fun loadAsset(context: Context, filename: String): ByteBuffer {
        context.assets.open(filename).use { input ->
            val bytes = input.readBytes()
            val buffer = ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes)
            buffer.rewind()
            return buffer
        }
    }
}
