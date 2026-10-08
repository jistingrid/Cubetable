package dev.tacticalcombat.character;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Sheet JSON is sent gzipped: client -> server packets are limited to about 32 KB. */
public final class Gz {
	/** Largest text we are willing to unpack. */
	public static final int MAX_TEXT = 2_000_000;

	private Gz() {}

	public static byte[] pack(String text) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
				gz.write(text.getBytes(StandardCharsets.UTF_8));
			}
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String unpack(byte[] data) throws IOException {
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				if (out.size() > MAX_TEXT) throw new IOException("character data too large");
			}
			return out.toString(StandardCharsets.UTF_8);
		}
	}
}
