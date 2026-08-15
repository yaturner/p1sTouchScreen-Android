package com.das.p1stouch.printer.ftp

import android.util.Log
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPSClient

/**
 * Wraps Apache Commons Net's FTPSClient for the printer's implicit-FTPS
 * file server (port 990, same bblp/access-code auth as MQTT). Every
 * operation is Mutex-guarded: the underlying control connection isn't
 * thread-safe (same caution as ftplib on the Python side, which caused a
 * real crash there from concurrent file-list + thumbnail-download access)
 * -- unlike the Python app, that lock lives inside this class so callers
 * never have to think about it.
 */
class FtpPrinterClient(
    private val ip: String,
    private val accessCode: String,
) {
    private val mutex = Mutex()
    private var client: FTPSClient? = null

    suspend fun listCacheDir(): List<FtpEntry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val c = ensureConnected()
            val files = c.listFiles("cache") ?: emptyArray()
            // Deliberately re-parsing each entry's raw original line
            // ourselves (FtpListParser) rather than trusting Commons Net's
            // own field extraction -- see FtpListParser's doc comment for
            // why. Its own parser can still silently DROP a line it fails
            // to recognize (a known, accepted limitation for now).
            files.map { FtpListParser.parse(it.rawListing) }
        }
    }

    suspend fun downloadFile(path: String): ByteArray? = mutex.withLock {
        // Commons Net's socket reads have no timeout by default, so a data
        // connection the server never actually sends bytes on blocks
        // forever -- confirmed live (every thumbnail download hung
        // indefinitely with no exception until DATA_TIMEOUT was added).
        // Both the client-level timeout AND this coroutine-level one are
        // belt-and-suspenders: the client one should fire first, but if the
        // blocking socket read somehow isn't interruptible from Kotlin's
        // side, this still bounds how long the mutex stays held.
        withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val c = ensureConnected()
                val buffer = ByteArrayOutputStream()
                val ok = c.retrieveFile(path, buffer)
                if (!ok) {
                    Log.w(TAG, "retrieveFile($path) returned false; reply=${c.replyString}")
                }
                if (ok) buffer.toByteArray() else null
            }
        }
    }

    fun disconnect() {
        try {
            client?.logout()
            client?.disconnect()
        } catch (e: Exception) {
            // best-effort cleanup
        }
        client = null
    }

    private fun ensureConnected(): FTPSClient {
        client?.takeIf { it.isConnected }?.let { return it }

        // Implicit FTPS: TLS handshake happens immediately on connect, not
        // via an explicit AUTH TLS command (that's "explicit" FTPS).
        val c = FTPSClient("TLS", true)
        c.trustManager = InsecureTrustManagerFactory.INSTANCE.trustManagers[0] as javax.net.ssl.X509TrustManager
        // No timeout is set by default, so a socket read that never gets
        // the bytes it expects blocks forever -- this is what was actually
        // happening (see downloadFile's comment).
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.connect(ip, 990)
        c.soTimeout = SOCKET_TIMEOUT_MS
        c.setDataTimeout(java.time.Duration.ofMillis(SOCKET_TIMEOUT_MS.toLong()))
        c.login("bblp", accessCode)
        // PBSZ 0 + PROT P: protect the data channel too, not just control --
        // skipping this is a classic implicit-FTPS bug (control connects
        // fine, then every data transfer hangs or fails).
        c.execPBSZ(0)
        c.execPROT("P")
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        client = c
        return c
    }

    companion object {
        private const val TAG = "FtpPrinterClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val DOWNLOAD_TIMEOUT_MS = 20_000L
    }
}
