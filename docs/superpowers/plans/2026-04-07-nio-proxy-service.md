# NIO Proxy Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the blocking thread-per-connection proxy implementation with a selector-based NIO forwarder that scales with active proxy rules instead of active client connections.

**Architecture:** Introduce a JVM-testable `NioPortForwarder` that owns one selector loop per configured proxy rule. Each accepted client connection is paired with a non-blocking outbound channel, and both directions share queued buffers plus backpressure-aware interest-op updates. `ProxyService` becomes lifecycle orchestration only: start, stop, and refresh forwarders from saved configs.

**Tech Stack:** Kotlin, Java NIO (`Selector`, `ServerSocketChannel`, `SocketChannel`), Android foreground service, JUnit4

---

### Task 1: Add failing tests for selector-based forwarding

**Files:**
- Create: `app/src/test/java/com/example/yyproxy/NioPortForwarderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun forwards_data_between_client_and_remote_server() {
    val remoteServer = ServerSocket(0)
    val forwarder = NioPortForwarder(
        localPort = 0,
        remoteHost = "127.0.0.1",
        remotePort = remoteServer.localPort
    )
    forwarder.start()
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests com.example.yyproxy.NioPortForwarderTest.forwards_data_between_client_and_remote_server`
Expected: FAIL because `NioPortForwarder` does not exist yet

### Task 2: Implement the NIO forwarding core

**Files:**
- Create: `app/src/main/java/com/example/yyproxy/NioPortForwarder.kt`
- Test: `app/src/test/java/com/example/yyproxy/NioPortForwarderTest.kt`

- [ ] **Step 1: Write minimal implementation skeleton**

```kotlin
class NioPortForwarder(
    private val localPort: Int,
    private val remoteHost: String,
    private val remotePort: Int
) : Closeable
```

- [ ] **Step 2: Implement selector loop**

```kotlin
private fun eventLoop() {
    while (running.get()) {
        selector.select()
        val keys = selector.selectedKeys().iterator()
        while (keys.hasNext()) {
            val key = keys.next()
            keys.remove()
            // accept, finishConnect, read, write
        }
    }
}
```

- [ ] **Step 3: Add connection pairing, buffering, and shutdown handling**

```kotlin
private data class ProxyConnection(...)
private fun closeConnection(connection: ProxyConnection) { ... }
private fun updateReadInterest(...) { ... }
```

- [ ] **Step 4: Run targeted tests**

Run: `./gradlew testDebugUnitTest --tests com.example.yyproxy.NioPortForwarderTest`
Expected: PASS

### Task 3: Replace ProxyService blocking sockets with the NIO core

**Files:**
- Modify: `app/src/main/java/com/example/yyproxy/ProxyService.kt`
- Test: `app/src/test/java/com/example/yyproxy/NioPortForwarderTest.kt`

- [ ] **Step 1: Replace `ProxyTask` socket logic with forwarder lifecycle management**

```kotlin
private inner class ProxyTask(val config: ProxyConfig) {
    private var forwarder: NioPortForwarder? = null

    fun run() {
        forwarder = NioPortForwarder(config.localPort, config.remoteHost, config.remotePort)
        forwarder?.start()
    }

    fun stop() {
        forwarder?.close()
    }
}
```

- [ ] **Step 2: Run full unit test suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 3: Run debug build verification**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL
