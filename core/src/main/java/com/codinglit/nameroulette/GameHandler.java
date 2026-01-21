package com.codinglit.nameroulette;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;

import java.nio.ShortBuffer;
import java.util.Random;

/** {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms. */
public class GameHandler extends ApplicationAdapter {
    private SpriteBatch batch;
    private BitmapFont font, bigFont;

    private Array<String> names;
    private String flashingName = "";
    private String selectedName = "";

    private final Random rng = new Random();

    // Timing
    private boolean flashing = false;
    private float flashTimer = 0f;
    private float totalFlashTime = 0f;

    // Tune these
    private float flashInterval = 0.28f;   // how fast names change
    private float flashDuration = 10.0f;    // how long the roulette runs

    // Beep
    private Sound beep;

    @Override
    public void create() {
        batch = new SpriteBatch();
        font = new BitmapFont(); // default font
        bigFont = new BitmapFont();
        bigFont.getData().setScale(3.0f);

        names = new Array<>();
        names.addAll(
            "Luke", "Blake", "Amy", "Bowen", "Joaquin", "Manny", "Noah", "Aidan", "Leslie", "John", "Greta", "Eric", "McKenna", "Andrew", "Mike"
        );

        beep = generateBeepSound(880, 0.03f); // 880 Hz, 30 ms
        flashingName = names.size > 0 ? names.get(0) : "(no names)";
    }

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();

        // Start flashing on SPACE
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            if (!flashing && names.size > 0) {
                startFlashing();
            }
        }

        if (flashing) {
            totalFlashTime += dt;
            flashTimer += dt;

            if (flashTimer >= flashInterval) {
                flashTimer -= flashInterval;
                flashingName = names.get(rng.nextInt(names.size));
                beep.play(0.6f);
            }

            if (totalFlashTime >= flashDuration) {
                finalizeSelection();
            }
        }

        Gdx.gl.glClearColor(0.05f, 0.05f, 0.07f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        batch.begin();

        String line1 = "Press SPACE to pick a name";
        String line2 = "Remaining: " + names.size;

        font.draw(batch, line1, 40, 40);
        font.draw(batch, line2, 40, 80);

        String display = flashing ? flashingName : (selectedName.isEmpty() ? flashingName : selectedName);
        bigFont.draw(batch, ">> " + display + " <<", 40, Gdx.graphics.getHeight() / 2f);

        if (!selectedName.isEmpty() && !flashing) {
            font.draw(batch, "(Selected and removed)", 40, Gdx.graphics.getHeight() / 2f - 40);
        }

        if (names.size == 0 && !flashing) {
            font.draw(batch, "All done! No names left.", 40, 120);
        }

        batch.end();
    }

    private void startFlashing() {
        flashing = true;
        flashTimer = 0f;
        totalFlashTime = 0f;
        selectedName = "";
    }

    private void finalizeSelection() {
        // Choose final winner and remove from list
        int idx = rng.nextInt(names.size);
        selectedName = names.removeIndex(idx);

        // stop flashing
        flashing = false;

        // little “final” beep
        beep.play(1.0f);
    }

    @Override
    public void dispose() {
        batch.dispose();
        font.dispose();
        beep.dispose();
    }

    /**
     * Generate a simple sine-wave beep as a LibGDX Sound (PCM 16-bit mono).
     * Works on desktop and Android.
     */
    private Sound generateBeepSound(int frequencyHz, float durationSeconds) {
        final int sampleRate = 44100;
        int samples = Math.max(1, (int)(durationSeconds * sampleRate));

        ShortBuffer sb = BufferUtils.newShortBuffer(samples);
        for (int i = 0; i < samples; i++) {
            double t = i / (double) sampleRate;
            double wave = Math.sin(2.0 * Math.PI * frequencyHz * t);

            // quick fade-out to avoid clicks
            double fade = 1.0 - (i / (double) samples);
            short value = (short) (wave * fade * Short.MAX_VALUE * 0.25);

            sb.put(value);
        }
        sb.flip();

        // Convert to byte buffer as required by Sound loader
        // LibGDX has Sound from file; for raw PCM we use AudioDevice usually.
        // BUT we can still create a Sound by writing a WAV into memory.
        // Simplest: write a tiny WAV header + PCM, then use Gdx.audio.newSound with a FileHandle.
        // Since we want “no external file”, we’ll create it in local storage once.

        byte[] wavBytes = WavUtil.pcm16MonoToWav(sb, sampleRate);
        String tmpName = "beep_" + frequencyHz + "_" + (int)(durationSeconds * 1000) + ".wav";
        Gdx.files.local(tmpName).writeBytes(wavBytes, false);

        return Gdx.audio.newSound(Gdx.files.local(tmpName));
    }

    /** Minimal WAV writer helper. */
    private static class WavUtil {
        static byte[] pcm16MonoToWav(ShortBuffer pcm, int sampleRate) {
            int numSamples = pcm.remaining();
            int dataSize = numSamples * 2;

            int chunkSize = 36 + dataSize;
            int byteRate = sampleRate * 2; // mono * 16bit
            short blockAlign = 2;
            short bitsPerSample = 16;

            byte[] out = new byte[44 + dataSize];

            // RIFF header
            writeAscii(out, 0, "RIFF");
            writeLEInt(out, 4, chunkSize);
            writeAscii(out, 8, "WAVE");

            // fmt chunk
            writeAscii(out, 12, "fmt ");
            writeLEInt(out, 16, 16);            // subchunk1 size
            writeLEShort(out, 20, (short) 1);   // PCM format
            writeLEShort(out, 22, (short) 1);   // mono
            writeLEInt(out, 24, sampleRate);
            writeLEInt(out, 28, byteRate);
            writeLEShort(out, 32, blockAlign);
            writeLEShort(out, 34, bitsPerSample);

            // data chunk
            writeAscii(out, 36, "data");
            writeLEInt(out, 40, dataSize);

            // PCM data
            int offset = 44;
            while (pcm.hasRemaining()) {
                short s = pcm.get();
                out[offset++] = (byte) (s & 0xFF);
                out[offset++] = (byte) ((s >> 8) & 0xFF);
            }
            return out;
        }

        static void writeAscii(byte[] b, int off, String s) {
            for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
        }

        static void writeLEInt(byte[] b, int off, int v) {
            b[off]     = (byte) (v & 0xFF);
            b[off + 1] = (byte) ((v >> 8) & 0xFF);
            b[off + 2] = (byte) ((v >> 16) & 0xFF);
            b[off + 3] = (byte) ((v >> 24) & 0xFF);
        }

        static void writeLEShort(byte[] b, int off, short v) {
            b[off]     = (byte) (v & 0xFF);
            b[off + 1] = (byte) ((v >> 8) & 0xFF);
        }
    }
}
