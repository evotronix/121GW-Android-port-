package com.eevblog.gw121.model

enum class MeasurementPosition(val title: String, val functions: String) {
    VOLTAGE("V / Low-Z", "DC V · AC V · DC+AC V · Low-Z"),
    MILLIVOLT_TEMPERATURE("mV / Temp", "DC mV · AC mV · Temperature"),
    RESISTANCE("Ω / Continuity / Diode / Cap", "Resistance · Continuity/Cable Break · Diode · Capacitance"),
    FREQUENCY("Hz / Pulse Width / Duty", "Frequency · Pulse Width · Duty Cycle"),
    CURRENT("A / mA", "DC A/mA · AC A/mA"),
    MICRO_CURRENT("µA / µVA", "DC µA · AC µA · DC µVA · AC µVA"),
    POWER_CURRENT("mVA / VA", "DC mVA/VA · AC mVA/VA"),
    UNKNOWN("Unknown", "—")
}

enum class SetupItem(val title: String) {
    TEMPERATURE("Temperature"),
    BATTERY("Battery"),
    AUTO_POWER_OFF("Auto Power Off"),
    BUZZER("Buzzer"),
    LCD_CONTRAST("LCD contrast"),
    YEAR("Year"),
    DATE("Date"),
    TIME("Time"),
    METER_ID("Multimeter ID"),
    LOGGING_INTERVAL("Logging interval"),
    CONTINUITY_THRESHOLD("Continuity / cable break"),
    BURDEN_VOLTAGE("Burden voltage"),
    DIODE_RANGE("Diode test range"),
    FREQUENCY_MODE("Frequency function"),
    BACKLIGHT("Backlight"),
    UNKNOWN("Setup")
}

data class SetupDescriptor(val item: SetupItem, val title: String, val reading: String)

val PacketV2.measurementPosition: MeasurementPosition
    get() = when (mode) {
        MeterMode.LOW_Z, MeterMode.DCV, MeterMode.ACV -> MeasurementPosition.VOLTAGE
        MeterMode.DC_MV, MeterMode.AC_MV, MeterMode.TEMP, MeterMode.TEMP_C, MeterMode.TEMP_F ->
            MeasurementPosition.MILLIVOLT_TEMPERATURE
        MeterMode.RESISTOR, MeterMode.CONTINUITY, MeterMode.DIODE, MeterMode.CAPACITOR ->
            MeasurementPosition.RESISTANCE
        MeterMode.HZ, MeterMode.MS, MeterMode.DUTY -> MeasurementPosition.FREQUENCY
        MeterMode.AC_MA, MeterMode.DC_MA, MeterMode.AC_A, MeterMode.DC_A -> MeasurementPosition.CURRENT
        MeterMode.AC_UA, MeterMode.DC_UA, MeterMode.AC_UVA, MeterMode.DC_UVA -> MeasurementPosition.MICRO_CURRENT
        MeterMode.AC_MVA, MeterMode.DC_MVA, MeterMode.AC_VA, MeterMode.DC_VA -> MeasurementPosition.POWER_CURRENT
        else -> MeasurementPosition.UNKNOWN
    }

fun PacketV2.setupDescriptor(): SetupDescriptor? {
    when (subModeRaw) {
        100 -> return SetupDescriptor(SetupItem.TEMPERATURE, "Temperature", "°C")
        105 -> return SetupDescriptor(SetupItem.TEMPERATURE, "Temperature", "°F")
        101 -> return SetupDescriptor(SetupItem.BUZZER, "Buzzer", if (kotlin.math.abs(subIntValue) == 0) "OFF" else "ON")
        110 -> return SetupDescriptor(
            SetupItem.BATTERY, "Battery",
            when {
                subValue.isFinite() -> "%.2f V".format(subValue)
                mainValue.isFinite() -> "%.2f V".format(mainValue)
                else -> "—"
            }
        )
        120 -> return SetupDescriptor(SetupItem.AUTO_POWER_OFF, "Auto Power Off", "ON")
        125 -> return SetupDescriptor(SetupItem.AUTO_POWER_OFF, "Auto Power Off", "OFF")
        130 -> {
            val n = kotlin.math.abs(subIntValue)
            var y = n % 100_000
            if (y < 100) y += 2000
            if (y !in 1990..2099) y = 2000 + (n % 100)
            return SetupDescriptor(SetupItem.YEAR, "Year", "%04d".format(y))
        }
        135 -> {
            val n = kotlin.math.abs(subIntValue)
            val mm = minOf(12, (n shr 8) and 0xFF)
            val dd = minOf(31, n and 0xFF)
            return SetupDescriptor(SetupItem.DATE, "Date", "%02d-%02d".format(mm, dd))
        }
        140 -> {
            val n = kotlin.math.abs(subIntValue)
            val hh = minOf(23, (n shr 8) and 0xFF)
            val mm = minOf(59, n and 0xFF)
            return SetupDescriptor(SetupItem.TIME, "Time", "%02d:%02d".format(hh, mm))
        }
        150 -> if (mode in setOf(MeterMode.AC_MA, MeterMode.DC_MA, MeterMode.AC_A, MeterMode.DC_A, MeterMode.AC_UA, MeterMode.DC_UA)) {
            val n = kotlin.math.abs(subIntValue)
            val reading = when {
                subPoint == 0 && n == 0 -> "OFF"
                subPoint == 0 && n == 1 -> "ON"
                subValue.isFinite() -> "%.1f mV".format(subValue)
                mainValue.isFinite() -> "%.1f mV".format(mainValue)
                else -> "—"
            }
            return SetupDescriptor(SetupItem.BURDEN_VOLTAGE, "Burden voltage", reading)
        }
        160 -> return SetupDescriptor(SetupItem.LCD_CONTRAST, "LCD contrast", "${kotlin.math.abs(subIntValue) % 10}")
        170, 175 -> {
            if (iconTEST) return SetupDescriptor(SetupItem.UNKNOWN, "Calibration", "%05d".format(kotlin.math.abs(subIntValue) % 100_000))
            return SetupDescriptor(SetupItem.LOGGING_INTERVAL, "Logging interval", "In ${kotlin.math.abs(subIntValue)}")
        }
        190 -> return SetupDescriptor(SetupItem.METER_ID, "Multimeter ID", "%05d".format(kotlin.math.abs(subIntValue) % 100_000))
    }
    if (mode == MeterMode.CONTINUITY) {
        continuityBeeperOhms?.let {
            return SetupDescriptor(SetupItem.CONTINUITY_THRESHOLD, "Continuity threshold", "%.0f Ω".format(it))
        }
    }
    if (iconDBM) return null
    return null
}

val PacketV2.graphUnit: String
    get() = when (mode) {
        MeterMode.LOW_Z, MeterMode.DCV, MeterMode.ACV, MeterMode.DIODE -> "V"
        MeterMode.DC_MV, MeterMode.AC_MV -> "mV"
        MeterMode.TEMP, MeterMode.TEMP_C -> "°C"
        MeterMode.TEMP_F -> "°F"
        MeterMode.HZ -> "Hz"
        MeterMode.MS -> "ms"
        MeterMode.DUTY -> "%"
        MeterMode.RESISTOR, MeterMode.CONTINUITY -> "Ω"
        MeterMode.CAPACITOR -> "µF"
        MeterMode.AC_UA, MeterMode.DC_UA -> "µA"
        MeterMode.AC_MA, MeterMode.DC_MA -> "mA"
        MeterMode.AC_A, MeterMode.DC_A -> "A"
        MeterMode.AC_UVA, MeterMode.DC_UVA -> "µVA"
        MeterMode.AC_MVA, MeterMode.DC_MVA -> "mVA"
        MeterMode.AC_VA, MeterMode.DC_VA -> "VA"
        else -> mainUnit
    }

val PacketV2.graphValue: Double
    get() {
        val v = mainValue
        if (!v.isFinite()) return Double.NaN
        return when (mode) {
            MeterMode.LOW_Z, MeterMode.DCV, MeterMode.ACV, MeterMode.DIODE -> siValue
            MeterMode.DC_MV, MeterMode.AC_MV -> siValue * 1_000.0
            MeterMode.TEMP, MeterMode.TEMP_C, MeterMode.TEMP_F, MeterMode.HZ, MeterMode.DUTY, MeterMode.MS -> v
            MeterMode.RESISTOR, MeterMode.CONTINUITY -> siValue
            MeterMode.CAPACITOR -> siValue * 1_000_000.0
            MeterMode.AC_UA, MeterMode.DC_UA -> mainValue
            MeterMode.AC_MA, MeterMode.DC_MA -> siValue * 1_000.0
            MeterMode.AC_A, MeterMode.DC_A -> siValue
            MeterMode.AC_UVA, MeterMode.DC_UVA -> mainValue
            MeterMode.AC_MVA, MeterMode.DC_MVA -> siValue * 1_000.0
            MeterMode.AC_VA, MeterMode.DC_VA -> siValue
            else -> v
        }
    }

private fun PacketV2.fallbackFullScale(): Double {
    val count = 50_000.0
    val p = maxOf(0, mainRangeValue)
    val display = count / Math.pow(10.0, maxOf(0, 5 - p).toDouble())
    val scaled = display * rangeMultiple
    return when (graphUnit) {
        "mV", "mA", "mVA" -> maxOf(kotlin.math.abs(scaled * 1_000), 1e-12)
        "µA", "µVA", "µF" -> maxOf(kotlin.math.abs(scaled * 1_000_000), 1e-12)
        else -> maxOf(kotlin.math.abs(scaled), 1e-12)
    }
}

private fun <T> List<T>.safe(i: Int): T? = if (i in indices) this[i] else null

val PacketV2.graphFullScale: Double
    get() {
        val i = maxOf(0, mainRangeIndex)
        return when (mode) {
            MeterMode.LOW_Z, MeterMode.DCV, MeterMode.ACV -> listOf(5.0, 50.0, 500.0, 1000.0).safe(i) ?: fallbackFullScale()
            MeterMode.DC_MV, MeterMode.AC_MV -> listOf(50.0, 500.0).safe(i) ?: fallbackFullScale()
            MeterMode.TEMP, MeterMode.TEMP_C -> 1350.0
            MeterMode.TEMP_F -> 2450.0
            MeterMode.HZ -> listOf(100.0, 1000.0, 10_000.0, 100_000.0, 1_000_000.0).safe(i) ?: fallbackFullScale()
            MeterMode.MS -> listOf(10.0, 100.0, 1000.0).safe(i) ?: fallbackFullScale()
            MeterMode.DUTY -> 100.0
            MeterMode.RESISTOR -> listOf(50.0, 500.0, 5_000.0, 50_000.0, 500_000.0, 5_000_000.0, 50_000_000.0).safe(i) ?: fallbackFullScale()
            MeterMode.CONTINUITY -> 50.0
            MeterMode.DIODE -> listOf(3.0, 15.0).safe(i) ?: fallbackFullScale()
            MeterMode.CAPACITOR -> listOf(0.01, 0.1, 1.0, 10.0, 100.0, 9999.0).safe(i) ?: fallbackFullScale()
            MeterMode.AC_UA, MeterMode.DC_UA -> listOf(50.0, 500.0).safe(i) ?: fallbackFullScale()
            MeterMode.AC_MA, MeterMode.DC_MA -> listOf(5.0, 50.0).safe(i) ?: fallbackFullScale()
            MeterMode.AC_A, MeterMode.DC_A -> listOf(0.5, 5.0, 10.0).safe(i) ?: fallbackFullScale()
            MeterMode.AC_UVA, MeterMode.DC_UVA -> listOf(250.0, 2_500.0, 25_000.0).safe(i) ?: fallbackFullScale()
            MeterMode.AC_MVA, MeterMode.DC_MVA -> listOf(25.0, 250.0, 2_500.0, 25_000.0).safe(i) ?: fallbackFullScale()
            MeterMode.AC_VA, MeterMode.DC_VA -> listOf(50.0, 500.0).safe(i) ?: fallbackFullScale()
            else -> fallbackFullScale()
        }
    }

val PacketV2.graphLowerBound: Double
    get() = when (mode) {
        MeterMode.RESISTOR, MeterMode.CONTINUITY, MeterMode.DIODE, MeterMode.CAPACITOR,
        MeterMode.HZ, MeterMode.MS, MeterMode.DUTY -> 0.0
        else -> -graphFullScale
    }
