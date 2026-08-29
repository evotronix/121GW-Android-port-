package com.eevblog.gw121.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class UnitAnnunciators(
    var nano: Boolean = false,
    var micro: Boolean = false,
    var milli: Boolean = false,
    var kilo: Boolean = false,
    var mega: Boolean = false,
    var volt: Boolean = false,
    var amp: Boolean = false,
    var ohm: Boolean = false,
    var farad: Boolean = false,
    var hertz: Boolean = false,
    var second: Boolean = false,
    var percent: Boolean = false,
    var celsius: Boolean = false,
    var fahrenheit: Boolean = false,
    var dbm: Boolean = false
)

enum class Coupling { NONE, DC, AC, ACDC }

enum class MeterMode(val raw: Int) {
    LOW_Z(0), DCV(1), ACV(2), DC_MV(3), AC_MV(4),
    TEMP(5), HZ(6), MS(7), DUTY(8),
    RESISTOR(9), CONTINUITY(10), DIODE(11), CAPACITOR(12),
    AC_UVA(13), AC_MVA(14), AC_VA(15),
    AC_UA(16), DC_UA(17), AC_MA(18), DC_MA(19), AC_A(20), DC_A(21),
    DC_UVA(22), DC_MVA(23), DC_VA(24),
    TEMP_C(25), TEMP_F(26), UNKNOWN(255);

    companion object {
        fun from(raw: Int): MeterMode = entries.find { it.raw == raw } ?: UNKNOWN
    }
}

private data class RangeSpec(val points: List<Int>, val notation: String)

class PacketV2 private constructor(val raw: ByteArray) {
    val checksumOK: Boolean = true

    companion object {
        const val LENGTH = 19
        const val START: Int = 0xF2

        private val ranges = mapOf(
            0 to RangeSpec(listOf(4), " "),
            1 to RangeSpec(listOf(1, 2, 3, 4), "    "),
            2 to RangeSpec(listOf(1, 2, 3, 4), "    "),
            3 to RangeSpec(listOf(2, 3), "mm"),
            4 to RangeSpec(listOf(2, 3), "mm"),
            5 to RangeSpec(listOf(4), " "),
            6 to RangeSpec(listOf(2, 3, 1, 2, 3), "  kkk"),
            7 to RangeSpec(listOf(1, 2, 3), "   "),
            8 to RangeSpec(listOf(4), " "),
            9 to RangeSpec(listOf(2, 3, 1, 2, 3, 1, 2), "  kkkMM"),
            10 to RangeSpec(listOf(3), " "),
            11 to RangeSpec(listOf(1, 2), "  "),
            12 to RangeSpec(listOf(3, 4, 2, 3, 4, 5), "nnuuuu"),
            13 to RangeSpec(listOf(3, 4, 4, 5), "    "),
            14 to RangeSpec(listOf(2, 3, 3, 4), "mm  "),
            15 to RangeSpec(listOf(4, 5, 2, 3), "mm  "),
            16 to RangeSpec(listOf(2, 3), "  "),
            17 to RangeSpec(listOf(2, 3), "  "),
            18 to RangeSpec(listOf(1, 2), "mm"),
            19 to RangeSpec(listOf(1, 2), "mm"),
            20 to RangeSpec(listOf(3, 1, 2), "m  "),
            21 to RangeSpec(listOf(3, 1, 2), "m  "),
            22 to RangeSpec(listOf(3, 4, 4, 5), "    "),
            23 to RangeSpec(listOf(2, 3, 3, 4), "mm  "),
            24 to RangeSpec(listOf(4, 5, 2, 3), "mm  "),
            25 to RangeSpec(listOf(4), " "),
            26 to RangeSpec(listOf(4), " ")
        )

        fun parse(bytes: ByteArray): PacketV2? {
            if (bytes.size < LENGTH) return null
            if (bytes[0].toInt() and 0xFF != START) return null
            var xor = 0
            for (i in 0 until 18) xor = xor xor (bytes[i].toInt() and 0xFF)
            if (xor != (bytes[18].toInt() and 0xFF)) return null
            return PacketV2(bytes.copyOf(LENGTH))
        }
    }

    private fun u(i: Int) = raw[i].toInt() and 0xFF
    private fun nibble(i: Int, high: Boolean): Int {
        val v = u(i)
        return if (high) (v shr 4) and 0x0F else v and 0x0F
    }

    val modeRaw: Int get() = u(5) and 0x1F
    val mode: MeterMode get() = MeterMode.from(modeRaw)

    val isResistanceMode: Boolean get() = mode == MeterMode.RESISTOR || mode == MeterMode.CONTINUITY
    val isDiodeMode: Boolean get() = mode == MeterMode.DIODE
    val isContinuityMode: Boolean get() = mode == MeterMode.CONTINUITY

    val isACMilliVolt: Boolean get() = modeRaw == 4 || mode == MeterMode.AC_MV
    val isDCMilliVolt: Boolean get() = modeRaw == 3 || mode == MeterMode.DC_MV
    val isACVolt: Boolean get() = modeRaw == 2 || mode == MeterMode.ACV
    val isDCVolt: Boolean get() = modeRaw == 1 || mode == MeterMode.DCV || modeRaw == 0

    val isTemperature: Boolean
        get() {
            if (isACMilliVolt || isACVolt) return false
            if (iconAC || (iconDC && iconAC)) return false
            if (acdcCode == 2 || acdcCode == 3) return false
            return modeRaw == 5 || modeRaw == 25 || modeRaw == 26
        }

    val isSetupSubMode: Boolean
        get() = when (subModeRaw) {
            100, 101, 105, 110, 120, 125, 130, 135, 140, 150, 160, 170, 175, 180, 190 -> true
            else -> false
        }

    val dualDisplayIsFrequency: Boolean
        get() {
            if (iconDBM || isSetupSubMode) return false
            if (icon1kHz || subModeRaw == 6) return true
            if ((isACMilliVolt || isACVolt) && (subModeRaw == 0 || subModeRaw == 6)) return true
            return false
        }

    val isMainOverflow: Boolean get() = (nibble(6, true) and 0x8) != 0
    val isMainNegative: Boolean get() = (nibble(6, true) and 0x4) != 0
    val mainC: Boolean get() = (nibble(6, true) and 0x2) != 0
    val mainF: Boolean get() = (nibble(6, true) and 0x1) != 0
    val mainRangeIndex: Int get() = nibble(6, false)

    val mainIntValue: Int
        get() {
            val hi = ((u(5) shr 6) and 0x03) shl 16
            return hi or (u(7) shl 8) or u(8)
        }

    private val spec: RangeSpec get() = ranges[mode.raw] ?: RangeSpec(listOf(3), " ")
    private val clampedRangeIndex: Int get() = min(max(0, mainRangeIndex), max(0, spec.points.size - 1))

    val mainRangeValue: Int
        get() {
            val pts = spec.points
            if (pts.isEmpty()) return 3
            return pts[min(max(0, clampedRangeIndex), pts.lastIndex)]
        }

    val mainNotation: Char
        get() {
            val n = spec.notation
            if (n.isEmpty()) return ' '
            return n[min(max(0, clampedRangeIndex), n.lastIndex)]
        }

    val mainDecimalPlaces: Int get() = max(0, 5 - mainRangeValue)

    val mainValue: Double
        get() {
            if (isMainOverflow) return Double.NaN
            val denom = 10.0.pow(mainDecimalPlaces.toDouble())
            var v = mainIntValue.toDouble() / denom
            if (isMainNegative) v = -v
            return v
        }

    val rangeMultiple: Double
        get() = when (mainNotation) {
            'n' -> 1e-9
            'u' -> 1e-6
            'm' -> 1e-3
            'k', 'K' -> 1e3
            'M' -> 1e6
            else -> 1.0
        }

    val siValue: Double
        get() {
            val v = mainValue
            return if (v.isFinite()) v * rangeMultiple else v
        }

    val formattedMainValue: String
        get() {
            if (isMainOverflow) return "OL"
            val n = abs(mainIntValue) % 100_000
            var s = "%05d".format(n)
            val cut = min(max(mainRangeValue, 1), 5)
            if (cut < 5) s = s.substring(0, cut) + "." + s.substring(cut)
            if (s.contains('.')) {
                val chars = s.toCharArray()
                var i = 0
                while (i < chars.size - 1 && chars[i] == '0' && chars[i + 1] != '.') {
                    chars[i] = ' '
                    i++
                }
                return String(chars)
            }
            return s
        }

    val mainUnit: String
        get() {
            if (isTemperature) return if (modeRaw == 26 || (mainF && !mainC)) "°F" else "°C"
            if (isACMilliVolt || isDCMilliVolt) return "mV"
            if ((mode == MeterMode.DCV || mode == MeterMode.ACV || mode == MeterMode.LOW_Z) && mainNotation == 'm') return "mV"
            return unitString(mode, mainNotation)
        }

    val displayString: String
        get() {
            if (isMainOverflow) return "OL $mainUnit".trim()
            return "${formattedMainValue} $mainUnit".trim()
        }

    val measureFingerprint: String get() = "$modeRaw:$acdcCode"

    val barIsUsed: Boolean get() = (u(13) and 0x10) == 0
    val barUses150Scale: Boolean get() = (u(13) and 0x08) != 0
    val barIsNegative: Boolean get() = (u(13) and 0x04) != 0
    val barRangeIndex: Int get() = u(13) and 0x03
    val analogBarRaw: Int get() = u(14) and 0x1F

    val barNominalScale: Double
        get() = when (barRangeIndex) {
            0 -> 5.0
            1 -> 50.0
            2 -> 500.0
            else -> 1000.0
        }

    val barFullScale: Double
        get() = when (mode) {
            MeterMode.AC_UA, MeterMode.DC_UA -> if (barRangeIndex == 1) 500.0 else 50.0
            MeterMode.AC_MVA, MeterMode.DC_MVA -> when (barRangeIndex) {
                0 -> 50.0; 1 -> 500.0; 2 -> 5_000.0; else -> 25_000.0
            }
            MeterMode.AC_MA, MeterMode.DC_MA, MeterMode.AC_A, MeterMode.DC_A -> when (barRangeIndex) {
                0 -> 500.0; 1 -> 5.0; else -> 1000.0
            }
            MeterMode.DIODE -> if (mainRangeIndex == 1) 15.0 else 3.0
            else -> barNominalScale
        }

    val barScaleUnit: String
        get() = when (mode) {
            MeterMode.AC_UA, MeterMode.DC_UA -> "µA"
            MeterMode.AC_MA, MeterMode.DC_MA, MeterMode.AC_A, MeterMode.DC_A ->
                if (barRangeIndex == 1) "A" else "mA"
            MeterMode.AC_UVA, MeterMode.DC_UVA -> "µVA"
            MeterMode.AC_MVA, MeterMode.DC_MVA -> "mVA"
            MeterMode.AC_VA, MeterMode.DC_VA -> "VA"
            MeterMode.DIODE -> "V"
            MeterMode.DC_MV, MeterMode.AC_MV -> "mV"
            MeterMode.LOW_Z, MeterMode.DCV, MeterMode.ACV -> "V"
            MeterMode.RESISTOR, MeterMode.CONTINUITY -> "Ω"
            else -> mainUnit
        }

    val subModeRaw: Int get() = u(9)
    val isSubOverflow: Boolean get() = (nibble(10, true) and 0x8) != 0
    val isSubNegative: Boolean get() = (nibble(10, true) and 0x4) != 0
    val subPoint: Int get() = nibble(10, false)
    val subIntValue: Int get() = (u(11) shl 8) or u(12)

    val subValue: Double
        get() {
            if (isSubOverflow) return Double.NaN
            val p = min(max(subPoint, 0), 6)
            var v = subIntValue.toDouble() / 10.0.pow(p.toDouble())
            if (isSubNegative) v = -v
            return v
        }

    val icon1: Int get() = u(15)
    val icon2: Int get() = u(16)
    val icon3: Int get() = u(17)

    val icon1kHz: Boolean get() = (icon1 and 0x40) != 0
    val icon1ms: Boolean get() = (icon1 and 0x20) != 0
    val iconAUTO: Boolean get() = (icon1 and 0x04) != 0
    val iconAPO: Boolean get() = (icon1 and 0x02) != 0
    val iconBAT: Boolean get() = (icon1 and 0x01) != 0
    val iconBT: Boolean get() = (icon2 and 0x40) != 0
    val iconREL: Boolean get() = (icon2 and 0x10) != 0
    val iconDBM: Boolean get() = (icon2 and 0x08) != 0
    val iconMinMax: Boolean get() = (icon2 and 0x07) != 0
    val iconTEST: Boolean get() = (icon3 and 0x40) != 0
    val iconMEM: Boolean get() = (icon3 and 0x30) != 0
    val iconHOLD: Boolean get() = ((icon3 shr 2) and 0x3) != 0
    val iconAC: Boolean get() = (icon3 and 0x02) != 0
    val iconDC: Boolean get() = (icon3 and 0x01) != 0
    val iconCONT: Boolean get() = isContinuityMode
    val iconLowZ: Boolean get() = mode == MeterMode.LOW_Z

    val acdcCode: Int get() = (icon1 shr 3) and 0x3
    val iconACDC: Boolean get() = acdcCode == 3 || (iconDC && iconAC)

    val isDCMode: Boolean
        get() = mode in setOf(
            MeterMode.LOW_Z, MeterMode.DCV, MeterMode.DC_MV, MeterMode.DC_UA,
            MeterMode.DC_MA, MeterMode.DC_A, MeterMode.DC_UVA, MeterMode.DC_MVA, MeterMode.DC_VA
        )
    val isACMode: Boolean
        get() = mode in setOf(
            MeterMode.ACV, MeterMode.AC_MV, MeterMode.AC_UA, MeterMode.AC_MA,
            MeterMode.AC_A, MeterMode.AC_UVA, MeterMode.AC_MVA, MeterMode.AC_VA
        )

    val coupling: Coupling
        get() = when (acdcCode) {
            3 -> Coupling.ACDC
            2 -> Coupling.AC
            1 -> Coupling.DC
            else -> when {
                iconDC && iconAC -> Coupling.ACDC
                iconAC -> Coupling.AC
                iconDC -> Coupling.DC
                isACMode -> Coupling.AC
                isDCMode -> Coupling.DC
                else -> Coupling.NONE
            }
        }

    val subIsTemperature: Boolean
        get() {
            if (isSetupSubMode) return subModeRaw == 100 || subModeRaw == 105
            if (dualDisplayIsFrequency || isACMilliVolt || isACVolt) return false
            return subModeRaw == 5 || subModeRaw == 25 || subModeRaw == 26
        }

    val subUnit: String
        get() {
            if (iconDBM) return "dBm"
            if (dualDisplayIsFrequency) return if (icon1kHz || abs(subValue) >= 1000) "kHz" else "Hz"
            setupSubPresentation()?.let { return if (it.unit.isEmpty()) it.label else it.unit }
            if (subIsTemperature) return if (subModeRaw == 26 || subModeRaw == 105) "°F" else "°C"
            val sm = subModeRaw
            if (sm == 0) return ""
            val m = MeterMode.from(sm and 0x1F)
            if (sm < 25 && m != MeterMode.UNKNOWN) return unitString(m, ' ')
            return ""
        }

    data class SubPres(val digits: String, val unit: String, val label: String)

    fun setupSubPresentation(): SubPres? {
        if (iconDBM && !isSetupSubMode) {
            val v = if (subValue.isFinite()) subValue else abs(subIntValue).toDouble() / 10.0
            return SubPres("%.1f".format(v), "dBm", "dBm")
        }
        if (subModeRaw == 120 || subModeRaw == 125) {
            when (mode) {
                MeterMode.CAPACITOR, MeterMode.HZ, MeterMode.MS, MeterMode.DUTY,
                MeterMode.RESISTOR, MeterMode.CONTINUITY, MeterMode.DIODE,
                MeterMode.AC_UVA, MeterMode.AC_MVA, MeterMode.AC_VA,
                MeterMode.DC_UVA, MeterMode.DC_MVA, MeterMode.DC_VA -> return null
                else -> {}
            }
        }
        val nSub = abs(subIntValue)
        val nMain = abs(mainIntValue)
        val n = if (nSub != 0) nSub else nMain
        val vSub = subValue
        val vMain = mainValue
        val v = when {
            vSub.isFinite() && abs(vSub) > 0 -> abs(vSub)
            vMain.isFinite() -> abs(vMain)
            else -> n.toDouble()
        }
        return when (subModeRaw) {
            5, 25, 100 -> SubPres("%.1f".format(v), "°C", "Temperature")
            26 -> if (isACMilliVolt || isACVolt) formatSubFrequency()
            else SubPres("%.1f".format(v), "°F", "Temperature")
            105 -> SubPres("%.1f".format(v), "°F", "Temperature")
            110 -> SubPres("%.1f".format(v), "V", "Battery")
            120 -> SubPres("on", "", "Auto Power OFF")
            125 -> SubPres("oFF", "", "Auto Power OFF")
            101 -> SubPres(if (n == 0) "oFF" else "on", "", "BEEP")
            130 -> {
                var y = n % 100_000
                if (y < 100) y += 2000
                if (y > 2099 && y <= 20_999) y /= 10
                if (y !in 1990..2099) y = 2000 + (n % 100)
                SubPres("%04d".format(y), "", "YEAR")
            }
            135 -> {
                val mm = min(12, (n shr 8) and 0xFF)
                val dd = min(31, n and 0xFF)
                SubPres("%02d-%02d".format(mm, dd), "", "DATE")
            }
            140 -> {
                val hh = min(23, (n shr 8) and 0xFF)
                val mm = min(59, n and 0xFF)
                SubPres("%02d-%02d".format(hh, mm), "", "TIME")
            }
            150 -> if (measurementPosition == MeasurementPosition.CURRENT ||
                measurementPosition == MeasurementPosition.MICRO_CURRENT
            ) {
                if (subPoint == 0 && n == 0) SubPres("bd.0FF", "", "BURDEN")
                else if (subPoint == 0 && n == 1) SubPres("bd.0n", "", "BURDEN")
                else SubPres("%.1f".format(v), "mV", "BURDEN")
            } else null
            160 -> SubPres("${n % 10}", "", "LCD Contrast")
            170, 175 -> SubPres("%05d".format(n % 100_000), "", "Calibration")
            180 -> SubPres("%.1f".format(if (vSub.isFinite()) vSub else n / 10.0), "dBm", "dBm")
            190 -> SubPres("%05d".format(n % 100_000), "", "In")
            6 -> formatSubFrequency()
            else -> {
                if (dualDisplayIsFrequency) formatSubFrequency()
                else continuityBeeperOhms?.let { SubPres("%.0f".format(it), "Ω", "BEEP") }
            }
        }
    }

    private fun formatSubFrequency(): SubPres {
        val rawN = abs(subIntValue)
        var v = subValue
        if (!v.isFinite()) v = rawN.toDouble()
        val d = min(max(subPoint, 0), 4)
        if (v == 0.0 && rawN == 0) return SubPres("0", "Hz", "Hz")
        if (icon1kHz || abs(v) >= 1000) {
            val shown = if (abs(v) >= 1000) abs(v) / 1000.0 else abs(v)
            return SubPres("%.${d}f".format(shown), "kHz", "Hz")
        }
        return SubPres("%.${d}f".format(abs(v)), "Hz", "Hz")
    }

    val formattedSubValue: String
        get() {
            setupSubPresentation()?.let { return if (it.unit.isEmpty()) it.digits else "${it.digits} ${it.unit}" }
            if (isSubOverflow) return "OL"
            val v = subValue
            if (v.isNaN()) return "-"
            val d = min(max(subPoint, 0), 4)
            val num = "%.${d}f".format(v)
            return if (subUnit.isEmpty()) num else "$num $subUnit"
        }

    val continuityBeeperOhms: Double?
        get() {
            if (!isContinuityMode && !isResistanceMode) return null
            when (subModeRaw) {
                0, 5, 6, 9, 10, 25, 26, 100, 105, 110, 120, 125, 130, 135, 140, 150, 160, 180, 190 -> return null
            }
            val nSub = abs(subIntValue)
            val nMain = abs(mainIntValue)
            if (nSub == 30 || nSub == 300) return nSub.toDouble()
            if (nMain == 30 || nMain == 300) return nMain.toDouble()
            return null
        }

    val serialDigits: String
        get() = "%d%d%d%d%d".format(
            nibble(2, false), nibble(3, true), nibble(3, false), nibble(4, true), nibble(4, false)
        )

    private fun unitString(mode: MeterMode, notation: Char): String {
        fun prefixed(base: String) = when (notation) {
            'n' -> "n$base"; 'u' -> "µ$base"; 'm' -> "m$base"
            'k', 'K' -> "k$base"; 'M' -> "M$base"; else -> base
        }
        return when (mode) {
            MeterMode.LOW_Z, MeterMode.DCV, MeterMode.ACV, MeterMode.DIODE -> prefixed("V")
            MeterMode.DC_MV, MeterMode.AC_MV -> "mV"
            MeterMode.TEMP, MeterMode.TEMP_C -> "°C"
            MeterMode.TEMP_F -> "°F"
            MeterMode.HZ -> prefixed("Hz")
            MeterMode.MS -> "ms"
            MeterMode.DUTY -> "%"
            MeterMode.RESISTOR, MeterMode.CONTINUITY -> prefixed("Ω")
            MeterMode.CAPACITOR -> prefixed("F")
            MeterMode.AC_UA, MeterMode.DC_UA -> "µA"
            MeterMode.AC_MA, MeterMode.DC_MA -> "mA"
            MeterMode.AC_A, MeterMode.DC_A -> if (notation == 'm') "mA" else "A"
            MeterMode.AC_UVA, MeterMode.DC_UVA -> if (notation == 'm') "mVA" else "µVA"
            MeterMode.AC_MVA, MeterMode.DC_MVA -> if (notation == 'm') "mVA" else prefixed("VA")
            MeterMode.AC_VA, MeterMode.DC_VA -> prefixed("VA")
            else -> ""
        }
    }
}
