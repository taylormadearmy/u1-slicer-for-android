package com.u1.slicer.printer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttMessageListener
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.net.ssl.SSLSocketFactory

class PahoBambuMqttSessionFactory : BambuMqttSessionFactory {
    override fun create(
        serverUri: String,
        username: String,
        password: String,
        socketFactory: SSLSocketFactory,
    ): BambuMqttSession {
        val clientId = "u1-slicer-${UUID.randomUUID()}"
        val client = MqttAsyncClient(serverUri, clientId, MemoryPersistence())
        val options = MqttConnectOptions().apply {
            this.socketFactory = socketFactory
            this.userName = username
            this.password = password.toCharArray()
            this.mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            this.isCleanSession = true
            // The transport owns reconnects so a non-idempotent print command
            // cannot be replayed automatically after a dropped QoS 1 session.
            this.isAutomaticReconnect = false
            this.connectionTimeout = 10
            this.keepAliveInterval = 30
            this.maxInflight = 1_000
        }
        return PahoBambuMqttSession(client, options)
    }
}

private class PahoBambuMqttSession(
    private val client: MqttAsyncClient,
    private val options: MqttConnectOptions,
) : BambuMqttSession {
    @Volatile private var disconnectedListener: (() -> Unit)? = null
    @Volatile private var callbackInstalled = false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        client.connect(options).waitForCompletion(CONNECT_TIMEOUT_MILLIS)
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (client.isConnected) {
            // Do not wait for an acknowledgement from an in-flight printer
            // command while changing printers or tearing down the app.
            client.disconnectForcibly(0, 0, false)
        }
        client.close()
    }

    override suspend fun subscribe(
        topic: String,
        onMessage: (String) -> Unit,
        onDisconnected: () -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            disconnectedListener = onDisconnected
            if (!callbackInstalled) {
                client.setCallback(object : MqttCallbackExtended {
                    override fun connectionLost(cause: Throwable?) {
                        disconnectedListener?.invoke()
                    }

                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        val command = runCatching {
                            token?.message?.payload
                                ?.let { String(it, StandardCharsets.UTF_8) }
                                ?.let(::publishCommand)
                                ?: "unknown"
                        }.getOrDefault("unknown")
                        Log.i(TAG, "MQTT PUBACK command=$command messageId=${token?.messageId}")
                    }
                })
                callbackInstalled = true
            }
            client.subscribe(topic, 1, IMqttMessageListener { _, message ->
                onMessage(String(message.payload, StandardCharsets.UTF_8))
            }).waitForCompletion(SUBSCRIBE_TIMEOUT_MILLIS)
        }
    }

    override suspend fun publish(topic: String, payload: String) {
        withTimeout(PUBLISH_TIMEOUT_MILLIS) {
            runInterruptible(Dispatchers.IO) {
                val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8))
                // Bambu Studio and Bambu Buddy dispatch project_file with QoS 1.
                // Do not wait on the delivery token: Bambu firmware has unreliable
                // PUBACK handling and can execute a command without completing it.
                message.qos = 1
                val token = client.publish(topic, message)
                Log.i(TAG, "MQTT publish queued command=${publishCommand(payload)} messageId=${token.messageId}")
            }
        }
    }

    private companion object {
        const val TAG = "BambuMqtt"
        const val CONNECT_TIMEOUT_MILLIS = 12_000L
        const val SUBSCRIBE_TIMEOUT_MILLIS = 8_000L
        const val PUBLISH_TIMEOUT_MILLIS = 3_000L

        fun publishCommand(payload: String): String = when {
            payload.contains("\"project_file\"") -> "project_file"
            payload.contains("\"gcode_file\"") -> "gcode_file"
            payload.contains("\"pushall\"") -> "pushall"
            else -> "other"
        }
    }
}
