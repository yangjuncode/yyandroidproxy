package com.example.yyproxy

import org.junit.Test

import org.junit.Assert.*

/**
 * 本地 JVM 单元测试示例。
 *
 * 这个测试本身没有业务价值，主要用于确认测试框架接线正常，
 * 也就是 Gradle 能编译并执行最基础的 JUnit 用例。
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        // 最简单的断言，用来验证测试环境本身是可运行的。
        assertEquals(4, 2 + 2)
    }
}
