/**
 * Sound mosaicing: rebuilds an audio file (speech, a song) out of vanilla's own sound files, played at the right
 * moments with the right pitch and volume, so a player hears the input in the game. {@code /lullaby mosaic play
 * <file> [filters] [-overwrite]} (Mosaic.java) works it out in the background once, keeps it, and plays it to the
 * player who asked. Settings: the {@code sound_mosaicing} section of config.lua (Settings.java).
 *
 * <p>How, in short (each class says more):
 * <ol>
 *   <li>SoundAssets: the server jar has no sound files, so the .ogg files are downloaded from Mojang (the list with
 *       hashes is made at build: build.gradle.kts, downloadSounds) into {@code plugins/lullaby/cache/mosaic/assets}.
 *   <li>Dictionary: every file is decoded (AudioFiles) and turned into a spectrogram (Spectra): 10 ms frames, two
 *       bins per semitone, so a pitch change is just a shift by whole bins. Kept in {@code cache/mosaic/dictionary.bin}.
 *   <li>Features: per file, worked out from it (nothing by hand): tonality up to each length (one held tone), first
 *       note, bass purity, and whether it may be stopped (it hits again or holds its loudness; a single hit always
 *       plays whole). Listed in {@code mosaic/made/sounds.txt}.
 *   <li>Slicer ({@code method = "slices"}, the default): time in slices; melody, bass and noise layers, each with its
 *       own volume. Or Solver ({@code method = "layers"}): the strict greedy solver that places long sounds, in
 *       melody, bass, main and detail layers. Both share Shared (input loudness, stop keys that hit no other sound).
 *   <li>Preview writes the result as a .wav, Mosaic plays it (nms/MosaicSender builds the packets on the main thread,
 *       a playback thread only sends them at the right time and reports how late it was).
 * </ol>
 *
 * <p>Threads: one job thread (and its helper pool) works only on plain data it owns (files, arrays); chat messages
 * and packet building go to the main thread through Bukkit's scheduler; the playback thread only calls
 * {@code connection.send} with packets made on the main thread.
 *
 * <p><b>Removing the feature</b> (jft 2026-11-27: it must come out easily if it fails): delete this package and
 * {@code nms/MosaicSender.java}; the {@code .then(...Mosaic.command(plugin))} line in LullabyCommand.java; the
 * {@code mosaic_config} lines in JavaFunctions.java and in core.lua's I.setup; the {@code sound_mosaicing} section of
 * defaults/config.lua; the {@code lullaby.mosaic} permission in paper-plugin.yml; the two decoder dependencies
 * (jorbis, jlayer) and the {@code assets} lines of downloadSounds in build.gradle.kts; the mosaic rows in
 * ARCHITECTURE.md. precise_sound{} and SoundSeeds.allUses stay (they are a normal feature). Its folders are
 * {@code plugins/lullaby/mosaic} and {@code plugins/lullaby/cache/mosaic}.
 */
package io.github.kupfermeister.lullaby.mosaic;
