package io.modelcontextprotocol.sample.server

import io.github.davidepianca98.MQTTClient
import io.github.davidepianca98.mqtt.MQTTVersion
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.McpMqttServer
import io.modelcontextprotocol.kotlin.sdk.server.MqttServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.text.firstOrNull
import kotlin.text.toDoubleOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject

fun configureServer(): Server {
    val server = Server(
        Implementation(
            name = "mcp-kotlin test server",
            version = "0.1.0",
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                prompts = ServerCapabilities.Prompts(listChanged = true),
                resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    server.addPrompt(
        name = "Kotlin Developer",
        description = "Develop small kotlin applications",
        arguments = listOf(
            PromptArgument(
                name = "Project Name",
                description = "Project name for the new project",
                required = true,
            ),
        ),
    ) { request ->
        GetPromptResult(
            messages = listOf(
                PromptMessage(
                    role = Role.User,
                    content = TextContent(
                        "Develop a kotlin project named <name>${request.arguments?.get("Project Name")}</name>",
                    ),
                ),
            ),
            description = "Description for ${request.name}",
        )
    }

    // Add a calculator tool
    server.addTool(
        name = "calculator",
        description = "This tool can perform basic math operations: addition, subtraction, multiplication, and division.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("num1") {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive("The first number"))
                }
                putJsonObject("num2") {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive("The second number"))
                }
                putJsonObject("op") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The operation to perform: +, -, *, /"))
                }
            },
            required = listOf("num1", "num2", "op"),
        ),
    ) { request : CallToolRequest ->
        val num1 = request.params.arguments?.get("num1")?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid or missing argument: num1")
        val num2 = request.params.arguments?.get("num2")?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid or missing argument: num2")
        val op = request.params.arguments?.get("op")?.jsonPrimitive?.content?.firstOrNull()
            ?: throw IllegalArgumentException("Invalid or missing argument: op")
        val x = num1.toDouble()
        val y = num2.toDouble()
        val result = when(op) {
            '+' -> x + y
            '-' -> x - y
            '*' -> x * y
            '/' -> if (y == 0.0) throw ArithmeticException("Division by zero") else x / y
            else -> throw IllegalArgumentException("Don't support Operator: $op")
        }
        CallToolResult(
            content = listOf(TextContent("The result is: $result")),
        )
    }

    // Add a light control tool
    server.addTool(
        name = "set_light_brightness",
        description = "Control the light on the panel. You can change the brightness, if you want to off the light, set brightness to 0. Set brightness value to 'last_value' to restore the previous brightness, which is useful when the light is off and you want to turn it back on.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("value") {
                    put("type", JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("string"))))
                    put("description", JsonPrimitive("Brightness value between 0 and 100, or 'last_value' to restore previous brightness"))
                }
            },
            required = listOf("value")
        ),
    ) { request ->
        val value = request.params.arguments?.get("value")?.jsonPrimitive?.content
        // Handle the request and control the light brightness
        CallToolResult(
            content = listOf(TextContent("Light brightness set to: ${value}%")),
        )
    }

    // Add a resource
    server.addResource(
        uri = "https://search.com/",
        name = "Web Search",
        description = "Web search engine",
        mimeType = "text/html",
    ) { request ->
        ReadResourceResult(
            contents = listOf(
                TextResourceContents("Placeholder content for ${request.uri}", request.uri, "text/html"),
            ),
        )
    }

    return server
}

fun runSseMcpServerWithPlainConfiguration(port: Int, wait: Boolean = true) {
    printBanner(port = port, path = "/sse")
    val serverSessions = ConcurrentMap<String, ServerSession>()

    val server = configureServer()

    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        installCors()
        install(SSE)
        routing {
            sse("/sse") {
                val transport = SseServerTransport("/message", this)
                val serverSession = server.createSession(transport)
                serverSessions[transport.sessionId] = serverSession

                serverSession.onClose {
                    println("Server session closed for: ${transport.sessionId}")
                    serverSessions.remove(transport.sessionId)
                }
            }
            post("/message") {
                val sessionId: String? = call.request.queryParameters["sessionId"]
                if (sessionId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing sessionId parameter")
                    return@post
                }

                val transport = serverSessions[sessionId]?.transport as? SseServerTransport
                if (transport == null) {
                    call.respond(HttpStatusCode.NotFound, "Session not found")
                    return@post
                }

                transport.handlePostMessage(call)
            }
        }
    }.start(wait = wait)
}

/**
 * Starts an SSE (Server-Sent Events) MCP server using the Ktor plugin.
 *
 * This is the recommended approach for SSE servers as it simplifies configuration.
 * The URL can be accessed in the MCP inspector at http://localhost:[port]/sse
 *
 * @param port The port number on which the SSE MCP server will listen for client connections.
 */
fun runSseMcpServerUsingKtorPlugin(port: Int, wait: Boolean = true): EmbeddedServer<*, *> {
    printBanner(port)

    val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
        installCors()
        mcp {
            return@mcp configureServer()
        }
    }.start(wait = wait)
    return server
}

private fun printBanner(port: Int, path: String = "") {
    if (port == 0) {
        println("🎬 Starting SSE server on random port")
    } else {
        println("🎬 Starting SSE server on ${if (port > 0) "port $port" else "random port"}")
        println("🔍 Use MCP inspector to connect to http://localhost:$port$path")
    }
}

private fun Application.installCors() {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowNonSimpleContentTypes = true
        anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
    }
}

/**
 * Starts an MCP server using Standard I/O transport.
 *
 * This mode is useful for process-based communication where the server
 * communicates via stdin/stdout with a parent process or client.
 */
fun runMcpServerUsingStdio() {
    val server = configureServer()
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    runBlocking {
        server.createSession(transport)
        val done = Job()
        server.onClose {
            done.complete()
        }
        done.join()
    }
}

@OptIn(kotlin.ExperimentalUnsignedTypes::class)
fun runMcpServerUsingMqttBroker(clientId: String, serverName: String) {
    val client = MQTTClient(
        mqttVersion = MQTTVersion.MQTT5,
        address = "localhost",
        port = 1883,
        tls = null,
        clientId = clientId,
        autoInit = false
    ) {
        val msg = it.payload?.toByteArray()?.decodeToString()
        println("Received non MCP MQTT message: $msg, topic: ${it.topicName}")
    }
    val mcpMqttServer = McpMqttServer(
        mcpServer = configureServer(),
        mqttClient = client,
        serverName = serverName,
        description = "An demo MCP server that provides a calculator tool",
    )
    mcpMqttServer.connect()
    runBlocking {
        client.run()
    }
}
