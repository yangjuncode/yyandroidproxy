package com.example.yyproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ByteBufferPoolTest {

    @Test
    fun released_buffer_is_reused_on_next_acquire() {
        val pool = ByteBufferPool(bufferSize = 16, maxPoolSize = 2)
        val first = pool.acquire()
        first.put(1)
        pool.release(first)

        val second = pool.acquire()

        assertSame(first, second)
        assertEquals(0, second.position())
        assertEquals(16, second.remaining())
    }
}
