package com.mathking.tv.net

import android.content.Context
import android.util.Base64
import com.mathking.tv.data.ProfilePresets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 极简局域网 HTTP 服务：电视上显示二维码，手机扫码打开表单填名字/头像。
 * 只在「手机扫码」对话框打开期间运行，关掉就停，不对局域网常驻暴露。
 */
class LocalWebServer(
    private val context: Context,
    private val currentName: () -> String,
    private val currentAvatar: () -> String,
    private val onSaved: (name: String, avatar: String, avatarFile: File?) -> Unit
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
                } catch (t: Throwable) {
                    if (server.isClosed) break
                }
            }
        }.apply { isDaemon = true; name = "profile-web" }
        worker?.start()
        return port
    }

    fun stop() {
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        worker = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10000
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val header = ByteArrayOutputStream()
        var tail = 0
        while (header.size() < 32768) {
            val b = input.read()
            if (b < 0) return
            header.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) break
        }
        val head = String(header.toByteArray(), Charsets.UTF_8)
        val firstLine = head.lineSequence().firstOrNull() ?: return
        val parts = firstLine.split(" ")
        val method = parts.getOrNull(0) ?: "GET"
        val path = parts.getOrNull(1) ?: "/"
        val length = Regex("(?i)content-length:\\s*(\\d+)").find(head)
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

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
                if (!kv.contains("=")) return@mapNotNull null
                val k = URLDecoder.decode(kv.substringBefore("="), "UTF-8")
                val v = URLDecoder.decode(kv.substringAfter("="), "UTF-8")
                k to v
            }.toMap()

            val name = params["name"]?.trim().orEmpty().take(8)
            val avatar = params["avatar"]?.trim().orEmpty().ifBlank { "🐷" }
            val photo = params["avatar_data"]?.trim().orEmpty()
            val file = if (photo.startsWith("data:image")) savePhoto(photo) else null

            onSaved(name, avatar, file)
            respond(output, savedPage(name, avatar, file != null))
        } else {
            respond(output, formPage())
        }
    }

    /** 手机端已用 canvas 压到 256×256 的 JPEG，直接落盘到 filesDir/avatar.jpg */
    private fun savePhoto(dataUrl: String): File? = try {
        val base64 = dataUrl.substringAfter(",", "")
        if (base64.isEmpty()) null else {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isEmpty()) null else {
                val file = File(context.filesDir, "avatar.jpg")
                file.writeBytes(bytes)
                file
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun respond(output: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        output.write(head)
        output.write(bytes)
        output.flush()
    }

    private fun formPage(): String {
        val avatarButtons = ProfilePresets.avatars.joinToString("") { a ->
            val checked = if (a == currentAvatar()) " checked" else ""
            """<label class="a"><input type="radio" name="avatar" value="$a"$checked><span>$a</span></label>"""
        }
        return """
<!doctype html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>黑猪数学 · 填资料</title>
<style>
 *{box-sizing:border-box}
 body{font-family:-apple-system,"PingFang SC","Noto Sans SC",sans-serif;background:#0E1124;color:#fff;margin:0;padding:22px 18px 40px}
 h1{font-size:22px;color:#FFC53D;margin:0 0 4px}
 .sub{color:#9AA3C7;font-size:13px;margin:0 0 20px}
 label.t{display:block;font-size:14px;color:#9AA3C7;margin:16px 0 8px}
 input[type=text]{width:100%;padding:14px;font-size:19px;border-radius:12px;border:1px solid #2A3055;background:#171B33;color:#fff}
 .row{display:flex;flex-wrap:wrap;gap:9px}
 .a{width:58px;height:58px;display:flex;align-items:center;justify-content:center;font-size:29px;background:#1E2342;border:2px solid #2A3055;border-radius:14px}
 .a input{display:none}
 .a:has(input:checked){border-color:#FFC53D;background:#2C3363;box-shadow:0 0 0 2px #FFC53D inset}
 .file{display:block;margin-top:8px;padding:14px;border:1px dashed #2A3055;border-radius:12px;color:#9AA3C7;font-size:14px}
 .file input{width:100%;color:#9AA3C7}
 #preview{margin-top:10px}
 #preview img{width:76px;height:76px;border-radius:50%;object-fit:cover;border:3px solid #FFC53D}
 button{width:100%;margin-top:26px;padding:17px;font-size:19px;font-weight:700;border:0;border-radius:14px;background:#FFC53D;color:#161616}
 .foot{margin-top:14px;font-size:12px;color:#5D6689;text-align:center}
</style></head><body>
<h1>黑猪数学</h1>
<p class="sub">填好点保存，电视上会立刻更新</p>
<form method="post" action="/save">
 <label class="t">名字（最多 8 个字）</label>
 <input type="text" name="name" maxlength="8" value="${escape(currentName())}" placeholder="比如 王小明" required>

 <label class="t">或者用自己的照片当头像</label>
 <label class="file">选一张照片<input type="file" accept="image/*" onchange="shrink(this)"></label>
 <input type="hidden" name="avatar_data" id="avatarData">
 <div id="preview"></div>

 <label class="t">也可以选个表情头像</label>
 <div class="row">$avatarButtons</div>

 <button type="submit">保存</button>
 <p class="foot">只在这个页面打开时有效</p>
</form>
<script>
function shrink(input){
  var f=input.files[0];
  if(!f) return;
  var img=new Image();
  img.onload=function(){
    var s=Math.min(img.width,img.height);
    var c=document.createElement('canvas');
    c.width=256;c.height=256;
    c.getContext('2d').drawImage(img,(img.width-s)/2,(img.height-s)/2,s,s,0,0,256,256);
    var data=c.toDataURL('image/jpeg',0.85);
    document.getElementById('avatarData').value=data;
    document.getElementById('preview').innerHTML='<img src="'+data+'">';
  };
  img.src=URL.createObjectURL(f);
}
</script>
</body></html>
"""
    }

    private fun savedPage(name: String, avatar: String, hasPhoto: Boolean): String {
        val who = if (name.isBlank()) "小朋友" else escape(name)
        val mark = if (hasPhoto) "📷" else avatar
        return """
<!doctype html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>已保存 · 黑猪数学</title>
<style>
 body{font-family:-apple-system,"PingFang SC",sans-serif;background:#0E1124;color:#fff;margin:0;padding:48px 22px;text-align:center}
 .ok{font-size:64px}
 h1{font-size:24px;color:#5BE49B;margin:12px 0 6px}
 p{color:#9AA3C7;font-size:15px}
 a{display:inline-block;margin-top:24px;padding:14px 22px;border-radius:12px;background:#1E2342;color:#FFC53D;text-decoration:none;font-size:15px}
</style></head><body>
<div class="ok">✅</div>
<h1>已保存</h1>
<p>$mark $who</p>
<p>电视上已经更新，这个页面可以关掉了</p>
<a href="/">再改一次</a>
</body></html>
"""
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        /** 电视的局域网 IPv4，优先无线，其次有线 */
        fun localIpv4(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                    .firstNotNullOfOrNull { nif ->
                        nif.inetAddresses.toList()
                            .filterIsInstance<Inet4Address>()
                            .firstOrNull { it.isSiteLocalAddress }
                            ?.hostAddress
                    }
            } catch (t: Throwable) {
                null
            }
        }
    }
}
