package io.github.yoavgeva.designguard.service;

import java.util.Locale;
import java.util.Set;

/**
 * The names of HTML's elements, current and obsolete, as the HTML standard lists them: a vocabulary from outside the
 * code, which a parser or a renderer of HTML compares tag names with, and which an enum can't own, since a document
 * may hold any tag.
 */
final class HtmlElements {

    private static final Set<String> NAMES = Set.of("a", "abbr", "acronym", "address", "applet", "area", "article",
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

    private HtmlElements() {
    }

    /** Whether the string names an HTML element, in any case, as {@code "td"} or {@code "TBODY"} does. */
    static boolean contains(String name) {
        return NAMES.contains(name.toLowerCase(Locale.ROOT));
    }
}
