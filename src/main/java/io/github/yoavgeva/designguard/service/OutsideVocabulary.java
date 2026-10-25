package io.github.yoavgeva.designguard.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Words from outside the code, which an enum can't own: HTTP's method names, URI schemes and HTML's element names,
 * which a client or a parser compares input with, and, for choices in code, the syntax of other languages, Java's type
 * names and the like. A document may hold any tag, and a URI any scheme.
 */
final class OutsideVocabulary {

    private static final Set<String> HTTP_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS",
            "TRACE", "PATCH");
    private static final Set<String> URI_SCHEMES = Set.of("http", "https", "ftp", "ftps", "sftp", "file", "jar", "mailto",
            "data", "ws", "wss", "ssh", "git", "urn", "ldap", "ldaps", "tcp", "udp", "classpath");
    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long", "float",
            "double", "void");
    private static final Set<String> OBJECT_METHODS = Set.of("toString", "hashCode", "equals", "getClass");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    /** A character that no identifier-like word has: punctuation, quotes, brackets, dots or spaces. */
    private static final Pattern SYNTAX = Pattern.compile("[^\\p{L}\\p{N}_-]");
    private static final Set<String> HTML_ELEMENTS = Set.of("a", "abbr", "acronym", "address", "applet", "area", "article",
            "aside", "audio", "b", "base", "basefont", "bdi", "bdo", "bgsound", "big", "blink", "blockquote", "body", "br",
            "button", "canvas", "caption", "center", "cite", "code", "col", "colgroup", "data", "datalist", "dd", "del",
            "details", "dfn", "dialog", "dir", "div", "dl", "dt", "em", "embed", "fieldset", "figcaption", "figure",
            "font", "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup",
            "hr", "html", "i", "iframe", "image", "img", "input", "ins", "isindex", "kbd", "keygen", "label", "legend",
            "li", "link", "listing", "main", "map", "mark", "marquee", "math", "menu", "menuitem", "meta", "meter", "nav",
            "nobr", "noembed", "noframes", "noscript", "object", "ol", "optgroup", "option", "output", "p", "param",
            "picture", "plaintext", "pre", "progress", "q", "rb", "rp", "rt", "rtc", "ruby", "s", "samp", "script",
            "search", "section", "select", "slot", "small", "source", "span", "strike", "strong", "style", "sub",
            "summary", "sup", "svg", "table", "tbody", "td", "template", "textarea", "tfoot", "th", "thead", "time",
            "title", "tr", "track", "tt", "u", "ul", "var", "video", "wbr", "xmp");

    private OutsideVocabulary() {
    }

    /**
     * Whether the strings are well-known names from outside the code: all HTTP method names or all URI schemes, or at
     * least half of them HTML's element names, as a parser of HTML compares tags with.
     */
    static boolean areNames(List<String> strings) {
        return strings.stream().allMatch(string -> HTTP_METHODS.contains(string.toUpperCase(Locale.ROOT)))
                || strings.stream().allMatch(string -> URI_SCHEMES.contains(string.toLowerCase(Locale.ROOT)))
                || strings.stream().filter(string -> HTML_ELEMENTS.contains(string.toLowerCase(Locale.ROOT))).count() * 2
                        >= strings.size();
    }

    /**
     * Whether the strings a choice compares with are words of a vocabulary from outside the code: the {@link
     * #areNames names} above, property keys, class names or SQL, which have dots or spaces, tokens a parser reads, any of
     * which is punctuation such as {@code "("}, single characters such as operators, Java's type names, codes such as
     * SQL states, which all have digits, method names a proxy dispatches on, which include {@code toString}, or
     * command-line options, which start with a dash.
     */
    static boolean isOfChoices(List<String> strings) {
        return strings.stream().anyMatch(string -> SYNTAX.matcher(string).find() || OBJECT_METHODS.contains(string)
                        || string.startsWith("-"))
                || strings.stream().allMatch(string -> string.length() <= 1 || DIGIT.matcher(string).find())
                || strings.stream().filter(PRIMITIVES::contains).count() * 2 >= strings.size()
                || areNames(strings);
    }
}
