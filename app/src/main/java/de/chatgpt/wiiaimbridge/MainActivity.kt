package de.chatgpt.wiiaimbridge

import android.app.Activity
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this).apply {
            textSize = 15f
            setPadding(24, 32, 24, 24)
            text = buildReport()
        }
        setContentView(output)
    }

    private fun buildReport(): String {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values
        if (devices.isEmpty()) {
            return "WiiAimBridge 0.1.0\n\nKein USB-Gerät erkannt. Bitte DolphinBar anschließen und erneut starten."
        }

        return buildString {
            appendLine("WiiAimBridge 0.1.0")
            appendLine("USB-Geräte: ${devices.size}")
            appendLine("Dies ist zunächst ein Diagnose-Build; noch keine Maus-/GunCon2-Ausgabe.")
            appendLine()
            devices.forEachIndexed { index, device ->
                appendLine("Gerät ${index + 1}")
                appendLine("Name: ${device.deviceName}")
                appendLine("VID:PID: %04X:%04X".format(device.vendorId, device.productId))
                appendLine("Klasse: ${device.deviceClass}")
                appendLine("Interfaces: ${device.interfaceCount}")
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    appendLine("  Interface $i: Klasse ${intf.interfaceClass}, Endpunkte ${intf.endpointCount}")
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        val direction = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                        appendLine("    EP $e: $direction, Typ ${ep.type}, MaxPacket ${ep.maxPacketSize}")
                    }
                }
                appendLine()
            }
        }
    }
}
