package com.mathking.tv.net

import com.mathking.tv.data.ProfilePresets
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 极简局域网 HTTP 服务：电视上显示二维码，手机在同一个 WiFi 里扫码填名字/头像。
 *
 * 只在「手机扫码填写」对话框打开期间运行，关掉即停 —— 不做常驻服务，也不对公网暴露。
 * 没有第三方 HTTP 框架，手写 GET/POST 解析，够这个表单用。
 */
class LocalWebServer(
    private val currentName: () -> String,
    private val currentAvatar: () -> String,
    private val onSaved: (name: String, avatar: String) -> Unit
) {

    private var socket: ServerSocket? = null
    private var worker: Thread? = null

    var port: Int = 0
        private set

    fun start(): Int {
        val server = ServerSocket(0)
        socket = server
        port = server.localPort
        worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { handle(it) }
                } catch (_: Throwable) {
                    if (server.isClosed) break
                }
            }
        }.apply {
            isDaemon = true
            name = "profile-web"
        }.also { it.start() }
        return port
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        worker = null
    }

    // ------------------------------------------------------------ HTTP

    private fun handle(client: Socket) {
        client.soTimeout = 8000
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val head = ByteArrayOutputStream()
        var tail = 0
        while (head.size() < 16384) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) break
        }
        val header = String(head.toByteArray(), Charsets.UTF_8)
        val first = header.lineSequence().firstOrNull() ?: return
        val parts = first.split(" ")
        val method = parts.getOrNull(0) ?: "GET"
        val path = parts.getOrNull(1) ?: "/"
        val length = Regex("(?i)content-length:\\s*(\\d+)")
            .find(header)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        if (method == "POST" && path.startsWith("/save")) {
            val body = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = input.read(body, off, length - off)
                if (n < 0) break
                off += n
            }
            val form = String(body, 0, off, Charsets.UTF_8)
            val params = form.split("&").mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                decode(kv.substring(0, idx)) to decode(kv.substring(idx + 1))
            }.toMap()
            val name = (params["name"] ?: "").trim().take(8)
            val avatar = (params["avatar"] ?: "").trim().ifBlank { "🐷" }
            onSaved(name, avatar)
            respond(output, savedPage(name, avatar))
        } else {
            respond(output, formPage())
        }
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }

    private fun respond(out: OutputStream, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        try {
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ 页面

    private fun formPage(): String {
        val avatarButtons = ProfilePresets.avatars.joinToString("") { e ->
            val checked = if (e == currentAvatar()) " checked" else ""
            "<label><input type=radio name=avatar value=\"$e\"$checked><span>$e</span></label>"
        }
        val presetNames = ProfilePresets.names.joinToString(" · ")
        return """
<!doctype html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>黑猪数学 · 填资料</title>
<style>
  body{font-family:-apple-system,"PingFang SC",sans-serif;background:#0E1124;color:#fff;margin:0;padding:22px}
  h1{font-size:22px;color:#FFC53D;margin:0 0 4px}
  p{color:#9AA3C7;font-size:14px;margin:0 0 6px;line-height:1.5}
  label.f{display:block;font-size:14px;color:#9AA3C7;margin:16px 0 6px}
  input[type=text]{width:100%;box-sizing:border-box;padding:14px;font-size:18px;border-radius:12px;
    border:1px solid #2A3055;background:#171B33;color:#fff}
  .hint{font-size:12px;color:#6E7699;margin-top:6px}
  .row{display:flex;flex-wrap:wrap;gap:8px;margin-top:6px}
  .row label{width:58px;height:58px;display:flex;align-items:center;justify-content:center;
    font-size:29px;background:#1E2342;border:2px solid #2A3055;border-radius:14px;margin:0}
  .row input{display:none}
  .row input:checked + span{filter:drop-shadow(0 0 6px #FFC53D)}
  .row label:has(input:checked){border-color:#FFC53D;background:#2C3363}
  button{width:100%;margin-top:22px;padding:16px;font-size:18px;font-weight:700;border:0;
    border-radius:14px;background:#FFC53D;color:#1A1A1A}
</style></head><body>
<h1>黑猪数学</h1>
<p>手机填好，电视上马上更新</p>
<form method="post" action="/save">
  <label class="f">名字（最多 8 个字）</label>
  <input type="text" name="name" maxlength="8" value="${escape(currentName())}" placeholder="例如 王小明" required>
  <div class="hint">也可以直接用这些小名：$presetNames</div>
  <label class="f">选个头像</label>
  <div class="row">$avatarButtons</div>
  <button type="submit">保存</button>
</form>
</body></html>
"""
    }

    private fun savedPage(name: String, avatar: String): String = """
<!doctype html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>已保存</title>
<style>
  body{font-family:-apple-system,"PingFang SC",sans-serif;background:#0E1124;color:#fff;
    margin:0;padding:40px 22px;text-align:center}
  .big{font-size:64px;margin-bottom:10px}
  h1{font-size:22px;color:#3DD68C;margin:0 0 10px}
  p{color:#9AA3C7;font-size:15px}
  a{display:inline-block;margin-top:22px;color:#FFC53D;text-decoration:none;font-size:16px}
</style></head><body>
<div class="big">$avatar</div>
<h1>已保存</h1>
<p>${escape(name.ifBlank { "小朋友" })}，回到电视看看吧</p>
<a href="/">再改一次</a>
</body></html>
"""

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {

        /** 电视在局域网里的 IPv4（优先 site-local） */
        fun localIpv4(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (nif in interfaces) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            return null
        }
    }
}
