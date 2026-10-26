package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which methods are commands, called for the change they make rather than for what they return: methods that return
 * nothing, or only a {@code Void} publisher or future such as {@code Mono<Void>}, and methods named for a change, as
 * {@code update} and {@code sendCode} are, which may return what they stored, as reactive clients and repositories do.
 */
final class CommandMethods {

    /** Verbs that name a change, as the first word of a command's name. */
    private static final Set<String> COMMAND_VERBS = Set.of("create", "update", "delete", "remove", "save", "insert",
            "upsert", "add", "put", "set", "patch", "send", "publish", "emit", "post", "write", "store", "persist",
            "merge", "register", "unregister", "install", "uninstall", "upload", "start", "stop", "subscribe",
            "unsubscribe", "notify", "schedule", "cancel", "execute", "submit", "confirm", "approve", "reject",
            "reset", "clear", "flush", "commit", "rollback", "unload", "backup", "restore", "enable", "disable",
            "mark", "grant", "revoke", "assign", "archive", "purge", "enqueue", "dispatch", "trigger", "lock",
            "unlock", "increment", "decrement", "move", "rename", "close", "draft", "pay", "charge", "refund",
            "transfer", "book", "reserve", "release", "acquire", "accept", "deny", "activate", "deactivate", "expire",
            "evict", "invalidate", "refresh", "reload", "sync", "init", "initialize", "attach", "detach", "link",
            "unlink", "bind", "unbind", "import", "migrate", "upgrade", "retry", "deploy", "suspend", "resume",
            "pause", "terminate", "shutdown", "login", "logout", "issue", "rotate", "toggle", "push", "offer", "ack",
            "acknowledge", "reply", "forward", "broadcast", "invite", "leave", "follow", "unfollow", "favorite",
            "unfavorite", "vote", "share", "ban", "unban", "block", "unblock", "mute", "unmute", "pin", "unpin",
            "star", "unstar");
    private static final Pattern WORD = Pattern.compile("(?=[A-Z])");

    private CommandMethods() {
    }

    static boolean isCommand(JavaMethod method) {
        boolean voidResult = method.getRawReturnType().isEquivalentTo(void.class)
                || method.getReturnType() instanceof JavaParameterizedType result && !result.getActualTypeArguments().isEmpty()
                        && result.getActualTypeArguments().stream()
                                .allMatch(argument -> Void.class.getName().equals(argument.toErasure().getName()));
        return voidResult || COMMAND_VERBS.contains(WORD.split(method.getName())[0]);
    }
}
