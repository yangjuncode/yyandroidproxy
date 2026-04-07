package com.example.yyproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `NioPortForwarder` 的回归测试。
 *
 * 这组测试验证两件关键事情：
 * 1. 单连接场景下，请求和响应都能完整穿过转发器。
 * 2. 多连接并发场景下，不会退化成“每连一条就卡住一条”的阻塞模型。
 */
class NioPortForwarderTest {

    @Test
    fun forwards_data_between_client_and_remote_server() {
        // 用一个本地 echo server 作为“远端服务”，避免测试依赖外网环境。
        EchoServer().use { remote ->
            NioPortForwarder(
                localPort = 0,
                remoteHost = "127.0.0.1",
                remotePort = remote.port
            ).use { forwarder ->
                forwarder.start()

                Socket("127.0.0.1", forwarder.localPort).use { client ->
                    val payload = "hello through nio".toByteArray(StandardCharsets.UTF_8)
                    client.getOutputStream().write(payload)
                    // 主动半关闭输出，模拟客户端“请求发完，开始等响应”的真实 TCP 场景。
                    client.shutdownOutput()

                    val response = client.getInputStream().readBytes()
                    assertArrayEquals(payload, response)
                }
            }
        }
    }

    @Test
    fun forwards_multiple_clients_without_blocking_per_connection() {
        EchoServer().use { remote ->
            NioPortForwarder(
                localPort = 0,
                remoteHost = "127.0.0.1",
                remotePort = remote.port
            ).use { forwarder ->
                forwarder.start()

                // 用多个并发客户端同时压同一个 forwarder，验证 selector 模型能并发处理多个连接。
                val pool = Executors.newFixedThreadPool(6)
                try {
                    val done = CountDownLatch(6)
                    repeat(6) { index ->
                        pool.submit {
                            Socket("127.0.0.1", forwarder.localPort).use { client ->
                                val payload = "client-$index".toByteArray(StandardCharsets.UTF_8)
                                client.getOutputStream().write(payload)
                                client.shutdownOutput()

                                val response = client.getInputStream().readBytes()
                                assertArrayEquals(payload, response)
                            }
                            done.countDown()
                        }
                    }

                    // 所有客户端都应该在超时时间内完成请求，否则说明转发流程有阻塞或死锁风险。
                    val completed = done.await(5, TimeUnit.SECONDS)
                    org.junit.Assert.assertTrue("expected all clients to complete", completed)
                } finally {
                    pool.shutdownNow()
                }
            }
        }
    }

    private class EchoServer : Closeable {
        private val serverSocket = ServerSocket(0)
        private val executor = Executors.newCachedThreadPool()
        val port: Int = serverSocket.localPort

        init {
            // 简单本地 echo server：收到什么就回什么，适合做端口转发验证。
            executor.submit {
                while (!serverSocket.isClosed) {
                    try {
                        val socket = serverSocket.accept()
                        executor.submit {
                            socket.use { accepted ->
                                // 把客户端输入原样写回输出，模拟一个最小可用的远端服务。
                                accepted.getInputStream().copyTo(accepted.getOutputStream())
                            }
                        }
                    } catch (_: Exception) {
                        if (!serverSocket.isClosed) {
                            throw RuntimeException("echo server accept failed")
                        }
                    }
                }
            }
        }

        override fun close() {
            // 测试退出时及时关闭监听端口和线程池，避免影响后续测试用例。
            serverSocket.close()
            executor.shutdownNow()
        }
    }
}
