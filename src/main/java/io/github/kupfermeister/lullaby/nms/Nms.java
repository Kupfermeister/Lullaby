package io.github.kupfermeister.lullaby.nms;

import net.minecraft.SharedConstants;

/** Small NMS reads that do not belong to a bigger NMS class yet. */
public final class Nms {

    private Nms() {
    }

    /** The Minecraft version the server runs, vanilla's own name for it, e.g. "26.3". */
    public static String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }
}
