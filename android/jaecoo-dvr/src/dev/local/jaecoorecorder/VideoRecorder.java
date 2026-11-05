package dev.local.jaecoorecorder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Codec, frame submission and muxer operations belong to the dedicated encoder thread. */
final class VideoRecorder {
    interface Listener { void completed(String name, String uri, long frames, long bytes, long milliseconds); }
    private static final int MAX_ACCESS_UNIT = 32 * 1024 * 1024;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private MediaFormat outputFormat;
    private Storage storage;
    private volatile Storage.Destination destination;
    private boolean muxStarted, eosSeen;
    private volatile boolean rotationRequested;
    private int track = -1;
    private volatile long segmentStartPts = -1, segmentLastPts, segmentBytes, segmentFrames;
    private ByteBuffer copy;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private volatile long firstPts = -1, lastOutputPts = -1, nonMonotonic, invalidHeaders, inputUnavailable;
    private volatile Listener listener;
    volatile int width, height, targetFps, bitrate;
    volatile boolean semiPlanar, usb, hardwareAccelerated;
    volatile long frames, encodedFrames, bytes, startMs, startUs, lastPts = -1;
    volatile String codecName = "", lastCompleted = "";
    private boolean allowSoftware;
    void setAllowSoftware(boolean allow){allowSoftware=allow;}
    private String mimeType = "video/avc";
    private volatile int segmentSeconds = 60;

    void setListener(Listener value) { listener = value; }
    void setSegmentSeconds(int seconds) { segmentSeconds = Math.max(5, Math.min(300, seconds)); }
    void start(Storage s, boolean usb, int w, int h) throws Exception { start(s, usb, w, h, 25, 12000000, false, "video/avc"); }
    void start(Storage s, boolean usb, int w, int h, int fps, int rate, boolean surface) throws Exception { start(s, usb, w, h, fps, rate, surface, "video/avc"); }

    void start(Storage s, boolean usb, int wantedW, int wantedH, int fps, int rate, boolean surface, String mimePreference) throws Exception {
        if (surface) throw new IOException("ה-DVR משתמש רק בנתיב קידוד מאומת");
        if (codec != null || muxer != null || destination != null) throw new IllegalStateException("Encoder active");
        if (s == null || wantedW <= 0 || wantedH <= 0 || (wantedW & 1) != 0 || (wantedH & 1) != 0 || fps < 1 || rate < 1)
            throw new IllegalArgumentException("הגדרות מקודד לא תקינות");
        storage = s; this.usb = usb;
        outputFormat = null; copy = null; lastCompleted = "";
        frames = encodedFrames = bytes = nonMonotonic = invalidHeaders = inputUnavailable = 0;
        firstPts = lastOutputPts = lastPts = -1;
        rotationRequested = eosSeen = false;
        hardwareAccelerated = false;
        Exception last = null;
        String[] mimeTypes = "video/hevc".equals(mimePreference) ? new String[]{"video/hevc", "video/avc"} : new String[]{"video/avc", "video/hevc"};
        for(int pass=0;pass<(allowSoftware?2:1);pass++) for (String mime : mimeTypes) {
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!ci.isEncoder() || (pass==0?!ci.isHardwareAccelerated():ci.isHardwareAccelerated())) continue;
                try {
                    MediaCodecInfo.CodecCapabilities cc = ci.getCapabilitiesForType(mime);
                    if (!cc.getVideoCapabilities().areSizeAndRateSupported(wantedW, wantedH, fps)) continue;
                    int color = 0;
                    for (int value : cc.colorFormats) {
                        if (value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) { color = value; break; }
                        if (value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) color = value;
                    }
                    if (color == 0) continue;
                    bitrate = Math.max(cc.getVideoCapabilities().getBitrateRange().getLower(), Math.min(rate, cc.getVideoCapabilities().getBitrateRange().getUpper()));
                    codec = MediaCodec.createByCodecName(ci.getName());
                    MediaFormat format = MediaFormat.createVideoFormat(mime, wantedW, wantedH);
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color);
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    format.setFloat(MediaFormat.KEY_OPERATING_RATE, fps);
                    format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                    if (cc.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    codec.start();
                    width = wantedW; height = wantedH; targetFps = fps;
                    semiPlanar = color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
                    codecName = ci.getName(); mimeType = mime; hardwareAccelerated = ci.isHardwareAccelerated();
                    startMs = SystemClock.elapsedRealtime(); startUs = System.nanoTime() / 1000;
                    openSegment();
                    return;
                } catch (Exception failure) {
                    last = failure;
                    discardSegment();
                    release();
                    outputFormat = null;
                    hardwareAccelerated = false;
                }
            }
        }
        throw new IOException("לא ניתן לפתוח מקודד תואם " + wantedW + "×" + wantedH + "@" + fps, last);
    }

    private void openSegment() throws Exception {
        if (muxer != null || destination != null) throw new IllegalStateException("הקטע הקודם לא נסגר");
        try {
            destination = storage.create(usb);
            muxer = new MediaMuxer(destination.fd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            track = -1; muxStarted = false;
            segmentStartPts = -1; segmentLastPts = segmentBytes = segmentFrames = 0;
            if (outputFormat != null) startMuxer();
        } catch (Exception failure) {
            discardSegment();
            throw failure;
        }
    }

    private void startMuxer() throws IOException {
        if (muxer == null || outputFormat == null || muxStarted) throw new IOException("מצב רצועת MP4 לא תקין");
        track = muxer.addTrack(outputFormat);
        muxer.start();
        muxStarted = true;
    }

    private String completeSegment() throws Exception {
        if (muxer == null) return lastCompleted;
        MediaMuxer closing = muxer;
        Storage.Destination finished = destination;
        long count = segmentFrames, length = segmentBytes;
        long duration = count == 0 ? 0 : Math.max(1, (segmentLastPts - segmentStartPts) / 1000 + 1000 / Math.max(1, targetFps));
        boolean valid = false;
        Exception failure = null;
        try {
            if (muxStarted && count > 0) { closing.stop(); valid = true; }
        } catch (Exception error) { failure = error; }
        finally {
            try { closing.release(); } catch (Exception error) { if (failure == null) failure = error; }
            muxer = null; muxStarted = false; track = -1;
        }
        if (finished == null) throw new IOException("יעד ההקלטה אבד", failure);
        try {
            if (!valid) {
                finished.remove();
                if (failure != null) throw failure;
                return lastCompleted;
            }
            // A finalized MP4 must survive an index write failure. Storage.completed marks it first.
            storage.completed(finished, length, Math.max(1, duration / 1000));
            lastCompleted = finished.name;
        } finally {
            try { finished.close(); } finally { destination = null; }
        }
        if (failure != null) Log.w("JaecooDVR", "Finalized muxer release", failure);
        Listener callback = listener;
        if (callback != null) {
            try { callback.completed(finished.name, finished.uri.toString(), count, length, duration); }
            catch (RuntimeException error) { Log.e("JaecooDVR", "התראת השלמת הקליפ נכשלה", error); }
        }
        return lastCompleted;
    }

    boolean frame(ByteBuffer data, long timestampUs) throws Exception {
        if (codec == null || eosSeen) throw new IOException("המקודד אינו פתוח");
        int length = Math.multiplyExact(Math.multiplyExact(width, height), 3) / 2;
        if (data == null || data.limit() < length) throw new IOException("חוצץ התמונה חסר");
        drain(false);
        // This is the encoder worker, so a short wait avoids dropping startup frames without blocking UI/HAB.
        int index = codec.dequeueInputBuffer(10000);
        if (index < 0) { inputUnavailable++; return false; }
        ByteBuffer target = codec.getInputBuffer(index);
        if (target == null || target.capacity() < length) throw new IOException("גודל חוצץ המקודד לא תקין");
        target.clear();
        ByteBuffer source = data.duplicate(); source.position(0); source.limit(length);
        target.put(source);
        long pts = Math.max(Math.max(0, timestampUs), lastPts + 1);
        codec.queueInputBuffer(index, 0, length, pts, 0);
        lastPts = pts; frames++;
        drain(false);
        return true;
    }
    void frame(ByteBuffer data) throws Exception { frame(data, System.nanoTime() / 1000 - startUs); }

    void requestSegment() {
        if (codec != null && !rotationRequested) {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(parameters);
            rotationRequested = true;
        }
    }

    private void drain(boolean end) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (true) {
            if (end && SystemClock.elapsedRealtime() > deadline) throw new IOException("פסק זמן ל-EOS של המקודד");
            int index = codec.dequeueOutputBuffer(info, end ? 10000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return; continue; }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outputFormat != null || muxStarted) throw new IOException("פורמט המקודד השתנה במהלך ההקלטה");
                outputFormat = codec.getOutputFormat(); startMuxer(); continue;
            }
            if (index < 0) continue;
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    ByteBuffer source = codec.getOutputBuffer(index);
                    if (source == null || info.offset < 0 || info.size > MAX_ACCESS_UNIT || (long) info.offset + info.size > source.capacity())
                        throw new IOException("גבולות החבילות של המקודד לא תקינים");
                    if ((info.flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0) throw new IOException("המקודד הפיק תמונה מקוטעת");
                    if (!muxStarted) throw new IOException("רצועת MP4 לא מוכנה");
                    if (lastOutputPts >= 0 && info.presentationTimeUs <= lastOutputPts) {
                        nonMonotonic++; throw new IOException("חותמת הזמן של הווידאו אינה מתקדמת");
                    }
                    // Own the bytes before closing a muxer or invoking storage/listener code.
                    ByteBuffer part = source.duplicate(); part.clear(); part.position(info.offset); part.limit(info.offset + info.size);
                    if (copy == null || copy.capacity() < info.size) copy = ByteBuffer.allocateDirect(Math.max(info.size, 1024 * 1024));
                    copy.clear(); copy.put(part); copy.flip();
                    if (!validHeader(copy)) { invalidHeaders++; throw new IOException("המקודד הפיק חבילה לא תקינה; ההקלטה נעצרה"); }
                    boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (rotationRequested && keyFrame && segmentFrames > 0) {
                        completeSegment(); openSegment(); rotationRequested = false;
                    }
                    if (segmentFrames == 0 && !keyFrame) throw new IOException("הקליפ אינו מתחיל בפריים מפתח");
                    if (segmentStartPts < 0) segmentStartPts = info.presentationTimeUs;
                    MediaCodec.BufferInfo sample = new MediaCodec.BufferInfo();
                    sample.set(0, info.size, info.presentationTimeUs - segmentStartPts, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    muxer.writeSampleData(track, copy, sample);
                    segmentLastPts = info.presentationTimeUs; segmentFrames++; segmentBytes += info.size;
                    bytes += info.size; encodedFrames++; lastOutputPts = info.presentationTimeUs;
                    if (firstPts < 0) firstPts = lastOutputPts;
                    if (!end && !rotationRequested && lastOutputPts - segmentStartPts >= segmentSeconds * 1000000L) requestSegment();
                }
            } finally { codec.releaseOutputBuffer(index, false); }
            if (eos) { eosSeen = true; return; }
        }
    }

    /** Hardware encoders expose Annex B or four-byte length-prefixed NAL units. */
    private boolean validHeader(ByteBuffer data) {
        int length = data.remaining();
        if (length < 5) return false;
        int start = findStartCode(data, 0, Math.min(length, 8));
        if (start >= 0) {
            for (int i = 0; i < start; i++) if (data.get(i) != 0) return false;
            while (start >= 0) {
                int header = start + (data.get(start + 2) == 1 ? 3 : 4);
                int next = findStartCode(data, header, length);
                if (!validNal(data, header, next < 0 ? length : next)) return false;
                start = next;
            }
            return true;
        }
        int offset = 0;
        while (offset < length) {
            if (length - offset < 4) return false;
            long size = ((long) (data.get(offset) & 255) << 24) | ((long) (data.get(offset + 1) & 255) << 16)
                | ((long) (data.get(offset + 2) & 255) << 8) | (data.get(offset + 3) & 255);
            offset += 4;
            if (size < 1 || size > length - offset || !validNal(data, offset, offset + (int) size)) return false;
            offset += (int) size;
        }
        return offset == length;
    }
    private int findStartCode(ByteBuffer data, int from, int limit) {
        for (int i = from; i + 2 < limit; i++) {
            if (data.get(i) != 0 || data.get(i + 1) != 0) continue;
            if (data.get(i + 2) == 1) return i;
            if (i + 3 < limit && data.get(i + 2) == 0 && data.get(i + 3) == 1) return i;
        }
        return -1;
    }
    private boolean validNal(ByteBuffer data, int start, int end) {
        if (start >= end) return false;
        int header = data.get(start) & 255;
        if ((header & 128) != 0) return false;
        if ("video/hevc".equals(mimeType)) return start + 1 < end && ((header >> 1) & 63) <= 40 && (data.get(start + 1) & 7) != 0;
        int type = header & 31;
        return type > 0 && type <= 23;
    }

    String finish() throws Exception {
        boolean completed = false;
        try {
            if (codec != null && !eosSeen) {
                long deadline = SystemClock.elapsedRealtime() + 1000;
                int index;
                do {
                    drain(false);
                    index = codec.dequeueInputBuffer(10000);
                } while (index < 0 && SystemClock.elapsedRealtime() < deadline);
                if (index < 0) throw new IOException("אין כניסת EOS למקודד");
                codec.queueInputBuffer(index, 0, 0, Math.max(0, lastPts + 1), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drain(true);
                if (!eosSeen) throw new IOException("Encoder EOS gelmedi");
            }
            completeSegment(); completed = true;
            return lastCompleted;
        } finally {
            release();
            if (!completed) discardSegment();
            copy = null;
        }
    }

    String currentName() { Storage.Destination value = destination; return value == null ? "" : value.name; }
    String currentUri() { Storage.Destination value = destination; return value == null ? "" : value.uri.toString(); }
    long seconds() { return startMs == 0 ? 0 : Math.max(0, (SystemClock.elapsedRealtime() - startMs) / 1000); }
    double actualFps() {
        long count = encodedFrames, first = firstPts, last = lastOutputPts;
        return count == 0 || first < 0 || last < first ? 0 : count * 1000000.0 / Math.max(1, last - first + 1000000L / Math.max(1, targetFps));
    }
    long remainingBytes() {
        try {
            Storage.Destination value = destination;
            android.system.StructStatVfs stat = android.system.Os.fstatvfs(value.fd.getFileDescriptor());
            return stat.f_bavail * stat.f_frsize;
        } catch (Exception error) { return usb ? Long.MAX_VALUE : storage == null ? 0 : storage.freeBytes(); }
    }
    String diagnostics() {
        return "submitted=" + frames + " encoded=" + encodedFrames + " inputUnavailable=" + inputUnavailable
            + " invalid=" + invalidHeaders + " nonMonotonic=" + nonMonotonic + " actualFps="
            + String.format(java.util.Locale.US, "%.2f", actualFps()) + " codec=" + codecName;
    }
    String outputTimeline() { return diagnostics(); }
    private void discardSegment() {
        MediaMuxer abandoned = muxer; muxer = null; muxStarted = false; track = -1;
        if (abandoned != null) try { abandoned.release(); } catch (Exception ignored) { }
        Storage.Destination abandonedDestination = destination; destination = null;
        if (abandonedDestination != null) abandonedDestination.remove();
    }
    private void release() {
        MediaCodec closing = codec; codec = null;
        if (closing != null) {
            try { closing.stop(); } catch (Exception ignored) { }
            try { closing.release(); } catch (Exception ignored) { }
        }
    }
}
