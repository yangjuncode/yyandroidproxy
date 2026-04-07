package com.example.yyproxy

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Android 仪器化测试示例。
 *
 * 这类测试会运行在真机或模拟器上，适合验证需要 Android 运行时环境的逻辑。
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // 获取被测应用的 Context，并确认包名符合预期。
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.yyproxy", appContext.packageName)
    }
}
