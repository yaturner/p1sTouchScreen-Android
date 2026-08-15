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
        // This printer's FTP data transfers are slow -- observed ~85 KB/s
        // for a real 3MF over FTPS on this LAN, so DOWNLOAD_TIMEOUT_MS is
        // sized for the 15 MB thumbnail cap (see RealBackend's
        // MAX_THUMBNAIL_FILE_BYTES), not for a "should be instant" LAN
        // assumption. withTimeoutOrNull can't actually interrupt the
        // blocking retrieveFileStream()/read() calls below (coroutine
        // cancellation only takes effect at suspension points, and none of
        // this is one) -- confirmed live that on timeout it just discards
        // an otherwise-successful result once the blocking call eventually
        // returns, rather than freeing the mutex early. It's kept anyway as
        // an outer bound in case the printer's FTP daemon wedges again (see
        // this class's doc comment).
        withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                // A fresh connection per download, not the shared/reused one
                // from listCacheDir() -- this printer's embedded FTP daemon
                // has low connection tolerance (confirmed live: it can get
                // wedged for every client, including totally different FTP
                // stacks, after enough accumulated sessions), so connections
                // are opened right before use and closed immediately after,
                // matching the reference Python client's per-call connect
                // pattern rather than Commons Net's normal reuse idiom.
                val c = freshConnection()
                val buffer = ByteArrayOutputStream()
                val ins = c.retrieveFileStream(path)
                var ok = false
                if (ins != null) {
                    val chunk = ByteArray(8192)
                    var n = ins.read(chunk)
                    while (n >= 0) {
                        buffer.write(chunk, 0, n)
                        n = ins.read(chunk)
                    }
                    ins.close()
                    ok = c.completePendingCommand()
                }
                if (!ok) {
                    Log.w(TAG, "download failed for $path; reply=${c.replyString}")
                }
                try {
                    c.logout()
                    c.disconnect()
                } catch (e: Exception) {
                    // best-effort cleanup; see the connection-tolerance note above
                }
                if (client === c) client = null
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
        return freshConnection()
    }

    private fun freshConnection(): FTPSClient {
        // Implicit FTPS: TLS handshake happens immediately on connect, not
        // via an explicit AUTH TLS command (that's "explicit" FTPS).
        val c = FTPSClient("TLS", true)
        c.trustManager = InsecureTrustManagerFactory.INSTANCE.trustManagers[0] as javax.net.ssl.X509TrustManager
        // No timeout is set by default, so a socket read that never gets
        // the bytes it expects blocks forever -- relevant if the printer's
        // FTP daemon wedges again (see this class's doc comment).
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
        // Sized for the 15 MB thumbnail cap (RealBackend.MAX_THUMBNAIL_FILE_BYTES)
        // at this printer's observed real-world FTPS transfer speed (~85 KB/s
        // measured live, well under typical LAN speeds -- likely the printer's
        // embedded CPU being the bottleneck for TLS, not the network).
        private const val DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}
