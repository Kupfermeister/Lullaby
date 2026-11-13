/**
 * The only package allowed to use Minecraft's own server code (NMS: {@code net.minecraft.*}) and CraftBukkit
 * ({@code org.bukkit.craftbukkit.*}). Everything else in Lullaby goes through classes here, so a Minecraft update
 * only means fixing this package. Names are vanilla's (Mojang mappings), which Paper uses at runtime since 1.20.5,
 * so nothing is remapped when the jar is built.
 */
package io.github.kupfermeister.lullaby.nms;
