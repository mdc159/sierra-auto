package com.mdc159.sierra.voice.duplex

import android.content.Context
import com.mdc159.sierra.agent.AgentClient
import com.mdc159.sierra.agent.ChatMessage
import com.mdc159.sierra.agent.ConfigStore
import com.mdc159.sierra.agent.Conversation
import com.mdc159.sierra.studio54.Studio54
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Entry points used by the phone screen (and, in phase 2, the Talk screen): build the
 * session for the saved provider, with the built-in `ask_agent` tool pointed at the
 * owner's configured agent.
 */
object Duplex {
    /** A websocket client without a read timeout: realtime sockets stay quiet between turns. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    fun session(config: DuplexConfig, tools: ToolRegistry, client: OkHttpClient = this.client): DuplexSession =
        when (config.provider) {
            DuplexProvider.OPENAI_REALTIME -> OpenAiRealtimeSession(config, tools, client)
            DuplexProvider.ELEVENLABS -> ElevenLabsAgentSession(config, tools, client)
        }

    /** The default tool set: `ask_agent` bound to the agent configured in Sierra's settings. */
    fun defaultTools(context: Context): ToolRegistry = tools(context, emptyList())

    /** `ask_agent` plus [extra] (on the car, the Talk screen's tools through [TalkToolAdapter]). */
    fun tools(context: Context, extra: List<DuplexTool>): ToolRegistry =
        ToolRegistry(listOf(AskAgentTool(ConfiguredAgentBridge(context))) + extra)
}

/** Forwards `ask_agent` questions to the OpenAI-compatible agent from the main settings. */
class ConfiguredAgentBridge(context: Context) : AgentBridge {
    private val app = context.applicationContext

    override fun ask(question: String): String {
        // Same settings Talk uses: signed in to Studio 54, the gateway address and key come from the vault.
        val config = Studio54.agentConfig(app, ConfigStore.load(app))
        if (!config.isConfigured) throw IllegalStateException("The owner's agent is not set up on the phone.")
        return AgentClient(config).chat(
            listOf(ChatMessage("system", Conversation.SYSTEM_PROMPT), ChatMessage("user", question)),
        )
    }
}
