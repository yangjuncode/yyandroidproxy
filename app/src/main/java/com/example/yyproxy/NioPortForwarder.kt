package com.example.yyproxy

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 基于 Java NIO 的 TCP 端口转发器。
 *
 * 设计目标：
 * 1. 用一个 selector 线程管理同一条规则下的所有连接，避免线程随连接数线性膨胀。
 * 2. 支持双向转发、半关闭传播和基础背压控制。
 * 3. 保持纯 JVM 实现，便于单元测试，不依赖 Android 组件。
 */
class NioPortForwarder(
    localPort: Int,
    private val remoteHost: String,
    private val remotePort: Int
) : Closeable {

    @Volatile
    var localPort: Int = localPort
        private set

    private val running = AtomicBoolean(false)

    @Volatile
    private var selector: Selector? = null

    @Volatile
    private var serverChannel: ServerSocketChannel? = null

    @Volatile
    private var loopThread: Thread? = null
    private val bufferPool = ByteBufferPool(
        bufferSize = BUFFER_SIZE,
        maxPoolSize = MAX_POOLED_BUFFERS
    )

    fun start() {
        // 一个 forwarder 只允许启动一次，避免重复绑定同一端口。
        check(running.compareAndSet(false, true)) { "forwarder already started" }

        val openedSelector = Selector.open()
        val openedServer = ServerSocketChannel.open()

        try {
            // 监听端口用非阻塞模式注册到 selector，让 accept 也走事件循环。
            openedServer.configureBlocking(false)
            openedServer.bind(InetSocketAddress(localPort))
            // 如果调用方传入 0，系统会自动分配端口，这里把真实端口回填给外部读取。
            localPort = (openedServer.localAddress as InetSocketAddress).port
            openedServer.register(openedSelector, SelectionKey.OP_ACCEPT)
        } catch (e: Exception) {
            running.set(false)
            safeClose(openedServer)
            safeClose(openedSelector)
            throw e
        }

        selector = openedSelector
        serverChannel = openedServer
        loopThread = thread(
            start = true,
            isDaemon = true,
            name = "nio-forwarder-$localPort"
        ) {
            // 真正的 accept/connect/read/write 都在这条 selector 线程里驱动。
            runEventLoop(openedSelector)
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        // 先唤醒 select()，再关闭监听端口，让事件循环尽快退出。
        selector?.wakeup()
        safeClose(serverChannel)

        val worker = loopThread
        if (worker != null && worker !== Thread.currentThread()) {
            // 等一小段时间让后台线程把 selector 清理流程走完。
            worker.join(2_000)
        }
    }

    private fun runEventLoop(selector: Selector) {
        try {
            while (running.get()) {
                // 阻塞等待就绪事件，这也是 NIO 模型省线程的关键。
                selector.select()
                val selectedKeys = selector.selectedKeys().iterator()
                while (selectedKeys.hasNext()) {
                    val key = selectedKeys.next()
                    selectedKeys.remove()

                    if (!key.isValid) {
                        continue
                    }

                    try {
                        when {
                            // 新客户端连进来。
                            key.isAcceptable -> acceptClient(selector, key)
                            // 远端 SocketChannel 的非阻塞连接已完成。
                            key.isConnectable -> finishConnect(key)
                            // 某一侧有数据可读。
                            key.isReadable -> readFromChannel(key)
                            // 某一侧有积压数据可继续写出。
                            key.isWritable -> writeToChannel(key)
                        }
                    } catch (e: IOException) {
                        // 某条连接出错时只回收它自己，不影响整个 forwarder 继续服务其它连接。
                        closeAttachedConnection(key)
                    }
                }
            }
        } catch (_: ClosedSelectorException) {
            // Expected during shutdown.
        } finally {
            cleanupSelector(selector)
        }
    }

    private fun acceptClient(selector: Selector, key: SelectionKey) {
        val listener = key.channel() as ServerSocketChannel
        val clientChannel = listener.accept() ?: return
        clientChannel.configureBlocking(false)

        // 每接入一个客户端，就同时创建到远端的配对通道。
        val remoteChannel = SocketChannel.open()
        remoteChannel.configureBlocking(false)

        val connection = ProxyConnection(clientChannel, remoteChannel)
        val clientState = ChannelState(connection, clientChannel, ChannelRole.CLIENT)
        val remoteState = ChannelState(connection, remoteChannel, ChannelRole.REMOTE)
        connection.clientState = clientState
        connection.remoteState = remoteState
        clientState.peer = remoteState
        remoteState.peer = clientState

        val connected = try {
            remoteChannel.connect(InetSocketAddress(remoteHost, remotePort))
        } catch (e: IOException) {
            closeConnection(connection)
            throw e
        }

        remoteState.connected = connected
        // client 立即进入可读状态；remote 如果还没连上，则先等 OP_CONNECT。
        clientState.key = clientChannel.register(selector, SelectionKey.OP_READ, clientState)
        remoteState.key = remoteChannel.register(
            selector,
            if (connected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT,
            remoteState
        )
    }

    private fun finishConnect(key: SelectionKey) {
        val state = key.attachment() as ChannelState
        if (!state.channel.finishConnect()) {
            return
        }

        // 连接完成后才允许这一路正式读写。
        state.connected = true
        refreshInterestOps(state)
        refreshInterestOps(state.peer)
        // 如果在连接建立前，对端已经 EOF，连接一完成就要把半关闭同步过去。
        maybeShutdownOutput(state)
    }

    private fun readFromChannel(key: SelectionKey) {
        val state = key.attachment() as ChannelState
        val buffer = bufferPool.acquire()
        val bytesRead = try {
            state.channel.read(buffer)
        } catch (e: IOException) {
            bufferPool.release(buffer)
            throw e
        }

        when {
            bytesRead == -1 -> {
                bufferPool.release(buffer)
                // -1 只代表“这一方向读到 EOF”，不能直接把整条连接关掉，
                // 否则会截断另一方向还没回完的数据。
                state.readClosed = true
                refreshInterestOps(state)
                maybeShutdownOutput(state.peer)
                maybeCloseConnection(state.connection)
                return
            }

            bytesRead == 0 -> {
                bufferPool.release(buffer)
                return
            }
        }

        // 直接把池化缓冲区排到对端写队列，减少高吞吐下的小对象分配抖动。
        buffer.flip()
        enqueueWrite(state.peer, buffer)
    }

    private fun writeToChannel(key: SelectionKey) {
        val state = key.attachment() as ChannelState

        // 持续写出队列头，直到写满内核缓冲区或队列清空。
        while (state.pendingWrites.isNotEmpty()) {
            val pending = state.pendingWrites.first()
            state.channel.write(pending.buffer)
            if (pending.buffer.hasRemaining()) {
                break
            }

            state.pendingWrites.removeFirst()
            bufferPool.release(pending.buffer)
            state.pendingBytes -= pending.size
        }

        refreshInterestOps(state)
        refreshInterestOps(state.peer)
        // 某一侧写空后，可能正好满足“应该向对端发送 EOF”的条件。
        maybeShutdownOutput(state)
        maybeCloseConnection(state.connection)
    }

    private fun enqueueWrite(target: ChannelState, data: ByteBuffer) {
        // 目标通道的待写字节数也会作为背压指标，避免无限制堆积内存。
        target.pendingWrites.addLast(PendingWrite(data, data.remaining()))
        target.pendingBytes += data.remaining()
        refreshInterestOps(target)
        refreshInterestOps(target.peer)
    }

    private fun refreshInterestOps(state: ChannelState) {
        val key = state.key ?: return
        if (!key.isValid) {
            return
        }

        var ops = 0
        if (!state.connected) {
            ops = ops or SelectionKey.OP_CONNECT
        } else {
            if (state.pendingWrites.isNotEmpty()) {
                ops = ops or SelectionKey.OP_WRITE
            }
            // 当对端待写积压过多时，临时关闭当前方向的读事件，做一个轻量级背压。
            if (!state.readClosed && state.peer.pendingBytes < MAX_PENDING_BYTES) {
                ops = ops or SelectionKey.OP_READ
            }
        }
        key.interestOps(ops)
    }

    private fun maybeShutdownOutput(state: ChannelState) {
        // 只有对端已经明确 EOF，且本端待写数据全部发完时，才传播半关闭。
        if (!state.connected || state.outputShutdown || state.pendingWrites.isNotEmpty() || !state.peer.readClosed) {
            return
        }

        try {
            state.channel.shutdownOutput()
            state.outputShutdown = true
        } catch (e: IOException) {
            closeConnection(state.connection)
            return
        }

        refreshInterestOps(state)
    }

    private fun maybeCloseConnection(connection: ProxyConnection) {
        if (connection.closed) {
            return
        }

        // 两边都读完且待写队列都清空，说明这条双向流已经完整结束，可以安全关闭。
        val clientDone = connection.clientState.readClosed && connection.clientState.pendingWrites.isEmpty()
        val remoteDone = connection.remoteState.readClosed && connection.remoteState.pendingWrites.isEmpty()
        if (clientDone && remoteDone) {
            closeConnection(connection)
        }
    }

    private fun closeAttachedConnection(key: SelectionKey) {
        val attachment = key.attachment()
        if (attachment is ChannelState) {
            closeConnection(attachment.connection)
        } else {
            safeClose(key.channel())
            key.cancel()
        }
    }

    private fun closeConnection(connection: ProxyConnection) {
        if (connection.closed) {
            return
        }

        // 关闭时同时回收 client/remote 两个通道，避免只关一侧留下悬挂连接。
        connection.closed = true
        clearPendingWrites(connection.clientState)
        clearPendingWrites(connection.remoteState)
        connection.clientState.key?.cancel()
        connection.remoteState.key?.cancel()
        safeClose(connection.client)
        safeClose(connection.remote)
    }

    private fun clearPendingWrites(state: ChannelState) {
        while (state.pendingWrites.isNotEmpty()) {
            val pending = state.pendingWrites.removeFirst()
            bufferPool.release(pending.buffer)
        }
        state.pendingBytes = 0
    }

    private fun cleanupSelector(selector: Selector) {
        // forwarder 退出时，把 selector 上残留的所有 key 都清掉，防止资源泄漏。
        selector.keys().toList().forEach { key ->
            closeAttachedConnection(key)
        }
        safeClose(serverChannel)
        safeClose(selector)
    }

    private fun safeClose(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private data class ProxyConnection(
        val client: SocketChannel,
        val remote: SocketChannel,
        var closed: Boolean = false
    ) {
        // 两侧状态都在同一个连接对象上聚合，方便判断何时传播 EOF 和何时整体回收。
        lateinit var clientState: ChannelState
        lateinit var remoteState: ChannelState
    }

    private class ChannelState(
        val connection: ProxyConnection,
        val channel: SocketChannel,
        val role: ChannelRole
    ) {
        // peer 指向配对通道的状态对象，方便把当前方向读到的数据排队给另一侧写出。
        lateinit var peer: ChannelState
        var key: SelectionKey? = null
        var connected: Boolean = role == ChannelRole.CLIENT
        // readClosed 表示本方向已经收到了 EOF；outputShutdown 表示我们已向这一侧传播 EOF。
        var readClosed: Boolean = false
        var outputShutdown: Boolean = false
        // pendingWrites 里的 ByteBuffer 代表“已经读到，但还没完全写出去”的数据块。
        val pendingWrites: ArrayDeque<PendingWrite> = ArrayDeque()
        var pendingBytes: Int = 0
    }

    private enum class ChannelRole {
        CLIENT,
        REMOTE
    }

    private data class PendingWrite(
        val buffer: ByteBuffer,
        val size: Int
    )

    private companion object {
        // 单次读缓冲大小；这里沿用旧实现的 16 KiB 量级。
        const val BUFFER_SIZE = 16 * 1024
        // 单方向待写数据的软上限，用于触发轻量背压，避免慢连接把内存撑大。
        const val MAX_PENDING_BYTES = 256 * 1024
        // 池子只缓存一部分最近用过的缓冲区，避免无限囤积空闲内存。
        const val MAX_POOLED_BUFFERS = 64
    }
}
