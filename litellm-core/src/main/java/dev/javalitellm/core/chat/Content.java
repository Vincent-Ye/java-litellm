package dev.javalitellm.core.chat;

/**
 * One part of a multimodal message. Wire-format shapes differ per provider, so these are plain
 * carriers; each provider's transformer owns the mapping to its wire format.
 */
public sealed interface Content permits Content.Text, Content.Image, Content.Audio {

    record Text(String text) implements Content {}

    /** {@code url} accepts both https URLs and data URIs (base64). */
    record Image(String url, String detail) implements Content {
        public Image(String url) {
            this(url, null);
        }
    }

    /** Base64-encoded audio with its format, e.g. "wav" or "mp3". */
    record Audio(String base64Data, String format) implements Content {}

    static Text text(String text) {
        return new Text(text);
    }

    static Image image(String url) {
        return new Image(url);
    }
}
