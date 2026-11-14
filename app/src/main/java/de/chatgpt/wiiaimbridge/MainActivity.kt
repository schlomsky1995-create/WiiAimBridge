package de.chatgpt.wiiaimbridge

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object { private const val ACTION_USB_PERMISSION = "de.chatgpt.wiiaimbridge.USB_PERMISSION" }

    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var aimView: AimView
    private var connection: UsbDeviceConnection? = null
    private var reader: Thread? = null
    private val reading = AtomicBoolean(false)
    private var logText = StringBuilder()
    private var lastDevice: UsbDevice? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) connectDevice(device)
            else append("USB-Berechtigung nicht erteilt.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(permissionReceiver, filter)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 12) }
        val title = TextView(this).apply { text = "WiiAimBridge – IR-Test"; textSize = 22f }
        aimView = AimView(this)
        status = TextView(this).apply { textSize = 14f; text = "DolphinBar anschließen und „USB suchen“ drücken." }
        val search = Button(this).apply { text = "USB suchen"; setOnClickListener { scanUsb() } }
        val stop = Button(this).apply { text = "Verbindung stoppen"; setOnClickListener { stopReader(); append("Verbindung gestoppt.") } }
        root.addView(title)
        root.addView(aimView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(search)
        root.addView(stop)
        root.addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        scanUsb()
    }

    private fun scanUsb() {
        val devices = usb.deviceList.values.toList()
        if (devices.isEmpty()) {
            append("Kein USB-Gerät gefunden. Prüfe Dock, USB-C-Verbindung und DolphinBar.")
            return
        }
        append("USB-Geräte gefunden: ${devices.size}")
        devices.forEach { d ->
            append("• ${d.deviceName}  VID:PID %04X:%04X  Interfaces: ${d.interfaceCount}".format(d.vendorId, d.productId))
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                append("  IF$i Klasse=${intf.interfaceClass} Subklasse=${intf.interfaceSubclass} Protokoll=${intf.interfaceProtocol}")
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    append("  IF$i EP$e ${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} Adr=0x%02X Typ=${ep.type} Paket=${ep.maxPacketSize} Intervall=${ep.interval}".format(ep.address))
                }
            }
        }
        // Do not guess which USB device is the DolphinBar. Let the user select it.
        val labels = devices.map { d ->
            "${d.deviceName}  VID:PID %04X:%04X  IF:%d".format(d.vendorId, d.productId, d.interfaceCount)
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("USB-Gerät auswählen")
            .setItems(labels) { _, which ->
                val device = devices[which]
                lastDevice = device
                append("Ausgewählt: ${labels[which]}")
                if (usb.hasPermission(device)) connectDevice(device) else {
                    append("Frage Android nach USB-Zugriff für ${device.deviceName} …")
                    val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    usb.requestPermission(device, pi)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun connectDevice(device: UsbDevice) {
        stopReader()
        val conn = usb.openDevice(device)
        if (conn == null) { append("USB-Gerät konnte nicht geöffnet werden."); return }

        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        val claimed = mutableListOf<UsbInterface>()
        for (intf in interfaces) {
            if (conn.claimInterface(intf, true)) {
                claimed.add(intf)
                append("Interface ${intf.id} geöffnet (Klasse=${intf.interfaceClass}, Subklasse=${intf.interfaceSubclass}, Protokoll=${intf.interfaceProtocol}).")
            } else {
                append("Interface ${intf.id} konnte nicht geöffnet werden.")
            }
        }

        val endpoints = mutableListOf<Pair<UsbInterface, UsbEndpoint>>()
        for (intf in claimed) {
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT || ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                    endpoints.add(intf to ep)
                    append("Lese-Kanal: IF${intf.id}, Adresse=0x%02X, Typ=${ep.type}, Paket=${ep.maxPacketSize}, Intervall=${ep.interval}.".format(ep.address))
                }
            }
        }
        if (endpoints.isEmpty()) {
            conn.close()
            append("Kein Interrupt-/Bulk-IN-Kanal gefunden.")
            return
        }
        connection = conn
        append("Diagnose: ${claimed.size} Interfaces geöffnet, ${endpoints.size} Eingangs-Kanäle werden nacheinander geprüft.")
        append("Keine IR-Befehle werden gesendet. Bewege die Wii-Remote und drücke B.")
        startReader(conn, endpoints)
    }

    private fun initializeIrCamera(conn: UsbDeviceConnection, endpoint: UsbEndpoint) {
        fun send(bytes: ByteArray, label: String) {
            val sent = conn.bulkTransfer(endpoint, bytes, bytes.size, 1000)
            append("$label: USB OUT $sent/${bytes.size} Byte")
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
        fun registerWrite(offset: Int, data: ByteArray): ByteArray {
            val report = ByteArray(22)
            report[0] = 0x16
            report[1] = 0x04
            report[2] = 0xB0.toByte()
            report[3] = 0x00
            report[4] = offset.toByte()
            report[5] = data.size.toByte()
            data.copyInto(report, 6)
            return report
        }
        // Wii Remote IR setup sequence adapted from the dbar4gun driver.
        send(byteArrayOf(0x13, 0x04), "IR Set 1")
        send(byteArrayOf(0x1A, 0x04), "IR Set 2")
        send(byteArrayOf(0x16, 0x04, 0xB0.toByte(), 0x00, 0x30, 0x01, 0x08), "IR enable")
        send(registerWrite(0x00, byteArrayOf(0, 0, 0, 0, 0, 0, 0x90.toByte(), 0, 0xC0.toByte())), "IR sensitivity 1")
        send(registerWrite(0x1A, byteArrayOf(0x40, 0)), "IR sensitivity 2")
        send(registerWrite(0x33, byteArrayOf(0x01)), "IR basic mode")
        send(byteArrayOf(0x16, 0x04, 0xB0.toByte(), 0x00, 0x30, 0x01, 0x08), "IR enable final")
        send(byteArrayOf(0x12, 0x00, 0x37), "Data report 0x37")
    }

    private fun startReader(conn: UsbDeviceConnection, endpoints: List<Pair<UsbInterface, UsbEndpoint>>) {
        reading.set(true)
        reader = thread(name = "WiiAim-USB-reader") {
            val counts = IntArray(endpoints.size)
            var total = 0
            while (reading.get()) {
                for ((index, pair) in endpoints.withIndex()) {
                    if (!reading.get()) break
                    val (intf, endpoint) = pair
                    val buffer = ByteArray(maxOf(endpoint.maxPacketSize, 32))
                    val n = conn.bulkTransfer(endpoint, buffer, buffer.size, 35)
                    if (n > 0) {
                        val packet = buffer.copyOf(n)
                        counts[index]++
                        total++
                        val hex = packet.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
                        runOnUiThread {
                            processReport(packet)
                            if (total <= 20 || total % 100 == 0) {
                                append("Empfang IF${intf.id}/0x%02X: ${n} Byte – ${hex}".format(endpoint.address))
                            }
                        }
                    }
                }
            }
            runOnUiThread {
                val summary = endpoints.mapIndexed { i, pair ->
                    "IF${pair.first.id}/0x%02X: ${counts[i]} Pakete".format(pair.second.address)
                }.joinToString("\n")
                append("USB-Test beendet.\n$summary")
            }
        }
    }

    private fun processReport(p: ByteArray) {
        if (p.isEmpty()) return
        val id = p[0].toInt() and 0xff
        if (id != 0x37 || p.size < 22) {
            status.text = "USB-Daten empfangen: ${p.size} Byte, erstes Byte 0x%02X.\nNoch kein bekannter IR-Report 0x37. Rohdaten stehen im Protokoll.".format(id)
            return
        }
        val dots = decodeBasicIr(p, 6)
        val valid = dots.filterNotNull()
        if (valid.isNotEmpty()) {
            // Average visible IR points; this is diagnostic aiming, not calibrated screen mapping.
            val x = valid.map { it.first }.average()
            val y = valid.map { it.second }.average()
            aimView.setAim((1.0 - x / 1023.0).toFloat(), (y / 767.0).toFloat())
            status.text = "IR erkannt: ${valid.size}/4 Punkte\nRohmittel X=${"%.0f".format(x)} Y=${"%.0f".format(y)}\nNoch keine systemweite Maus-/GunCon2-Ausgabe."
        } else {
            status.text = "Report 0x37 empfangen, aber keine gültigen IR-Punkte."
        }
        // Wiimote core button B is commonly bit 2 in the first button byte.
        val bPressed = p.size > 1 && ((p[1].toInt() and 0x04) != 0)
        if (bPressed) status.text = status.text.toString() + "\nB-Trigger: gedrückt"
    }

    private fun decodeBasicIr(p: ByteArray, offset: Int): List<Pair<Int, Int>?> {
        fun pair(a: Int, b: Int, c: Int, d: Int, e: Int): Pair<Int, Int>? {
            val x1 = a or ((c and 0x30) shl 4)
            val y1 = b or ((c and 0xC0) shl 2)
            if (y1 != 0x3ff) return x1 to y1
            val x2 = d or ((c and 0x03) shl 8)
            val y2 = e or ((c and 0x0C) shl 6)
            if (y2 != 0x3ff) return x2 to y2
            return null
        }
        if (p.size < offset + 10) return emptyList()
        val a = pair(p[offset].u(), p[offset+1].u(), p[offset+2].u(), p[offset+3].u(), p[offset+4].u())
        val b = pair(p[offset+5].u(), p[offset+6].u(), p[offset+7].u(), p[offset+8].u(), p[offset+9].u())
        // Each five-byte group carries two dots. Decode the two pairs independently.
        return listOf(
            decodeDot(p, offset, 0), decodeDot(p, offset, 1),
            decodeDot(p, offset + 5, 0), decodeDot(p, offset + 5, 1)
        )
    }

    private fun decodeDot(p: ByteArray, o: Int, second: Int): Pair<Int, Int>? {
        val c = p[o+2].u()
        val x = if (second == 0) p[o].u() or ((c and 0x30) shl 4) else p[o+3].u() or ((c and 0x03) shl 8)
        val y = if (second == 0) p[o+1].u() or ((c and 0xC0) shl 2) else p[o+4].u() or ((c and 0x0C) shl 6)
        return if (y == 0x3ff) null else x to y
    }

    private fun Byte.u() = toInt() and 0xff

    private fun append(line: String) {
        logText.append(line).append('\n')
        if (logText.length > 6500) logText.delete(0, logText.length - 5000)
        if (::status.isInitialized) status.text = logText.toString()
    }

    private fun stopReader() {
        reading.set(false)
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        reader = null
    }

    override fun onDestroy() {
        stopReader()
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private class AimView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var x = 0.5f
        private var y = 0.5f
        private var visible = false
        fun setAim(nx: Float, ny: Float) {
            x = nx.coerceIn(0f, 1f); y = ny.coerceIn(0f, 1f); visible = true; invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(24, 28, 34))
            paint.color = Color.LTGRAY; paint.textSize = 28f
            canvas.drawText("IR-Fadenkreuz (nur innerhalb dieser App)", 16f, 34f, paint)
            if (visible) {
                val cx = x * width; val cy = 55f + y * (height - 55f)
                paint.color = Color.GREEN; paint.strokeWidth = 3f
                canvas.drawCircle(cx, cy, 22f, paint)
                canvas.drawLine(cx - 32, cy, cx + 32, cy, paint)
                canvas.drawLine(cx, cy - 32, cx, cy + 32, paint)
            } else {
                paint.color = Color.GRAY; paint.textSize = 18f
                canvas.drawText("Warte auf Wii-Remote-IR-Daten …", 16f, 80f, paint)
            }
        }
    }
}
