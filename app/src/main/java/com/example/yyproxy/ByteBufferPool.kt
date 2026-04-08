package com.example.yyproxy

import java.nio.ByteBuffer
import java.util.ArrayDeque

class ByteBufferPool(
    private val bufferSize: Int,
    private val maxPoolSize: Int
) {
    private val available = ArrayDeque<ByteBuffer>()

    fun acquire(): ByteBuffer = synchronized(available) {
        if (available.isEmpty()) {
            ByteBuffer.allocate(bufferSize)
        } else {
            available.removeFirst().apply { clear() }
        }
    }

    fun release(buffer: ByteBuffer) {
        require(buffer.capacity() == bufferSize) {
            "Expected buffer capacity $bufferSize but was ${buffer.capacity()}"
        }

        buffer.clear()
        synchronized(available) {
            if (available.size < maxPoolSize) {
                available.addLast(buffer)
            }
        }
    }
}
