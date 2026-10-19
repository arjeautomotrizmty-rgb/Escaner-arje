```kotlin
package com.arje.escaner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private val NARANJA = Color.parseColor("#F2921A")
    private val GRIS = Color.parseColor("#242426")
    private val GRIS2 = Color.parseColor("#3A3A3C")
    private val VERDE = Color.parseColor("#2ECC71")
    private val ROJO = Color.parseColor("#E74C3C")
    private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var entrada: InputStream? = null
    private var salida: OutputStream? = null
    @Volatile private var vivo = false

    private lateinit var estado: TextView
    private lateinit var fallas: LinearLayout
    private lateinit var log: TextView
    private val medidores = mutableMapOf<String, Pair<TextView, ProgressBar>>()

    private val DTC = mapOf(
        "P0300" to Pair("Falla de encendido en varios cilindros", 3),
        "P0301" to Pair("Falla de encendido cilindro 1", 3),
        "P0302" to Pair("Falla de encendido cilindro 2", 3),
        "P0303" to Pair("Falla de encendido cilindro 3", 3),
        "P0304" to Pair("Falla de encendido cilindro 4", 3),
        "P0305" to Pair("Falla de encendido cilindro 5", 3),
        "P0306" to Pair("Falla de encendido cilindro 6", 3),
        "P0016" to Pair("Desfase cigüeñal / árbol de levas", 3),
        "P0011" to Pair("Árbol de levas adelantado banco 1", 3),
        "P0171" to Pair("Mezcla pobre banco 1 (posible fuga de vacío)", 2),
        "P0174" to Pair("Mezcla pobre banco 2 (posible fuga de vacío)", 2),
        "P0172" to Pair("Mezcla rica banco 1", 2),
        "P0175" to Pair("Mezcla rica banco 2", 2),
        "P0101" to Pair("Sensor MAF fuera de rango", 2),
        "P0130" to Pair("Sensor de oxígeno banco 1", 2),
        "P0420" to Pair("Catalizador con baja eficiencia banco 1", 2),
        "P0430" to Pair("Catalizador con baja eficiencia banco 2", 2),
        "P2187" to Pair("Mezcla pobre en ralentí", 2),
        "P0128" to Pair("Termostato: motor tarda en calentar", 1),
        "P0442" to Pair("Fuga pequeña EVAP (revisa tapón de gasolina)", 1),
        "P0455" to Pair("Fuga grande EVAP", 1),
        "P0507" to Pair("Ralentí más alto de lo normal", 1)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GRIS
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(GRIS)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            adjustViewBounds = true
            background = redondo(Color.WHITE, 16)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        raiz.addView(logo, LinearLayout.LayoutParams(-1, dp(140)))

        estado = texto("● Desconectado", 18f, ROJO, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8))
        }
        raiz.addView(estado)

        val fila1 = fila(); val fila2 = fila()
        fila1.addView(boton("🔌 Conectar") { pedirPermisoYConectar() }, peso())
        fila1.addView(boton("🔍 Leer fallas") { hilo { leerFallas() } }, peso())
        fila2.addView(boton("📊 En vivo") { alternarVivo() }, peso())
        fila2.addView(boton("🧹 Borrar") { confirmarBorrar() }, peso())
        raiz.addView(fila1); raiz.addView(fila2)

        val tablero = GridLayout(this).apply { columnCount = 2 }
        listOf("rpm" to "RPM", "temp" to "Temp. motor °C", "vel" to "Velocidad km/h", "volt" to "Batería V")
            .forEach { (k, t) -> tablero.addView(medidor(k, t)) }
        raiz.addView(tablero)

        raiz.addView(texto("Códigos de falla", 18f, Color.WHITE, true).apply { setPadding(0, dp(12), 0, dp(4)) })
        fallas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(fallas)

        val fila3 = fila()
        fila3.addView(boton("🎛️ Probar MMI", GRIS2) { hilo { probarMMI() } }, peso())
        fila3.addView(boton("📅 Historial", GRIS2) { verHistorial() }, peso())
        raiz.addView(fila3)

        log = texto("", 12f, Color.parseColor("#9AE6B4"), false).apply {
            typeface = Typeface.MONOSPACE
            background = redondo(Color.parseColor("#111111"), 10)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        raiz.addView(log, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        setContentView(ScrollView(this).apply { setBackgroundColor(GRIS); addView(raiz) })
        mostrarFallas(null)
    }

    // ---------- Bluetooth ----------
    private fun pedirPermisoYConectar() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1); return
        }
        elegirDispositivo()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) elegirDispositivo()
        else aviso("Sin permiso de Bluetooth no puedo conectarme.")
    }

    @Suppress("MissingPermission")
    private fun elegirDispositivo() {
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt == null || !bt.isEnabled) { aviso("Enciende el Bluetooth del celular."); return }
        val lista = bt.bondedDevices.toList()
        if (lista.isEmpty()) { aviso("Primero empareja tu ELM327 en los ajustes de Bluetooth (clave 1234)."); return }
        val nombres = lista.map { (it.name ?: "Sin nombre") + "\n" + it.address }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Elige tu escáner")
            .setItems(nombres) { _, i -> hilo { conectar(lista[i]) } }.show()
    }

    @Suppress("MissingPermission")
    private fun conectar(d: BluetoothDevice) {
        ui { estado.text = "● Conectando..."; estado.setTextColor(NARANJA) }
        try {
            try { socket?.close() } catch (_: Exception) {}
            var s: BluetoothSocket = d.createRfcommSocketToServiceRecord(SPP)
            try { s.connect() } catch (e: Exception) {
                escribir("Reintentando conexión alterna...")
                s = d.javaClass.getMethod("createRfcommSocket", Int::class.java).invoke(d, 1) as BluetoothSocket
                s.connect()
            }
            socket = s; entrada = s.inputStream; salida = s.outputStream
            val id = cmd("ATZ", 1500)
            listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATSP0").forEach { cmd(it) }
            cmd("0100", 3000)
            escribir("Adaptador: $id")
            ui { estado.text = "● Conectado a ${d.name}"; estado.setTextColor(VERDE) }
        } catch (e: Exception) {
            socket = null
            escribir("No se pudo conectar: ${e.message}")
            ui { estado.text = "● No se pudo conectar"; estado.setTextColor(ROJO) }
        }
    }

    @Synchronized
    private fun cmd(c: String, espera: Long = 300): String {
        val inp = entrada ?: return ""
        val out = salida ?: return ""
        while (inp.available() > 0) inp.read()
        out.write((c + "\r").toByteArray()); out.flush()
        Thread.sleep(espera)
        val sb = StringBuilder(); val inicio = System.currentTimeMillis()
        while (System.currentTimeMillis() - inicio < 5000) {
            if (inp.available() > 0) {
                val ch = inp.read().toChar()
                if (ch == '>') break
                sb.append(ch)
            } else Thread.sleep(20)
        }
        return sb.toString().trim()
    }

    private fun conectado(): Boolean {
        if (socket == null) { ui { aviso("Primero conecta el escáner.") }; return false }
        return true
    }

    private fun bytes(r: String) = r.replace("\r", " ").split(" ")
        .filter { it.length == 2 && it.all { c -> c.isDigit() || c.uppercaseChar() in 'A'..'F' } }
        .map { it.toInt(16) }

    // ---------- Funciones ----------
    private fun leerFallas() {
        if (!conectado()) return
        var d = bytes(cmd("03", 1500))
        if (0x43 in d) d = d.drop(d.indexOf(0x43) + 1)
        val codigos = mutableListOf<String>()
        var i = 0
        while (i + 1 < d.size) {
            val a = d[i]; val b = d[i + 1]
            if (a != 0 || b != 0) codigos.add("${"PCBU"[(a shr 6) and 3]}${(a shr 4) and 3}${"%X".format(a and 0xF)}${"%02X".format(b)}")
            i += 2
        }
        ui { mostrarFallas(codigos) }
        guardarHistorial(if (codigos.isEmpty()) "Sin fallas" else codigos.joinToString(" "))
    }

    private fun mostrarFallas(c: List<String>?) {
        fallas.removeAllViews()
        when {
            c == null -> fallas.addView(tarjeta("Toca \"Leer fallas\" para revisar el motor", GRIS2))
            c.isEmpty() -> fallas.addView(tarjeta("✔ Sin fallas en el motor. ¡Todo bien!", VERDE))
            else -> c.forEach {
                val (desc, nivel) = DTC[it] ?: Pair("Código no registrado, búscalo en internet", 0)
                val color = when (nivel) { 3 -> ROJO; 2 -> NARANJA; 1 -> Color.parseColor("#C9A400"); else -> GRIS2 }
                val etiqueta = when (nivel) { 3 -> "GRAVE"; 2 -> "ATENCIÓN"; 1 -> "LEVE"; else -> "?" }
                fallas.addView(tarjeta("$it  •  $etiqueta\n$desc", color))
            }
        }
    }

    private fun confirmarBorrar() {
        AlertDialog.Builder(this).setTitle("¿Borrar fallas?")
            .setMessage("Se apagará el foco de Check Engine. Si la falla sigue, volverá a aparecer.")
            .setPositiveButton("Sí, borrar") { _, _ ->
                hilo { if (conectado()) { escribir("Borrado: " + cmd("04", 1500)); ui { mostrarFallas(emptyList()) } } }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun alternarVivo() {
        if (socket == null) { aviso("Primero conecta el escáner."); return }
        vivo = !vivo
        escribir(if (vivo) "Datos en vivo activados" else "Datos en vivo detenidos")
        if (vivo) hilo {
            while (vivo) {
                val rpm = pid("0C") { (it[0] * 256 + it[1]) / 4.0 }
                val temp = pid("05") { it[0] - 40.0 }
                val vel = pid("0D") { it[0].toDouble() }
                val volt = cmd("ATRV").replace("V", "").trim().toDoubleOrNull()
                ui {
                    poner("rpm", rpm, 7000.0, 6000.0)
                    poner("temp", temp, 130.0, 105.0)
                    poner("vel", vel, 240.0, 200.0)
                    poner("volt", volt, 16.0, 15.0)
                }
                Thread.sleep(400)
            }
        }
    }

    private fun pid(p: String, f: (List<Int>) -> Double): Double? = try {
        val d = bytes(cmd("01$p")); val i = d.indexOf(p.toInt(16))
        if (i < 0) null else f(d.drop(i + 1))
    } catch (e: Exception) { null }

    private fun probarMMI() {
        if (!conectado()) return
        vivo = false
        escribir("EXPERIMENTAL: probando canal con el MMI (módulo 5F)...")
        listOf("ATSP6", "ATCAF0", "ATH1", "ATSH200", "ATCRA201").forEach { escribir("$it -> ${cmd(it)}") }
        val r = cmd("5F C0 00 10 00 03 01", 1500)
        escribir("Respuesta: " + r.ifEmpty { "(nada)" })
        escribir(if ("D0" in r) "✔ ¡El MMI respondió! Tu adaptador sí puede hablar con él."
                 else "✖ Sin respuesta. Tu ELM327 probablemente no soporta ese protocolo.")
        listOf("ATCAF1", "ATH0", "ATCRA", "ATSP0").forEach { cmd(it) }
    }

    private fun guardarHistorial(t: String) {
        val p = getSharedPreferences("historial", MODE_PRIVATE)
        val fecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es")).format(Date())
        p.edit().putString("datos", "$fecha  →  $t\n" + (p.getString("datos", "") ?: "")).apply()
    }

    private fun verHistorial() {
        val h = getSharedPreferences("historial", MODE_PRIVATE).getString("datos", "")
        AlertDialog.Builder(this).setTitle("Historial de revisiones")
            .setMessage(if (h.isNullOrEmpty()) "Aún no hay revisiones guardadas." else h)
            .setPositiveButton("Cerrar", null).show()
    }

    // ---------- Diseño ----------
    private fun medidor(clave: String, titulo: String): LinearLayout {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = redondo(GRIS2, 14); setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        val valor = texto("--", 30f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(VERDE)
        }
        caja.addView(valor)
        caja.addView(barra, LinearLayout.LayoutParams(-1, dp(10)))
        caja.addView(texto(titulo, 13f, Color.LTGRAY, false).apply { gravity = Gravity.CENTER })
        medidores[clave] = Pair(valor, barra)
        caja.layoutParams = GridLayout.LayoutParams().apply {
            width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(8), dp(4), 0)
        }
        return caja
    }

    private fun poner(k: String, v: Double?, max: Double, alerta: Double) {
        val (t, b) = medidores[k] ?: return
        if (v == null) { t.text = "--"; b.progress = 0; return }
        t.text = if (k == "volt") "%.1f".format(v) else "%.0f".format(v)
        b.progress = ((v / max) * 100).toInt().coerceIn(0, 100)
        val c = if (v >= alerta) ROJO else VERDE
        b.progressTintList = ColorStateList.valueOf(c); t.setTextColor(if (v >= alerta) ROJO else Color.WHITE)
    }

    private fun boton(t: String, color: Int = NARANJA, accion: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 16f; isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD; background = redondo(color, 14)
        setPadding(dp(8), dp(14), dp(8), dp(14)); setOnClickListener { accion() }
    }

    private fun tarjeta(t: String, color: Int) = texto(t, 15f, Color.WHITE, true).apply {
        background = redondo(color, 12); setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
    }

    private fun texto(t: String, tam: Float, color: Int, negrita: Boolean) = TextView(this).apply {
        text = t; textSize = tam; setTextColor(color)
        if (negrita) typeface = Typeface.DEFAULT_BOLD
    }

    private fun fila() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
    }

    private fun peso() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
    private fun redondo(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun hilo(f: () -> Unit) = Thread(f).start()
    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun escribir(t: String) = ui { log.append(t + "\n") }

    override fun onDestroy() {
        vivo = false
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
```
