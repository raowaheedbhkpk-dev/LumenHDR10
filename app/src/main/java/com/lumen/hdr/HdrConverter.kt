package com.lumen.hdr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the app knows about the chosen video before converting. */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val fps: Float,
    val durationUs: Long,
    val isHdr: Boolean,
    val hasAudio: Boolean,
    val audioCopyable: Boolean,
)

class HdrConverter(private val context: Context) {

    @Volatile var cancelled = false

    companion object {
        /** Finds a hardware HEVC encoder that can write HDR10 (Main10 HDR10 profile). */
        fun findHdr10Encoder(): MediaCodecInfo? {
            val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            val candidates = all.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    }
            }
            return candidates.firstOrNull { it.isHardwareAccelerated } ?: candidates.firstOrNull()
        }

        fun supportsHdrEditing(info: MediaCodecInfo): Boolean =
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)

        private fun MediaFormat.intOr(key: String, def: Int): Int =
            if (containsKey(key)) try { getInteger(key) } catch (e: Exception) { def } else def

        private fun MediaFormat.fpsOr(def: Float): Float {
            if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return def
            return try { getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() } catch (e: Exception) {
                try { getFloat(MediaFormat.KEY_FRAME_RATE) } catch (e2: Exception) { def }
            }
        }
    }

    private fun openExtractor(uri: Uri) = MediaExtractor().apply { setDataSource(context, uri, null) }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true } ?: -1

    fun probe(uri: Uri): VideoInfo {
        val ex = openExtractor(uri)
        try {
            val v = findTrack(ex, "video/")
            require(v >= 0) { "No video track found in this file." }
            val f = ex.getTrackFormat(v)
            val transfer = f.intOr(MediaFormat.KEY_COLOR_TRANSFER, 0)
            val a = findTrack(ex, "audio/")
            val audioMime = if (a >= 0) ex.getTrackFormat(a).getString(MediaFormat.KEY_MIME) else null
            return VideoInfo(
                width = f.getInteger(MediaFormat.KEY_WIDTH),
                height = f.getInteger(MediaFormat.KEY_HEIGHT),
                rotation = f.intOr(MediaFormat.KEY_ROTATION, 0),
                fps = f.fpsOr(30f),
                durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L,
                isHdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG,
                hasAudio = a >= 0,
                audioCopyable = audioMime == MediaFormat.MIMETYPE_AUDIO_AAC,
            )
        } finally {
            ex.release()
        }
    }

    /** Output size: the source size, or upscaled so the long side is [longSide]. Always even. */
    fun outputSize(info: VideoInfo, longSide: Int?): Pair<Int, Int> {
        val w = info.width; val h = info.height
        if (longSide == null || max(w, h) >= longSide) return (w and 1.inv()) to (h and 1.inv())
        val s = longSide.toFloat() / max(w, h)
        return ((w * s).roundToInt() and 1.inv()) to ((h * s).roundToInt() and 1.inv())
    }

    /**
     * Converts [uri] to an HDR10 HEVC MP4 at [outFile].
     * [onProgress] gets 0..1. Throws with a readable message on failure.
     */
    fun convert(
        uri: Uri,
        info: VideoInfo,
        outWidth: Int,
        outHeight: Int,
        settings: HdrSettings,
        outFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val codecInfo = findHdr10Encoder() ?: error("This phone has no HDR10 video encoder.")
        val caps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val vcaps = caps.videoCapabilities
        require(vcaps.isSizeSupported(outWidth, outHeight)) {
            "The encoder can't make ${outWidth}x$outHeight video. Choose the original size."
        }
        val fps = info.fps.coerceIn(1f, 120f)
        val wanted = (outWidth.toLong() * outHeight * fps * 0.25).toLong()
        val bitrate = wanted.coerceIn(8_000_000L, 100_000_000L).toInt()
            .let { vcaps.bitrateRange.clamp(it) }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setFloat(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo(settings.peakNits))
        }

        val extractor = openExtractor(uri)
        val vTrack = findTrack(extractor, "video/")
        extractor.selectTrack(vTrack)
        val inFormat = extractor.getTrackFormat(vTrack)

        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var gl: HdrGl? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            encoder = MediaCodec.createByCodecName(codecInfo.name)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            gl = HdrGl(inputSurface, info.width, info.height)

            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inFormat, gl.decoderSurface, null, 0)
            decoder.start()

            outFile.delete()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(info.rotation)

            // Audio is copied as-is (AAC only, which is what phones record).
            var audioEx: MediaExtractor? = null
            var audioFormat: MediaFormat? = null
            if (info.audioCopyable) {
                audioEx = openExtractor(uri)
                val a = findTrack(audioEx, "audio/")
                audioEx.selectTrack(a)
                audioFormat = audioEx.getTrackFormat(a)
            }
            var videoOut = -1
            var audioOut = -1

            val bi = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            val timeout = 10_000L

            while (!encoderDone) {
                if (cancelled) throw CancelledException()

                // 1. feed compressed frames to the decoder
                if (!inputDone) {
                    val idx = decoder.dequeueInputBuffer(timeout)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. collect encoded HDR10 output
                var encoderBusy = true
                while (encoderBusy) {
                    val idx = encoder.dequeueOutputBuffer(bi, 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderBusy = false
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder format changed twice" }
                            videoOut = muxer.addTrack(encoder.outputFormat)
                            if (audioFormat != null) audioOut = muxer.addTrack(audioFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        idx >= 0 -> {
                            val out = encoder.getOutputBuffer(idx)!!
                            val isConfig = bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig && bi.size > 0 && muxerStarted) {
                                out.position(bi.offset); out.limit(bi.offset + bi.size)
                                muxer.writeSampleData(videoOut, out, bi)
                                if (info.durationUs > 0) onProgress(min(0.98f, bi.presentationTimeUs.toFloat() / info.durationUs))
                            }
                            encoder.releaseOutputBuffer(idx, false)
                            if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true; encoderBusy = false
                            }
                        }
                    }
                }

                // 3. decoded frame -> HDR shader -> encoder
                if (!decoderDone) {
                    val idx = decoder.dequeueOutputBuffer(bi, timeout)
                    if (idx >= 0) {
                        val render = bi.size > 0
                        val pts = bi.presentationTimeUs
                        val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(idx, render)
                        if (render) {
                            gl.awaitFrame()
                            gl.draw(outWidth, outHeight, settings, pts * 1000)
                        }
                        if (eos) {
                            encoder.signalEndOfInputStream()
                            decoderDone = true
                        }
                    }
                }
            }

            // 4. copy audio
            if (audioEx != null && audioOut >= 0) {
                val maxSize = if (audioFormat!!.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20
                val buf = ByteBuffer.allocateDirect(max(maxSize, 64 * 1024))
                val abi = MediaCodec.BufferInfo()
                while (true) {
                    if (cancelled) throw CancelledException()
                    val size = audioEx.readSampleData(buf, 0)
                    if (size < 0) break
                    abi.set(0, size, audioEx.sampleTime,
                        if (audioEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(audioOut, buf, abi)
                    audioEx.advance()
                }
                audioEx.release()
            }
            onProgress(1f)
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            decoder?.release()
            try { encoder?.stop() } catch (_: Exception) {}
            encoder?.release()
            gl?.release()
            extractor.release()
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
        }
    }

    /**
     * CTA-861.3 static metadata (mastering display + MaxCLL/MaxFALL), little-endian,
     * in the layout Android's KEY_HDR_STATIC_INFO expects. Mastering display: P3-D65.
     */
    private fun hdrStaticInfo(peakNits: Float): ByteBuffer {
        val b = ByteBuffer.allocateDirect(25).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0.toByte()) // descriptor type 1
        fun xy(x: Double, y: Double) { b.putShort((x / 0.00002).roundToInt().toShort()); b.putShort((y / 0.00002).roundToInt().toShort()) }
        xy(0.680, 0.320)   // red
        xy(0.265, 0.690)   // green
        xy(0.150, 0.060)   // blue
        xy(0.3127, 0.3290) // white D65
        val peak = peakNits.roundToInt()
        b.putShort(peak.toShort())       // max mastering luminance, nits
        b.putShort(50.toShort())         // min mastering luminance, 0.0001-nit units (0.005 nits)
        b.putShort(peak.toShort())       // MaxCLL
        b.putShort(min(peak, 400).toShort()) // MaxFALL
        b.flip()
        return b
    }
}

class CancelledException : Exception("Cancelled")
