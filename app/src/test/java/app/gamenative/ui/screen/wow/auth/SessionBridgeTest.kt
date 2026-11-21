package app.gamenative.ui.screen.wow.auth

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The helper reads the credential frame, then one account frame: "NAME\nREGION", or empty when the
 * login does not name a single WoW account. The newline is the separator, not a printable character,
 * so validating the joined text rejected every account.
 */
class SessionBridgeTest {
    @Test
    fun `handover sends the credential and the sole account name with its region`() {
        val (token, account) = handover(BgsClient.GameAccount("WANDERER", "CN"))
        assertArrayEquals(TOKEN, token)
        assertEquals("WANDERER\nCN", account.toString(Charsets.US_ASCII))
    }

    @Test
    fun `handover sends an empty account frame when the login names no single account`() {
        val (token, account) = handover(null)
        assertArrayEquals(TOKEN, token)
        assertEquals(0, account.size)
    }

    /** Plays the helper over a real socket, since the frames are read by the Wine side, not by Kotlin. */
    private fun handover(account: BgsClient.GameAccount?): Pair<ByteArray, ByteArray> {
        SessionBridge().use { bridge ->
            val lines = bridge.config.lines()
            val port = lines[0].toInt()
            val nonce = lines[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val helper = executor.submit<Pair<ByteArray, ByteArray>> {
                    Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        output.write(proof("CLIENT", nonce))
                        output.flush()
                        val answer = ByteArray(32)
                        input.readFully(answer)
                        check(answer.contentEquals(proof("SERVER", nonce))) { "Bridge proof mismatch" }
                        writeFrame(output, "{}".toByteArray())
                        val token = readFrame(input)
                        val accountSize = input.readInt()
                        check(accountSize in 0..67) { "Account frame of $accountSize bytes" }
                        val accountFrame = ByteArray(accountSize)
                        if (accountSize > 0) input.readFully(accountFrame)
                        writeFrame(output, "STARTED".toByteArray())
                        token to accountFrame
                    }
                }
                bridge.accept().use { peer ->
                    peer.device()
                    peer.launch(TOKEN, account)
                }
                return helper.get(15, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun proof(role: String, nonce: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("WOW-SESSION-$role-1".toByteArray(Charsets.US_ASCII) + nonce)

    private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val size = input.readInt()
        return ByteArray(size).also(input::readFully)
    }

    private companion object {
        val TOKEN = "abc123".toByteArray(Charsets.US_ASCII)
    }
}
