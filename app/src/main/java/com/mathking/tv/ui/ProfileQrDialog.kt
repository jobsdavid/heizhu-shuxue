package com.mathking.tv.ui

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mathking.tv.App
import com.mathking.tv.R
import com.mathking.tv.net.LocalWebServer

/** 手机上扫码填写资料：电视起一个临时 HTTP 服务，二维码里是它的局域网地址 */
class ProfileQrDialog(
    private val activity: FragmentActivity,
    private val onSaved: () -> Unit
) {

    private val app = App.of(activity)
    private var server: LocalWebServer? = null

    fun show() {
        val dialog = Dialog(activity, R.style.Theme_MathKing_Dialog)
        dialog.setContentView(R.layout.dialog_qr)
        dialog.setCancelable(true)

        val qrImage = dialog.findViewById<ImageView>(R.id.qrImage)
        val tvUrl = dialog.findViewById<TextView>(R.id.tvQrUrl)
        val tvStatus = dialog.findViewById<TextView>(R.id.tvQrStatus)
        val close = dialog.findViewById<View>(R.id.btnCloseQr)

        val ip = LocalWebServer.localIpv4()
        if (ip == null) {
            qrImage.visibility = View.GONE
            tvUrl.text = ""
            tvStatus.text = "电视没连上局域网，先连 WiFi 再来"
        } else {
            val srv = LocalWebServer(
                currentName = { app.settings.userName },
                currentAvatar = { app.settings.avatar },
                onSaved = { name, avatar ->
                    app.settings.avatar = avatar
                    if (name.isNotBlank()) app.settings.userName = name
                    activity.runOnUiThread {
                        tvStatus.text = "已保存：$avatar ${name.ifBlank { "小朋友" }}"
                        onSaved()
                    }
                }
            )
            server = srv
            val port = srv.start()
            val url = "http://$ip:$port"
            tvUrl.text = url
            qrImage.setImageBitmap(qrBitmap(url))
        }

        close?.setOnClickListener { dialog.dismiss() }
        close?.requestFocus()
        dialog.setOnDismissListener {
            server?.stop()
            server = null
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(dp(520f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun qrBitmap(text: String): Bitmap {
        val size = 640
        return try {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Throwable) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        }
    }

    private fun dp(v: Float): Int =
        (v * activity.resources.displayMetrics.density + 0.5f).toInt()
}
