// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.remoteplay

import com.couchsubs.mobile.core.RunningGame
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Ağda bulunan PS4 ya da PS5. [type] ve [systemVersion]: keşif yanıtındaki `host-type` ve `system-version`; eski yerel
 * kütüphane bunları vermezse [type] PS5 sayılır. [runningAppTitleId] ve [runningAppName]: o an çalışan uygulama (keşif yanıtındaki
 * `running-app-titleid`, `running-app-name`); ana ekranda ya da dinlenme modunda null.
 */
data class ConsoleInfo(
    val host: String,
    val name: String,
    val isAwake: Boolean,
    val runningAppTitleId: String? = null,
    val runningAppName: String? = null,
    val type: ConsoleType = ConsoleType.PS5,
    val systemVersion: String? = null,
)

/** Konsol ailesi. PS4 ve PS5 farklı UDP portlarında yanıt verir, farklı kayıt/oturum protokolü konuşur. */
enum class ConsoleType(val label: String, val wakeTimeoutMs: Long) {
    /** PS4 dinlenmeden yaklaşık yarım dakikada açılır. */
    PS4("PS4", 40_000L),
    PS5("PS5", 20_000L);

    val isPs5: Boolean get() = this == PS5

    companion object {
        /** Keşif satırındaki `host-type`; boş ya da bilinmiyorsa (eski yerel kütüphane) PS5. */
        fun fromWire(text: String?): ConsoleType = if (text?.trim().equals("PS4", ignoreCase = true)) PS4 else PS5
    }
}

/**
 * libchiaki'nin ChiakiTarget değerleri (Remote Play sürümü). PS4_8: yazılım 7.0'dan eski (PSN çevrimiçi kimliğiyle kayıt;
 * desteklenmez), PS4_9: 7.x, PS4_10: 8.0 ve üstü, PS5_1: PS5.
 */
object RemotePlayTarget {
    const val PS4_8 = 800
    const val PS4_9 = 900
    const val PS4_10 = 1000
    const val PS5_1 = 1000100

    fun defaultFor(type: ConsoleType): Int = if (type.isPs5) PS5_1 else PS4_10

    fun typeOf(target: Int): ConsoleType = if (target >= 1_000_000) ConsoleType.PS5 else ConsoleType.PS4

    /** PS4_8 hesap kimliği yerine çevrimiçi kimlik ister; bu uygulama yalnız hesap kimliğiyle kaydeder. */
    fun supported(target: Int): Boolean = target != PS4_8

    /** Keşifteki sistem sürümünden ("09040001": ilk iki hane ana sürüm) hedef; okunamazsa en yeni PS4 hedefi. */
    fun forConsole(type: ConsoleType, systemVersion: String?): Int {
        if (type.isPs5) return PS5_1
        val major = systemVersion?.trim()?.take(2)?.toIntOrNull()?.takeIf { it > 0 } ?: return PS4_10
        return when {
            major >= 8 -> PS4_10
            major == 7 -> PS4_9
            else -> PS4_8
        }
    }
}

/**
 * PS4 ya da PS5'e kaydedilmiş bu telefonun bağlantı bilgileri (Windows: RegisteredConsole). Şifreli saklanır.
 * [type] ve [target] saklamaya da yazılmalıdır; yazılmamış (eski) kayıtlar PS5 sayılır.
 */
class RegisteredConsole(
    val host: String,
    val name: String,
    val registKey: ByteArray,
    val morning: ByteArray,
    val type: ConsoleType = ConsoleType.PS5,
    val target: Int = RemotePlayTarget.defaultFor(type),
) {
    /** Uyandırma için kayıt anahtarının onaltılık değeri. */
    val wakeCredential: Long?
        get() {
            val end = registKey.indexOf(0).let { if (it < 0) registKey.size else it }
            val text = String(registKey, 0, end, Charsets.US_ASCII)
            return try {
                java.lang.Long.parseUnsignedLong(text, 16)
            } catch (error: NumberFormatException) {
                null
            }
        }

    fun withHost(newHost: String) = RegisteredConsole(newHost, name, registKey, morning, type, target)
}

/**
 * Kaydın kullanıcıya anlatılan sonucu (Windows: RegistrationFailure). [PAIRING_CLOSED]: PS5 ağda ama aramayı reddetti
 * (Cihazı Eşleştir ekranı kapalı ya da PS5 dinlenmede); [CONNECT_FAILED]: PS5 aramaya yanıt verdi ama eşleştirme
 * bağlantısı açılamadı; [NO_FREE_PORT]: telefonda boş port ya da soket kalmadı (GitHub #590).
 */
enum class RegistrationFailure {
    WRONG_ACCOUNT, WRONG_CODE, IN_USE, VERSION_MISMATCH, CONSOLE_CRASHED, BAD_ADDRESS, NO_ANSWER, NO_REPLY,
    PAIRING_CLOSED, CONNECT_FAILED, NO_FREE_PORT, FAILED,
}

class RegistrationException(val failure: RegistrationFailure, val reason: Int? = null, val detail: String? = null) :
    Exception("PS5 registration failed: $failure ${detail ?: ""}")

object Registration {
    private const val INVALID_PSN_ID = 0x80108b02.toInt()
    private const val REGIST_FAILED = 0x80108b09.toInt()
    private const val IN_USE = 0x80108b10.toInt()
    private const val VERSION = 0x80108b11.toInt()
    private const val CRASH = 0x80108b15.toInt()
    private const val SEARCH_ATTEMPTS = 3
    private const val REASON_PREFIX = "Reported Application Reason: "
    /** Boş port ya da soket kalmadığını söyleyen strerror metinleri (EADDRNOTAVAIL, ENOBUFS, EMFILE). */
    private val NO_FREE_PORT_ERRORS = arrayOf("assign requested address", "No buffer space", "Too many open files")

    /**
     * Bu hatadan sonra aynı kodla yeniden denensin mi. Yalnız arama yanıtsız kaldıysa: PS5 aramaya bir kez yanıt verdikten
     * sonra yeni aramayı reddeder; eskiden yapılan iki deneme daha hep reddediliyor ve kullanıcı asıl hata yerine
     * "Connection refused"u görüp PS5'in bağlanmayı reddettiğini sanıyordu (GitHub #590).
     */
    fun shouldRetry(failure: RegistrationFailure, attempt: Int) = failure == RegistrationFailure.NO_ANSWER && attempt < SEARCH_ATTEMPTS

    /** PS5'in gösterdiği 8 haneli kod; boşluk, tire, nokta atlanır. Başka karakter ya da 8'den farklı hane: null. */
    fun parsePin(text: String?): Int? {
        if (text == null) return null
        val digits = StringBuilder(8)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint) || codePoint.toChar() in "-–._") continue
            if (Character.getType(codePoint) != Character.DECIMAL_DIGIT_NUMBER.toInt()) return null
            digits.append(Character.digit(codePoint, 10))
        }
        return if (digits.length == 8) digits.toString().toLong().toInt() else null
    }

    /** PS5'in bildirdiği nedenden ya da libchiaki'nin hata satırlarından kullanıcıya anlatılacak hata. */
    fun failureFrom(reason: Int?, errors: List<String>): RegistrationFailure {
        fun any(vararg needles: String) = errors.any { line -> needles.any { line.contains(it) } }
        return when {
            reason == INVALID_PSN_ID -> RegistrationFailure.WRONG_ACCOUNT
            reason == REGIST_FAILED -> RegistrationFailure.WRONG_CODE
            reason == IN_USE -> RegistrationFailure.IN_USE
            reason == VERSION -> RegistrationFailure.VERSION_MISMATCH
            reason == CRASH -> RegistrationFailure.CONSOLE_CRASHED
            reason != null -> RegistrationFailure.FAILED
            any("getaddrinfo") -> RegistrationFailure.BAD_ADDRESS
            // Nedeni (errno) libchiaki yaması yazar (third-party/patches/chiaki-regist-connect-retry.patch).
            any("send search", "for search", "Regist connect failed", "search response") && any(*NO_FREE_PORT_ERRORS) ->
                RegistrationFailure.NO_FREE_PORT
            any("search response") && any("Connection refused") -> RegistrationFailure.PAIRING_CLOSED
            // PS5 aramaya yanıt verdi, bağlantı açılamadı: "PS5 yanıt vermedi" değildir.
            any("connect for request") -> RegistrationFailure.CONNECT_FAILED
            any("search response", "connect for search", "send search", "Regist search failed", "Regist connect failed") ->
                RegistrationFailure.NO_ANSWER
            any("response HTTP header", "receiving response content", "receive response content") -> RegistrationFailure.NO_REPLY
            else -> RegistrationFailure.FAILED
        }
    }

    fun detailFrom(errors: List<String>): String? {
        errors.firstOrNull { it.contains(REASON_PREFIX) }?.let { return it.substringAfter(REASON_PREFIX) }
        return errors.firstOrNull()
    }

    /**
     * PS5'in "Cihazı Bağla" ya da PS4'ün "Aygıt Ekle" ekranındaki kodla kayıt olur (bloklar, en çok ~30 sn × deneme).
     * [type] verilmezse konsol önce aramayla tanınır; tanınamazsa her turda önce PS5 sonra PS4 hedefiyle denenir.
     */
    fun register(host: String, accountId: ByteArray, pin: Int, type: ConsoleType? = null): RegisteredConsole {
        require(accountId.size == 8) { "PSN account id must be 8 bytes" }
        if (!NativeBridge.available) throw RegistrationException(RegistrationFailure.FAILED, detail = "native library missing")
        val found = ConsoleDiscovery.discover(host, 2000).firstOrNull()
        val resolved = type ?: found?.type
        val targets = when {
            resolved != null -> listOf(RemotePlayTarget.forConsole(resolved, found?.takeIf { it.type == resolved }?.systemVersion))
            else -> listOf(RemotePlayTarget.PS5_1, RemotePlayTarget.PS4_10)
        }
        targets.firstOrNull { !RemotePlayTarget.supported(it) }?.let {
            throw RegistrationException(RegistrationFailure.FAILED, detail = "PS4 system software older than 7.0 is not supported")
        }
        var attempt = 1
        while (true) {
            var last: RegistrationException? = null
            for (target in targets) {
                val outcome = NativeOutcome()
                NativeBridge.register(host, accountId, pin, target, outcome)
                if (outcome.code == 0) {
                    val kind = RemotePlayTarget.typeOf(target)
                    return RegisteredConsole(host, outcome.nickname ?: kind.label, outcome.registKey!!, outcome.morning!!, kind, target)
                }
                val lines = outcome.errorLines
                val reported = if (outcome.code == 1 && outcome.reason != 0) outcome.reason else null
                val failure = when (outcome.code) {
                    1 -> failureFrom(reported, lines)
                    2 -> RegistrationFailure.NO_REPLY
                    else -> RegistrationFailure.FAILED
                }
                val error = RegistrationException(failure, reported, detailFrom(lines))
                // Yanıtsız arama dışındaki hatalar yeniden denenmez (başka hedefle de aynı yere çıkar).
                if (failure != RegistrationFailure.NO_ANSWER) throw error
                last = error
            }
            // Kod konsola gitmeden biten arama aşamasında paket kaybı olabilir; yeniden denemek güvenlidir.
            if (!shouldRetry(RegistrationFailure.NO_ANSWER, attempt)) throw last!!
            attempt++
            Thread.sleep(500)
        }
    }
}

/** PS4 ve PS5'i ağda arar ve uyandırır (libchiaki'nin arama protokolü). */
object ConsoleDiscovery {
    /** [host] verilirse yalnız ona, yoksa telefonun bulunduğu /24 ağın bütün adreslerine sorar (bloklar). */
    fun discover(host: String?, timeoutMs: Int = 2000): List<ConsoleInfo> {
        if (!NativeBridge.available) return emptyList()
        val targets = if (host != null) listOf(host) else subnetAddresses()
        if (targets.isEmpty()) return emptyList()
        return NativeBridge.discover(targets.toTypedArray(), timeoutMs)?.mapNotNull { line ->
            parseLine(line)
        } ?: emptyList()
    }

    /** "adres\tad\tuyanık(0/1)[\toyun kimliği\toyun adı[\tPS4|PS5[\tsistem sürümü]]]" (yerel kütüphanenin yanıt satırı). */
    fun parseLine(line: String): ConsoleInfo? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        return ConsoleInfo(
            parts[0], parts[1], parts[2] == "1", parts.getOrNull(3)?.ifEmpty { null }, parts.getOrNull(4)?.ifEmpty { null },
            ConsoleType.fromWire(parts.getOrNull(5)), parts.getOrNull(6)?.ifEmpty { null },
        )
    }

    /** Konsolda o an çalışan oyun: yalnız bağlı konsolun adresine sorulur (bloklar; ortak havuz için). */
    fun runningGame(host: String): RunningGame? =
        discover(host, 2000).firstOrNull()?.let { RunningGame.create(it.runningAppTitleId, it.runningAppName) }

    fun wake(console: RegisteredConsole) {
        val credential = console.wakeCredential ?: throw IllegalStateException("Registration key is not a wake credential")
        NativeBridge.wake(console.host, credential, console.type.isPs5)
    }

    /**
     * Adresi değişen kayıtlı konsol: adı (kayıtta konsolun verdiği ad) ve türü ([type] verilmişse) tek eşleşen; emin değilse
     * null. Eskiden ağda tek PS5 varsa adına bakılmadan o seçiliyordu; aynı evde iki PS5 varken uygulama diğerine geçiyordu
     * (GitHub #425).
     */
    fun pickMoved(found: List<ConsoleInfo>, name: String, type: ConsoleType? = null): ConsoleInfo? =
        found.filter { isRegistered(it, name, type) }.singleOrNull()

    /**
     * Aramaya yanıt veren konsol kayıtlı konsol mu (ad eşleşmesi, boşluk ve harf büyüklüğü gözetilmez; kayıtta ad yoksa her
     * yanıt kabul edilir, Windows ve Mac ile aynı). [type] verilmişse PS4/PS5 türü de aynı olmalıdır: adresi başka türde
     * bir konsola geçmiş kayıt o konsola bağlanmaya çalışmasın.
     */
    fun isRegistered(info: ConsoleInfo, name: String, type: ConsoleType? = null): Boolean =
        (type == null || info.type == type) && (name.isBlank() || info.name.trim().equals(name.trim(), ignoreCase = true))

    /** Telefonun Wi-Fi ya da Ethernet adresinin /24 ağındaki diğer adresler. */
    fun subnetAddresses(): List<String> {
        val local = localIPv4() ?: return emptyList()
        val prefix = local.substringBeforeLast('.')
        return (1..254).map { "$prefix.$it" }.filter { it != local }
    }

    /** Özel ağdaki (192.168/10/172.16-31) ilk IPv4 adresi; hücresel ve VPN arayüzleri atlanır. */
    fun localIPv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("tun") }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .firstOrNull { isPrivate(it) }
    } catch (error: Exception) {
        null
    }

    fun isPrivate(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
    }
}
