package io.github.kupfermeister.lullaby.lullablend;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.InflaterInputStream;

/**
 * The LullaBlend link: a TCP port the Blender add-on connects to. Off unless the config sets {@code lullablend_port}.
 *
 * <p>The protocol (all numbers big-endian; strings are Java's writeUTF: 2-byte length, then UTF-8). Each message
 * is a frame: 4-byte length, then the body; the body's first byte is its type.
 * <pre>
 *   Blender -> server  1 HELLO     u16 protocol (1), str token, str pack version (Minecraft version)
 *   server -> Blender  2 WELCOME   str player name, str server's Minecraft version
 *   server -> Blender  3 ERROR     str text (the server closes the link after it)
 *   Blender -> server  4 SECTIONS  u8 flags (1: forget the whole scene first), i32 count, then deflated:
 *                                  per section i32 x, y, z (section coordinates, see below), u16 palette size
 *                                  (0: remove this section), the palette's state texts, and when the palette isn't
 *                                  empty 4096 u16 cells (0: nothing, k: palette[k-1]; cell = x + z*16 + y*256)
 *   Blender -> server  5 COMMIT    the scene is complete: build it
 *   server -> Blender  6 STATUS    str text (shown in Blender's panel)
 *   Blender -> server  7 COMMAND   str "place", "undo", "show" or "hide"
 *   both ways          8 PING      nothing (Blender sends one every 10 s; 60 s of silence closes the link)
 * </pre>
 * Coordinates are blocks relative to the player's LullaBlend origin, as if the player looks north: x east, y up,
 * z south. The server turns them to the origin's facing.
 *
 * <p>Threads: one thread accepts, each link has a reader and a writer thread. They only read bytes into plain
 * data and hand it to the main thread through {@link LullaBlend#post}; they never touch the game or Lua.
 */
final class LinkServer {

    static final int PROTOCOL = 1;
    private static final int MAX_FRAME = 64 << 20; // Blender splits big scenes into frames below this

    private final LullaBlend owner;
    private final ServerSocket socket;
    private final Thread acceptor;
    private volatile boolean closed;

    LinkServer(LullaBlend owner, String bind, int port) throws IOException {
        this.owner = owner;
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(bind, port));
        acceptor = new Thread(this::acceptLoop, "Lullaby LullaBlend link");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = socket.accept();
                Link link = new Link(s);
                Thread t = new Thread(link::readLoop, "Lullaby LullaBlend " + s.getRemoteSocketAddress());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!closed) owner.log("LullaBlend link: " + e.getMessage()); // FORMAT
            }
        }
    }

    /** One connected Blender. */
    final class Link {
        private final Socket socket;
        private final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
        private volatile boolean gone;
        volatile LullaBlend.Session session;

        Link(Socket socket) {
            this.socket = socket;
            Thread w = new Thread(this::writeLoop, "Lullaby LullaBlend writer");
            w.setDaemon(true);
            w.start();
        }

        String address() {
            return String.valueOf(socket.getRemoteSocketAddress());
        }

        void sendStatus(String text) {
            send(6, text);
        }

        void sendError(String text) {
            send(3, text);
            out.add(new byte[0]); // the writer closes after the error
        }

        void sendWelcome(String name, String version) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (DataOutputStream d = new DataOutputStream(b)) {
                d.writeByte(2);
                d.writeUTF(name);
                d.writeUTF(version);
            } catch (IOException ignored) {
                // in memory
            }
            out.add(b.toByteArray());
        }

        private void send(int type, String text) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (DataOutputStream d = new DataOutputStream(b)) {
                d.writeByte(type);
                d.writeUTF(text.length() > 20000 ? text.substring(0, 20000) : text);
            } catch (IOException ignored) {
                // in memory
            }
            out.add(b.toByteArray());
        }

        void close() {
            gone = true;
            out.add(new byte[0]);
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }

        private void writeLoop() {
            try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
                while (true) {
                    byte[] frame = out.take();
                    if (frame.length == 0) break;
                    d.writeInt(frame.length);
                    d.write(frame);
                    if (out.isEmpty()) d.flush();
                }
                d.flush();
            } catch (IOException | InterruptedException ignored) {
                // the link is gone
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
        }

        private void readLoop() {
            Map<Long, LullaBlend.BlenderSection> scene = new HashMap<>();
            try {
                socket.setSoTimeout(60_000);
                socket.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                boolean hello = false;
                while (!gone) {
                    int length = in.readInt();
                    if (length < 1 || length > MAX_FRAME) throw new IOException("frame of " + length + " bytes");
                    byte[] body = new byte[length];
                    in.readFully(body);
                    DataInputStream b = new DataInputStream(new ByteArrayInputStream(body));
                    int type = b.readUnsignedByte();
                    if (!hello) {
                        if (type != 1) throw new IOException("the first message must be HELLO");
                        int protocol = b.readUnsignedShort();
                        String token = b.readUTF();
                        String pack = b.readUTF();
                        hello = true;
                        owner.post(() -> owner.hello(this, protocol, token, pack));
                        continue;
                    }
                    switch (type) {
                        case 4 -> {
                            int flags = b.readUnsignedByte();
                            int count = b.readInt();
                            if (count < 0) throw new IOException("bad section count");
                            if ((flags & 1) != 0) scene.clear();
                            DataInputStream z = new DataInputStream(new InflaterInputStream(b));
                            for (int i = 0; i < count; i++) {
                                int x = z.readInt(), y = z.readInt(), zz = z.readInt();
                                int n = z.readUnsignedShort();
                                long key = LullaBlend.key(x, y, zz);
                                if (n == 0) {
                                    scene.remove(key);
                                    continue;
                                }
                                String[] palette = new String[n];
                                for (int k = 0; k < n; k++) palette[k] = z.readUTF();
                                short[] cells = new short[4096];
                                for (int k = 0; k < 4096; k++) {
                                    int c = z.readUnsignedShort();
                                    if (c > n) throw new IOException("cell " + c + " past the palette (" + n + ")");
                                    cells[k] = (short) c;
                                }
                                scene.put(key, new LullaBlend.BlenderSection(x, y, zz, palette, cells));
                            }
                        }
                        case 5 -> {
                            Map<Long, LullaBlend.BlenderSection> copy = Map.copyOf(scene); // sections never change
                            owner.post(() -> owner.scene(this, copy));
                        }
                        case 7 -> {
                            String command = b.readUTF();
                            owner.post(() -> owner.command(this, command));
                        }
                        case 8 -> {
                        }
                        default -> throw new IOException("unknown message type " + type);
                    }
                }
            } catch (EOFException | SocketException ignored) {
                // closed
            } catch (IOException e) {
                sendError("LullaBlend link: " + e.getMessage()); // FORMAT
            } finally {
                gone = true;
                out.add(new byte[0]);
                owner.post(() -> owner.gone(this));
            }
        }
    }
}
