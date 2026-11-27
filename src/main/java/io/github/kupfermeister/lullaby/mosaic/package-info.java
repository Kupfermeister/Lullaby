/**
 * Sound mosaicing: rebuilds an audio file (speech, a song) out of vanilla's own sound files, played at the right
 * moments with the right pitch and volume, so a player hears the input in the game. {@code /lullaby mosaic play
 * <file> [filters]} (Mosaic.java) works it out in the background and then plays it to the player who asked.
 *
 * <p>How, in short (each class says more):
 * <ol>
 *   <li>SoundAssets: the server jar has no sound files, so the .ogg files are downloaded from Mojang (the list with
 *       hashes is made at build: build.gradle.kts, downloadSounds) into {@code plugins/lullaby/sound_mosaicing/assets}.
 *   <li>Dictionary: every file is decoded (AudioFiles) and turned into a spectrogram (Spectra): 10 ms frames, one bin
 *       per semitone, so a pitch change is just a shift by whole bins. Kept in {@code cache/dictionary.bin}.
 *   <li>Solver: the input gets the same spectrogram; then sounds are placed one by one where they explain most of
 *       what is still missing (greedy matching pursuit), longest sounds first, then shorter and shorter. Limits:
 *       sounds per tick, voices at once, the rate limit per file, melody before noise (tonality, written to
 *       {@code tonality.txt} for review), cut-off sounds through stopsound with free stop keys.
 *   <li>Preview writes the result as a .wav, Mosaic plays it (nms/MosaicSender builds the packets on the main thread,
 *       a playback thread only sends them at the right time).
 * </ol>
 *
 * <p>Threads: one job thread (and its helper pool) works only on plain data it owns (files, arrays); chat messages
 * and packet building go to the main thread through Bukkit's scheduler; the playback thread only calls
 * {@code connection.send} with packets made on the main thread.
 *
 * <p><b>Removing the feature</b> (jft 2026-11-27: it must come out easily if it fails): delete this package and
 * {@code nms/MosaicSender.java}, the {@code .then(...Mosaic.command(plugin))} line in LullabyCommand.java, the
 * {@code lullaby.mosaic} permission in paper-plugin.yml, the two decoder dependencies (jorbis, jlayer) and the
 * {@code assets} lines of downloadSounds in build.gradle.kts, and the mosaic rows in ARCHITECTURE.md. precise_sound{}
 * and SoundSeeds.allUses stay (they are a normal feature). Its folders are {@code plugins/lullaby/sound_mosaicing}
 * and {@code plugins/lullaby/sound_mosaicing_sources}.
 */
package io.github.kupfermeister.lullaby.mosaic;
