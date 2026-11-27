package io.github.kupfermeister.lullaby.mosaic;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * Audio files in and out: .ogg (JOrbis), .mp3 (JLayer) and .wav (Java's own) to mono samples, resampling, and
 * writing a .wav. Any thread (plain data only).
 */
final class AudioFiles {

    private AudioFiles() {
    }

    /** Mono samples (-1..1) and their rate. {@code cut}: true if the file was longer than the samples read. */
    record Pcm(float[] samples, int rate, boolean cut) {
    }

    static final java.util.List<String> INPUT_TYPES = java.util.List.of("wav", "mp3", "ogg");

    static String type(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** An input file by its ending; at most maxSeconds (0: all). */
    static Pcm read(Path file, double maxSeconds) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            return switch (type(file)) {
                case "ogg" -> ogg(in, maxSeconds);
                case "mp3" -> mp3(in, maxSeconds);
                case "wav" -> wav(in, maxSeconds);
                // FORMAT: mosaic input of an unknown type
                default -> throw new IOException("can't read " + file.getFileName() + ": use .wav, .mp3 or .ogg");
            };
        }
    }

    /** Growing float list without boxing. */
    private static final class Floats {
        float[] a = new float[1 << 16];
        int n;

        void add(float v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        float[] done() {
            return java.util.Arrays.copyOf(a, n);
        }
    }

    /** Ogg Vorbis through JOrbis, the steps of its own decoding example; channels are averaged. */
    static Pcm ogg(InputStream in, double maxSeconds) throws IOException {
        SyncState oy = new SyncState();
        StreamState os = new StreamState();
        Page og = new Page();
        Packet op = new Packet();
        Info vi = new Info();
        Comment vc = new Comment();
        DspState vd = new DspState();
        Block vb = new Block(vd);
        oy.init();
        int index = oy.buffer(4096);
        int bytes = Math.max(0, in.read(oy.data, index, 4096));
        oy.wrote(bytes);
        // FORMAT: broken ogg file
        if (oy.pageout(og) != 1) throw new IOException("not an Ogg file");
        os.init(og.serialno());
        vi.init();
        vc.init();
        if (os.pagein(og) < 0 || os.packetout(op) != 1 || vi.synthesis_headerin(vc, op) < 0) {
            throw new IOException("not an Ogg Vorbis file"); // FORMAT: broken ogg file
        }
        int headers = 1;
        while (headers < 3) {
            int result = oy.pageout(og);
            if (result == 1) {
                os.pagein(og);
                while (headers < 3) {
                    result = os.packetout(op);
                    if (result == 0) break;
                    if (result == -1) throw new IOException("broken Ogg Vorbis header"); // FORMAT: broken ogg file
                    vi.synthesis_headerin(vc, op);
                    headers++;
                }
                continue;
            }
            index = oy.buffer(4096);
            bytes = in.read(oy.data, index, 4096);
            if (bytes <= 0) throw new IOException("Ogg Vorbis file ends in its header"); // FORMAT: broken ogg file
            oy.wrote(bytes);
        }
        int channels = vi.channels;
        int rate = vi.rate;
        long limit = maxSeconds > 0 ? (long) (maxSeconds * rate) : Long.MAX_VALUE;
        vd.synthesis_init(vi);
        vb.init(vd);
        float[][][] pcmOut = new float[1][][];
        int[] at = new int[channels];
        Floats out = new Floats();
        boolean eos = false;
        boolean cut = false;
        while (!eos) {
            int result = oy.pageout(og);
            if (result == 0) {
                index = oy.buffer(4096);
                bytes = in.read(oy.data, index, 4096);
                if (bytes <= 0) break;
                oy.wrote(bytes);
                continue;
            }
            if (result < 0) continue;
            os.pagein(og);
            while (true) {
                result = os.packetout(op);
                if (result == 0) break;
                if (result < 0) continue;
                if (vb.synthesis(op) == 0) vd.synthesis_blockin(vb);
                int samples;
                while ((samples = vd.synthesis_pcmout(pcmOut, at)) > 0) {
                    float[][] pcm = pcmOut[0];
                    for (int s = 0; s < samples; s++) {
                        float sum = 0;
                        for (int c = 0; c < channels; c++) sum += pcm[c][at[c] + s];
                        out.add(sum / channels);
                    }
                    vd.synthesis_read(samples);
                    if (out.n >= limit) {
                        cut = true;
                        eos = true;
                        break;
                    }
                }
                if (eos) break;
            }
            if (og.eos() != 0) eos = true;
        }
        os.clear();
        vb.clear();
        vd.clear();
        vi.clear();
        oy.clear();
        float[] s = out.done();
        if (s.length > limit) s = java.util.Arrays.copyOf(s, (int) limit);
        return new Pcm(s, rate, cut);
    }

    static Pcm mp3(InputStream in, double maxSeconds) throws IOException {
        Bitstream bitstream = new Bitstream(in);
        Decoder decoder = new Decoder();
        Floats out = new Floats();
        int rate = 44100;
        boolean cut = false;
        try {
            Header h;
            while ((h = bitstream.readFrame()) != null) {
                SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(h, bitstream);
                rate = buffer.getSampleFrequency();
                int channels = buffer.getChannelCount();
                short[] b = buffer.getBuffer();
                int len = buffer.getBufferLength();
                for (int i = 0; i + channels <= len; i += channels) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) sum += b[i + c];
                    out.add(sum / channels / 32768f);
                }
                bitstream.closeFrame();
                if (maxSeconds > 0 && out.n >= maxSeconds * rate) {
                    cut = true;
                    break;
                }
            }
        } catch (javazoom.jl.decoder.JavaLayerException e) {
            throw new IOException("broken mp3 file (" + e.getMessage() + ")", e); // FORMAT: broken mp3 file
        }
        return new Pcm(out.done(), rate, cut);
    }

    static Pcm wav(InputStream in, double maxSeconds) throws IOException {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(in)) {
            AudioFormat f = source.getFormat();
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.getSampleRate(), 16, f.getChannels(),
                    f.getChannels() * 2, f.getSampleRate(), false);
            try (AudioInputStream s = AudioSystem.getAudioInputStream(pcm, source)) {
                byte[] all = s.readAllBytes();
                int channels = f.getChannels();
                int frames = all.length / (2 * channels);
                int rate = (int) f.getSampleRate();
                boolean cut = false;
                if (maxSeconds > 0 && frames > maxSeconds * rate) {
                    frames = (int) (maxSeconds * rate);
                    cut = true;
                }
                ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
                float[] out = new float[frames];
                for (int i = 0; i < frames; i++) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) sum += b.getShort((i * channels + c) * 2);
                    out[i] = sum / channels / 32768f;
                }
                return new Pcm(out, rate, cut);
            }
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            throw new IOException("this .wav can't be read (" + e.getMessage() + ")", e); // FORMAT: unreadable wav
        }
    }

    private static final int TAPS = 16; // each side of the windowed sinc

    /**
     * Resampling with a windowed sinc (Hann, 2x16 taps), low-passed below the new rate's limit so nothing folds
     * back. {@code step}: source samples per output sample (from rate / to rate, times a pitch for the preview).
     */
    static float[] resample(float[] src, double step, int outLength) {
        float[] out = new float[outLength];
        double cutoff = Math.min(1.0, 1.0 / step) * 0.95;
        // the kernel in a table at 1/SUB sample steps (sin and cos per tap would be far slower)
        final int SUB = 256;
        float[] table = new float[(TAPS + 1) * SUB + 2];
        for (int i = 0; i < table.length; i++) {
            double d = (double) i / SUB;
            double arg = Math.PI * d * cutoff;
            double sinc = Math.abs(arg) < 1e-9 ? 1 : Math.sin(arg) / arg;
            table[i] = d >= TAPS + 1 ? 0 : (float) (sinc * (0.5 + 0.5 * Math.cos(Math.PI * d / (TAPS + 1))));
        }
        for (int i = 0; i < outLength; i++) {
            double x = i * step;
            int center = (int) Math.floor(x);
            double sum = 0;
            double norm = 0;
            for (int j = center - TAPS + 1; j <= center + TAPS; j++) {
                float k = table[(int) (Math.abs(x - j) * SUB + 0.5)];
                norm += k;
                if (j >= 0 && j < src.length) sum += src[j] * k;
            }
            out[i] = (float) (norm == 0 ? 0 : sum / norm);
        }
        return out;
    }

    static float[] resample(Pcm pcm, int rate) {
        if (pcm.rate() == rate) return pcm.samples();
        double step = (double) pcm.rate() / rate;
        return resample(pcm.samples(), step, (int) (pcm.samples().length / step));
    }

    /** 16-bit mono .wav. */
    static void writeWav(Path file, float[] samples, int rate) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(samples.length * 2 + 44);
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int data = samples.length * 2;
        b.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2)
                .putShort((short) 16).put("data".getBytes()).putInt(data);
        body.write(b.array());
        ByteBuffer s = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : samples) s.putShort((short) Math.round(Math.max(-1, Math.min(1, v)) * 32767));
        body.write(s.array());
        Files.createDirectories(file.getParent());
        try (OutputStream o = Files.newOutputStream(file)) {
            body.writeTo(o);
        }
    }
}
