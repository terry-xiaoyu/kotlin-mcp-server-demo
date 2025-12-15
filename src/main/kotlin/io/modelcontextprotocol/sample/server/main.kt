package io.modelcontextprotocol.sample.server

import kotlinx.coroutines.runBlocking

/**
 * Start sse-server mcp on port 3001.
 *
 * @param args
 * - "--stdio": Runs an MCP server using standard input/output.
 * - "--sse-server-ktor <port>": Runs an SSE MCP server using Ktor plugin (default if no argument is provided).
 * - "--sse-server <port>": Runs an SSE MCP server with a plain configuration.
 * - "--mqtt <clientId>": Runs an MCP server using an MQTT broker with the specified client ID.
 */
@OptIn(ExperimentalUnsignedTypes::class)
fun main(vararg args: String): Unit = runBlocking {
    val command = args.firstOrNull() ?: "--stdio"
    when (command) {
        "--stdio" -> runMcpServerUsingStdio()

        "--sse-server-ktor" -> {
            val port = args.getOrNull(1)?.toIntOrNull() ?: 3001
            runSseMcpServerUsingKtorPlugin(port)
        }

        "--sse-server" -> {
            val port = args.getOrNull(1)?.toIntOrNull() ?: 3001
            runSseMcpServerWithPlainConfiguration(port)
        }

        "--mqtt" -> {
            val clientId = args.getOrNull(1) ?: "mcp-server-demo-kotlin"
            runMcpServerUsingMqttBroker(clientId)
        }

        else -> {
            error("Unknown command: $command")
        }
    }
}
