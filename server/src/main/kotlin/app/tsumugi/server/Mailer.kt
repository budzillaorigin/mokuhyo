package app.tsumugi.server

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Sends verification emails through any SMTP server, or logs the link when SMTP isn't configured. */
interface Mailer {
    fun sendVerification(email: String, link: String)
}

class LogMailer : Mailer {
    private val log = LoggerFactory.getLogger("mail")
    override fun sendVerification(email: String, link: String) {
        log.info("SMTP not configured; verification link for {}: {}", email, link)
    }
}

/**
 * A deliberately tiny SMTP client (STARTTLS on 587, implicit TLS on 465, AUTH LOGIN) so the server needs no mail
 * library dependency. Failures are logged, never surfaced to the registering user.
 */
class SmtpMailer(private val smtp: Config.Smtp) : Mailer {
    private val log = LoggerFactory.getLogger("mail")

    override fun sendVerification(email: String, link: String) {
        runCatching {
            send(email, "Confirm your Tsumugi sync account", "Confirm your email address for Tsumugi sync:\r\n\r\n$link\r\n")
        }.onFailure { log.warn("could not send verification email to {}: {}", email, it.message) }
    }

    private fun send(to: String, subject: String, body: String) {
        var socket: Socket = if (smtp.port == 465) {
            SSLSocketFactory.getDefault().createSocket(smtp.host, smtp.port)
        } else {
            Socket(smtp.host, smtp.port)
        }
        var io = Io(socket)
        io.expect(220)
        io.cmd("EHLO tsumugi", 250)
        if (smtp.port != 465) {
            io.cmd("STARTTLS", 220)
            socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, smtp.host, smtp.port, true) as SSLSocket
            socket.startHandshake()
            io = Io(socket)
            io.cmd("EHLO tsumugi", 250)
        }
        if (smtp.username != null) {
            io.cmd("AUTH LOGIN", 334)
            io.cmd(b64(smtp.username), 334)
            io.cmd(b64(smtp.password.orEmpty()), 235)
        }
        io.cmd("MAIL FROM:<${smtp.from}>", 250)
        io.cmd("RCPT TO:<$to>", 250)
        io.cmd("DATA", 354)
        val message = "From: ${smtp.from}\r\nTo: $to\r\nSubject: $subject\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n" +
            body.lines().joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
        io.cmd("$message\r\n.", 250)
        io.cmd("QUIT", 221)
        socket.close()
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private class Io(socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

        fun cmd(line: String, code: Int) {
            writer.write("$line\r\n")
            writer.flush()
            expect(code)
        }

        fun expect(code: Int) {
            var line: String
            do {
                line = reader.readLine() ?: error("SMTP connection closed")
            } while (line.length > 3 && line[3] == '-')
            check(line.startsWith(code.toString())) { "SMTP expected $code, got: $line" }
        }
    }
}
