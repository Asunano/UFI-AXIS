package com.ufi_axis_core.util

/**
 * Pure-Kotlin MIME type mapping based on file extension.
 * Avoids shell `file --mime-type` calls for streaming/download endpoints.
 */
object MimeTypes {

    fun fromFileName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXTENSION_MAP[ext] ?: "application/octet-stream"
    }

    /** Whether the extension represents a text-based file that can be edited in a text editor. */
    fun isTextFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    /** Whether the extension represents an image file. */
    fun isImage(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    /** Whether the extension represents a video file. */
    fun isVideo(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /** Whether the extension represents an audio file. */
    fun isAudio(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    fun isMedia(fileName: String): Boolean = isVideo(fileName) || isAudio(fileName)

    // ── Extension sets ──

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tiff", "tif"
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v"
    )

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "wav", "flac", "ogg", "aac", "m4a", "wma", "opus", "amr"
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "log", "md", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
        "properties", "csv", "tsv", "html", "htm", "css", "js", "ts", "jsx", "tsx",
        "kt", "kts", "java", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp",
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1",
        "sql", "graphql", "proto", "dockerfile", "makefile",
        "gradle", "pro", "rules", "env", "gitignore", "htaccess"
    )

    // ── Full MIME map ──

    private val EXTENSION_MAP = mapOf(
        // Images
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "bmp" to "image/bmp", "webp" to "image/webp",
        "svg" to "image/svg+xml", "ico" to "image/x-icon",
        "tiff" to "image/tiff", "tif" to "image/tiff",

        // Video
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "avi" to "video/x-msvideo",
        "mov" to "video/quicktime", "webm" to "video/webm", "flv" to "video/x-flv",
        "wmv" to "video/x-ms-wmv", "3gp" to "video/3gpp", "ts" to "video/mp2t",
        "m4v" to "video/x-m4v",

        // Audio
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "flac" to "audio/flac",
        "ogg" to "audio/ogg", "aac" to "audio/aac", "m4a" to "audio/mp4",
        "wma" to "audio/x-ms-wma", "opus" to "audio/opus", "amr" to "audio/amr",

        // Text / Code
        "txt" to "text/plain", "log" to "text/plain", "md" to "text/markdown",
        "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "html" to "text/html", "htm" to "text/html", "css" to "text/css",
        "js" to "application/javascript", "ts" to "application/typescript",
        "jsx" to "application/javascript", "tsx" to "application/typescript",
        "json" to "application/json", "xml" to "application/xml",
        "yaml" to "text/yaml", "yml" to "text/yaml", "toml" to "text/plain",
        "ini" to "text/plain", "conf" to "text/plain", "cfg" to "text/plain",
        "properties" to "text/plain",

        // Programming
        "kt" to "text/x-kotlin", "kts" to "text/x-kotlin",
        "java" to "text/x-java-source", "py" to "text/x-python",
        "rb" to "text/x-ruby", "go" to "text/x-go", "rs" to "text/x-rust",
        "c" to "text/x-c", "cpp" to "text/x-c++", "h" to "text/x-c",
        "hpp" to "text/x-c++", "sh" to "text/x-shellscript",
        "bash" to "text/x-shellscript", "zsh" to "text/x-shellscript",
        "sql" to "application/sql", "proto" to "text/plain",
        "gradle" to "text/plain", "pro" to "text/plain",

        // Documents
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",

        // Archives
        "zip" to "application/zip", "tar" to "application/x-tar",
        "gz" to "application/gzip", "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed", "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz",

        // Android
        "apk" to "application/vnd.android.package-archive",

        // Misc
        "wasm" to "application/wasm",
        "ttf" to "font/ttf", "otf" to "font/otf", "woff" to "font/woff",
        "woff2" to "font/woff2",
    )
}
