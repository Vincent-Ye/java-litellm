package dev.javalitellm.core.chat;

/** Tool choice constraint. {@code functionName} is only set when {@code mode == FUNCTION}. */
public record ToolChoice(Mode mode, String functionName) {

    public enum Mode {
        AUTO,
        NONE,
        REQUIRED,
        FUNCTION
    }

    public static final ToolChoice AUTO = new ToolChoice(Mode.AUTO, null);
    public static final ToolChoice NONE = new ToolChoice(Mode.NONE, null);
    public static final ToolChoice REQUIRED = new ToolChoice(Mode.REQUIRED, null);

    public static ToolChoice function(String name) {
        return new ToolChoice(Mode.FUNCTION, name);
    }
}
