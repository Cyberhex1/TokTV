package com.google.android.exoplayer2.extractor.flv;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.NalUnitUtil;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.video.AvcConfig;
import com.google.android.exoplayer2.video.HevcConfig;

import java.io.IOException;
import java.util.List;

/**
 * FLV reader for TikTok LIVE streams. Same as ExoPlayer's FlvExtractor, plus HEVC video
 * (FLV codec id 12, which TikTok and other Chinese CDNs use). Live only: no seeking.
 * Lives in ExoPlayer's flv package to reuse its AAC audio tag reader.
 */
public final class TokFlvExtractor implements Extractor {
    public static final ExtractorsFactory FACTORY = new ExtractorsFactory() {
        @Override
        public Extractor[] createExtractors() { return new Extractor[]{new TokFlvExtractor()}; }
    };

    private static final int STATE_READING_FLV_HEADER = 1;
    private static final int STATE_SKIPPING_TO_TAG_HEADER = 2;
    private static final int STATE_READING_TAG_HEADER = 3;
    private static final int STATE_READING_TAG_DATA = 4;
    private static final int TAG_TYPE_AUDIO = 8;
    private static final int TAG_TYPE_VIDEO = 9;

    private final ParsableByteArray header = new ParsableByteArray(9);
    private final ParsableByteArray tagHeader = new ParsableByteArray(11);
    private final ParsableByteArray tagData = new ParsableByteArray();
    private ExtractorOutput out;
    private int state = STATE_READING_FLV_HEADER;
    private int bytesToNextTagHeader;
    private int tagType, tagDataSize;
    private long tagTimestampUs;
    private boolean outputFirstSample, outputSeekMap;
    private long timestampOffsetUs;
    private AudioTagPayloadReader audioReader;
    private VideoReader videoReader;

    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
        byte[] b = new byte[3];
        input.peekFully(b, 0, 3);
        return b[0] == 'F' && b[1] == 'L' && b[2] == 'V';
    }

    @Override
    public void init(ExtractorOutput output) { out = output; }

    @Override
    public void seek(long position, long timeUs) {
        state = position == 0 ? STATE_READING_FLV_HEADER : STATE_READING_TAG_HEADER;
        outputFirstSample = false;
        bytesToNextTagHeader = 0;
    }

    @Override
    public void release() { }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        while (true) {
            switch (state) {
                case STATE_READING_FLV_HEADER:
                    if (!readFlvHeader(input)) return RESULT_END_OF_INPUT;
                    break;
                case STATE_SKIPPING_TO_TAG_HEADER:
                    input.skipFully(bytesToNextTagHeader);
                    bytesToNextTagHeader = 0;
                    state = STATE_READING_TAG_HEADER;
                    break;
                case STATE_READING_TAG_HEADER:
                    if (!readTagHeader(input)) return RESULT_END_OF_INPUT;
                    break;
                case STATE_READING_TAG_DATA:
                    if (readTagData(input)) return RESULT_CONTINUE;
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    private boolean readFlvHeader(ExtractorInput input) throws IOException {
        if (!input.readFully(header.getData(), 0, 9, true)) return false;
        header.setPosition(0);
        header.skipBytes(4);
        int flags = header.readUnsignedByte();
        boolean hasAudio = (flags & 0x04) != 0;
        boolean hasVideo = (flags & 0x01) != 0;
        if (hasAudio && audioReader == null) audioReader = new AudioTagPayloadReader(out.track(TAG_TYPE_AUDIO, C.TRACK_TYPE_AUDIO));
        if (hasVideo && videoReader == null) videoReader = new VideoReader(out.track(TAG_TYPE_VIDEO, C.TRACK_TYPE_VIDEO));
        out.endTracks();
        // Skip the rest of the header plus the first "previous tag size".
        bytesToNextTagHeader = header.readInt() - 9 + 4;
        state = STATE_SKIPPING_TO_TAG_HEADER;
        return true;
    }

    private boolean readTagHeader(ExtractorInput input) throws IOException {
        if (!input.readFully(tagHeader.getData(), 0, 11, true)) return false;
        tagHeader.setPosition(0);
        tagType = tagHeader.readUnsignedByte();
        tagDataSize = tagHeader.readUnsignedInt24();
        tagTimestampUs = tagHeader.readUnsignedInt24();
        tagTimestampUs = ((tagHeader.readUnsignedByte() << 24) | tagTimestampUs) * 1000L;
        tagHeader.skipBytes(3);
        state = STATE_READING_TAG_DATA;
        return true;
    }

    private boolean readTagData(ExtractorInput input) throws IOException {
        long timeUs = currentTimestampUs();
        boolean wasConsumed = true;
        boolean wasSampleOutput = false;
        if (tagType == TAG_TYPE_AUDIO && audioReader != null) {
            ensureSeekMap();
            wasSampleOutput = audioReader.consume(prepareTagData(input), timeUs);
        } else if (tagType == TAG_TYPE_VIDEO && videoReader != null) {
            ensureSeekMap();
            wasSampleOutput = videoReader.consume(prepareTagData(input), timeUs);
        } else {
            input.skipFully(tagDataSize);
            wasConsumed = false;
        }
        if (!outputFirstSample && wasSampleOutput) {
            outputFirstSample = true;
            timestampOffsetUs = -tagTimestampUs;
        }
        bytesToNextTagHeader = 4;
        state = STATE_SKIPPING_TO_TAG_HEADER;
        return wasConsumed;
    }

    private ParsableByteArray prepareTagData(ExtractorInput input) throws IOException {
        if (tagDataSize > tagData.capacity()) tagData.reset(new byte[Math.max(tagData.capacity() * 2, tagDataSize)], 0);
        else tagData.setPosition(0);
        tagData.setLimit(tagDataSize);
        input.readFully(tagData.getData(), 0, tagDataSize);
        return tagData;
    }

    private void ensureSeekMap() {
        if (!outputSeekMap) {
            out.seekMap(new SeekMap.Unseekable(C.TIME_UNSET));
            outputSeekMap = true;
        }
    }

    private long currentTimestampUs() {
        return outputFirstSample ? timestampOffsetUs + tagTimestampUs : 0;
    }

    /**
     * Receives the JSON that TikTok puts in the video's SEI messages. Multi-guest LIVEs send the
     * guest layout ("grids": x/y/w/h on the canvas) with who is talking ("talk", "talk_volume").
     * Called on the loading thread with the sample time of the frame it belongs to.
     */
    public interface SeiListener {
        void onSei(long timeUs, String json);
    }

    public static volatile SeiListener seiListener;

    /** H.264 (codec 7) and HEVC (codec 12) video tags. */
    static final class VideoReader extends TagPayloadReader {
        private long lastSeiAt, lastLayoutAt;

        private void probeSei(ParsableByteArray data, int n, long timeUs) {
            SeiListener l = seiListener;
            if (l == null || n < 16) return;
            byte b0 = data.getData()[data.getPosition()];
            boolean sei = codec == CODEC_HEVC ? ((b0 >> 1) & 0x3F) == 39 || ((b0 >> 1) & 0x3F) == 40 : (b0 & 0x1F) == 6;
            if (!sei) return;
            long now = System.currentTimeMillis();
            boolean big = n > 600;   // layout messages are ~4 KB, talk-only ones ~200 bytes
            if (now - (big ? lastLayoutAt : lastSeiAt) < (big ? 400 : 150)) return;
            byte[] d = data.getData();
            int start = data.getPosition(), end = start + n;
            int brace = -1;
            for (int i = start; i < end; i++) if (d[i] == '{') { brace = i; break; }
            if (brace < 0) return;
            int last = end - 1;
            while (last > brace && d[last] != '}') last--;
            if (last <= brace) return;
            String s = new String(d, brace, last - brace + 1, java.nio.charset.Charset.forName("UTF-8"));
            if (!s.contains("talk")) return;
            if (big) lastLayoutAt = now; else lastSeiAt = now;
            l.onSei(timeUs, s);
        }
        private static final int CODEC_AVC = 7;
        private static final int CODEC_HEVC = 12;
        private final ParsableByteArray nalStartCode = new ParsableByteArray(NalUnitUtil.NAL_START_CODE);
        private final ParsableByteArray nalLength = new ParsableByteArray(4);
        private int nalUnitLengthFieldLength;
        private boolean hasOutputFormat, hasOutputKeyframe;
        private int frameType, codec;

        VideoReader(TrackOutput output) { super(output); }

        @Override
        public void seek() { hasOutputKeyframe = false; }

        @Override
        protected boolean parseHeader(ParsableByteArray data) throws UnsupportedFormatException {
            int h = data.readUnsignedByte();
            frameType = (h >> 4) & 0x0F;
            codec = h & 0x0F;
            if (codec != CODEC_AVC && codec != CODEC_HEVC) throw new UnsupportedFormatException("Video format not supported: " + codec);
            return frameType != 5;
        }

        @Override
        protected boolean parsePayload(ParsableByteArray data, long timeUs) throws ParserException {
            int packetType = data.readUnsignedByte();
            int compositionTimeMs = data.readInt24();
            timeUs += compositionTimeMs * 1000L;
            if (packetType == 0 && !hasOutputFormat) {
                ParsableByteArray seq = new ParsableByteArray(new byte[data.bytesLeft()]);
                data.readBytes(seq.getData(), 0, data.bytesLeft());
                Format.Builder f = new Format.Builder();
                List<byte[]> init;
                if (codec == CODEC_HEVC) {
                    HevcConfig c = HevcConfig.parse(seq);
                    nalUnitLengthFieldLength = c.nalUnitLengthFieldLength;
                    init = c.initializationData;
                    f.setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(c.codecs).setWidth(c.width).setHeight(c.height)
                            .setPixelWidthHeightRatio(c.pixelWidthHeightRatio);
                } else {
                    AvcConfig c = AvcConfig.parse(seq);
                    nalUnitLengthFieldLength = c.nalUnitLengthFieldLength;
                    init = c.initializationData;
                    f.setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs(c.codecs).setWidth(c.width).setHeight(c.height)
                            .setPixelWidthHeightRatio(c.pixelWidthHeightRatio);
                }
                output.format(f.setInitializationData(init).build());
                hasOutputFormat = true;
                return false;
            } else if (packetType == 1 && hasOutputFormat) {
                boolean key = frameType == 1;
                if (!hasOutputKeyframe && !key) return false;
                byte[] lenData = nalLength.getData();
                lenData[0] = 0;
                lenData[1] = 0;
                lenData[2] = 0;
                int diff = 4 - nalUnitLengthFieldLength;
                int written = 0;
                while (data.bytesLeft() > 0) {
                    data.readBytes(lenData, diff, nalUnitLengthFieldLength);
                    nalLength.setPosition(0);
                    int n = nalLength.readUnsignedIntToInt();
                    probeSei(data, n, timeUs);
                    nalStartCode.setPosition(0);
                    output.sampleData(nalStartCode, 4);
                    written += 4;
                    output.sampleData(data, n);
                    written += n;
                }
                output.sampleMetadata(timeUs, key ? C.BUFFER_FLAG_KEY_FRAME : 0, written, 0, null);
                hasOutputKeyframe = true;
                return true;
            }
            return false;
        }
    }
}
