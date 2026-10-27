package com.example.demo.threads.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the first image URL using the same Markdown/HTML order as the section list UI. */
final class ThreadSectionPreviewCoverExtractor {
    // Java's default \\s is ASCII-only. Add the ECMAScript whitespace values so uploaded
    // Markdown keeps the browser rule's URL/caption boundaries (notably NBSP and BOM).
    private static final String JAVASCRIPT_WHITESPACE =
            "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
            "!\\[[^\\]]*\\]\\(([^)" + JAVASCRIPT_WHITESPACE
                    + "]+)(?:[" + JAVASCRIPT_WHITESPACE + "]+\"[^\"]*\")?\\)");
    private static final Pattern HTML_IMAGE = Pattern.compile(
            "<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private ThreadSectionPreviewCoverExtractor() {
    }

    static String extract(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        Matcher markdown = MARKDOWN_IMAGE.matcher(content);
        if (markdown.find()) {
            return markdown.group(1);
        }
        Matcher html = HTML_IMAGE.matcher(content);
        return html.find() ? html.group(1) : "";
    }
}
