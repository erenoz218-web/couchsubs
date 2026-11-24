// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.remoteplay

import java.nio.ByteBuffer

/** Yerel kayıt ya da oturum başlatma sonucu; alanları yerel taraf (couchsubs-jni.c) doldurur. */
class NativeOutcome {
    /** 0 başarı, 1 PS4/PS5 reddetti ya da hata, 2 yanıt yok, -1 başlatılamadı. */
    @JvmField var code: Int = -1
    /** Konsolun (PS4/PS5) bildirdiği neden (0x80108b02 gibi); yoksa 0. */
    @JvmField var reason: Int = 0
    @JvmField var nickname: String? = null
    @JvmField var registKey: ByteArray? = null
    @JvmField var morning: ByteArray? = null
    /** libchiaki'nin hata satırları, alt alta. */
    @JvmField var errors: String? = null

    val errorLines: List<String> get() = errors?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
}

/** Oturum olayları libchiaki'nin iş parçacığından gelir. */
fun interface NativeSessionListener {
    fun onEvent(type: Int, reason: Int, text: String?)
}

/**
 * Çözülen kareler çözücünün çıkış iş parçacığından gelir: [buffer] Y düzlemidir ve yalnız çağrı süresince geçerlidir.
 * Çağrı kısa sürmeli (kopyala ve dön); bu sürede çözücü yeni kare vermez.
 */
fun interface NativeFrameListener {
    /** [chroma]: [buffer]'da Y düzleminin ardından yarım çözünürlüklü U ve V düzlemleri de var ([ChromaPlanes]). */
    fun onFrame(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, colorFormat: Int, chroma: Boolean)
}

/** libchiaki'ye JNI köprüsü (app/src/main/cpp/couchsubs-jni.c). */
object NativeBridge {
    const val EVENT_CONNECTED = 1
    /** reason: 1 ise önceki şifre yanlıştı. */
    const val EVENT_LOGIN_PIN = 2
    const val EVENT_QUIT = 3
    const val QUIT_STOPPED = 1
    const val QUIT_IN_USE = 2
    const val QUIT_REFUSED = 3
    const val QUIT_NETWORK = 4
    const val QUIT_OTHER = 5
    /** PS4/PS5'teki Uzaktan Oynatma çöktü (CHIAKI_QUIT_REASON_SESSION_REQUEST_RP_CRASH). */
    const val QUIT_RP_CRASHED = 6
    /** PSN üzerinden eşleme reddedildi (CHIAKI_QUIT_REASON_PSN_REGIST_FAILED); QUIT_REFUSED yalnız TCP reddidir. */
    const val QUIT_PSN_REGIST_FAILED = 7
    /** Konsol akışı kendisi kapattı (CHIAKI_QUIT_REASON_STREAM_CONNECTION_REMOTE_DISCONNECTED / _REMOTE_SHUTDOWN); metin PS5'in nedeni. */
    const val QUIT_CONSOLE_CLOSED = 8

    /** Yerel kütüphane yüklenebildi mi (yalnız Kotlin derlemesinde ya da desteklenmeyen işlemcide false). */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("couchsubs-jni")
            init() == 0
        } catch (error: UnsatisfiedLinkError) {
            false
        }
    }

    @JvmStatic private external fun init(): Int
    /**
     * Her adrese hem PS4 (UDP 987, protokol 00020020) hem PS5 (UDP 9302, protokol 00030010) arama paketi gider. Yanıt satırı:
     * "adres\tad\tuyanık(0/1)\toyun kimliği\toyun adı\tPS4|PS5\tsistem sürümü" (host-type ve system-version alanları).
     */
    @JvmStatic external fun discover(targets: Array<String>, timeoutMs: Int): Array<String>?
    /** [ps5]: false ise PS4 uyandırma paketi (UDP 987, PS4 protokol sürümü); true ise PS5 (UDP 9302). */
    @JvmStatic external fun wake(host: String, credential: Long, ps5: Boolean): Int
    /** [target]: ChiakiTarget değeri ([RemotePlayTarget]); PS4 ve PS5 kayıt protokolleri bundan seçilir. */
    @JvmStatic external fun register(host: String, accountId: ByteArray, pin: Int, target: Int, outcome: NativeOutcome)
    @JvmStatic external fun sessionStart(
        host: String,
        /** false: PS4 oturumu (ChiakiConnectInfo.ps5 = false; yalnız H.264, PS4 RP sürümü); true: PS5. */
        ps5: Boolean,
        registKey: ByteArray, morning: ByteArray, accountId: ByteArray, width: Int, height: Int,
        frameListener: NativeFrameListener,
        /** Üreticiye göre seçilen çözücü türünün (yazılım/donanım) tersiyle başla (önceki oturumda görüntü gelmedi). */
        swapDecoder: Boolean,
        listener: NativeSessionListener, outcome: NativeOutcome,
    ): Long
    @JvmStatic external fun sessionStop(ptr: Long)
    /** Konsolun istediği giriş şifresini (ASCII rakamlar) gönderir; 0 başarı. Olay geri çağrısından çağrılmamalı. */
    @JvmStatic external fun sessionSetLoginPin(ptr: Long, pin: ByteArray): Int
    @JvmStatic external fun sessionErrors(ptr: Long): String?
    @JvmStatic external fun sessionFrames(ptr: Long): Long
}
