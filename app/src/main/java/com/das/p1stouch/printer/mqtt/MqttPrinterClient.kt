package com.das.p1stouch.printer.mqtt

import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin wrapper around HiveMQ's async MQTT3 client for Bambu's local LAN
 * protocol: TLS port 8883, user "bblp" / password = the printer's LAN
 * access code, topics device/{serial}/report (subscribe) and
 * device/{serial}/request (publish). Port of what real_backend.py's
 * bambulabs_api.Printer wraps -- see PrinterTelemetry.kt for the raw
 * payload shape and PrinterCommands.kt for outgoing command JSON.
 *
 * The printer's cert is self-signed and LAN-only; trust-all here mirrors
 * the Python side's cert_reqs=ssl.CERT_NONE (same accepted trade-off, not
 * an oversight).
 */
class MqttPrinterClient(
    private val ip: String,
    private val accessCode: String,
    private val serial: String,
) {
    private var client: Mqtt3AsyncClient? = null

    val reportTopic: String get() = "device/$serial/report"
    val requestTopic: String get() = "device/$serial/request"

    suspend fun connect(): Boolean {
        // A stale client from a previous connect attempt (e.g. overlapping
        // reconnects) must be torn down first, or its socket leaks silently
        // once the field below gets overwritten with the new one.
        client?.disconnect()
        val mqtt3Client = Mqtt3Client.builder()
            .identifier("p1stouch-android-${System.currentTimeMillis()}")
            .serverHost(ip)
            .serverPort(8883)
            .sslConfig(
                MqttClientSslConfig.builder()
                    .trustManagerFactory(InsecureTrustManagerFactory.INSTANCE)
                    .build(),
            )
            .simpleAuth()
            .username("bblp")
            .password(accessCode.toByteArray(StandardCharsets.UTF_8))
            .applySimpleAuth()
            .buildAsync()
        client = mqtt3Client

        return suspendCancellableCoroutine { cont ->
            mqtt3Client.connectWith()
                .keepAlive(30)
                .send()
                .whenComplete { _, throwable ->
                    if (throwable != null) {
                        cont.resumeWithException(throwable)
                    } else {
                        cont.resume(true)
                    }
                }
        }
    }

    suspend fun subscribeReports(): Flow<String> = callbackFlow {
        val c = client ?: run { close(); return@callbackFlow }
        c.subscribeWith()
            .topicFilter(reportTopic)
            .qos(MqttQos.AT_MOST_ONCE)
            .callback { publish ->
                trySend(String(publish.payloadAsBytes, StandardCharsets.UTF_8))
            }
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) close(throwable)
            }
        awaitClose { /* subscription torn down by disconnect() */ }
    }

    suspend fun publish(json: String) {
        val c = client ?: throw IllegalStateException("not connected")
        // QoS 0 (AT_MOST_ONCE), not 1: confirmed live that a QoS 1 publish
        // sent immediately after connect (the initial pushall request)
        // hangs forever waiting for a PUBACK the printer's broker never
        // sends -- traced via filesystem markers after this exact call
        // silently blocked attemptConnect() indefinitely, which in turn
        // meant every start*() call after it (including the thumbnail
        // worker) never ran. These are fire-and-forget commands anyway
        // (pushAll() is re-sent periodically, and the UI has no
        // per-command delivery confirmation), so QoS 0 is the right
        // semantics here, not just a workaround.
        withTimeout(PUBLISH_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                c.publishWith()
                    .topic(requestTopic)
                    .qos(MqttQos.AT_MOST_ONCE)
                    .payload(json.toByteArray(StandardCharsets.UTF_8))
                    .send()
                    .whenComplete { _, throwable ->
                        if (throwable != null) cont.resumeWithException(throwable) else cont.resume(Unit)
                    }
            }
        }
    }

    fun disconnect() {
        client?.disconnect()
        client = null
    }

    val isConnected: Boolean get() = client?.state?.isConnected == true

    companion object {
        private const val PUBLISH_TIMEOUT_MS = 10_000L
    }
}
