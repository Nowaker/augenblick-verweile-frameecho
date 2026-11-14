package com.shangjin.frameecho.core.media.source

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

internal data class MotionPhotoItem(val mime: String, val semantic: String, val length: Long, val padding: Long)

internal data class MotionPhotoXmp(
    val enabled: Boolean?,
    val legacyOffset: Long?,
    val presentationTimestampUs: Long?,
    val items: List<MotionPhotoItem>
) {
    companion object {
        private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
        private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
        private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
        private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

        fun parse(bytes: ByteArray): MotionPhotoXmp {
            if (bytes.size > MAX_XMP_BYTES) malformed("XMP packet is too large")
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), null)
            val camera = mutableMapOf<String, String>()
            val items = mutableListOf<MotionPhotoItem>()
            val path = mutableListOf<Pair<String, String>>()
            var item: MutableMap<String, String>? = null
            var itemDepth = 0
            var directoryCount = 0
            var events = 0
            try {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (++events > 30_000 || parser.depth > 32) malformed("XMP structure exceeds limits")
                    when (parser.eventType) {
                        XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF -> malformed("XMP entities are not supported")
                        XmlPullParser.START_TAG -> {
                            path += parser.namespace.orEmpty() to parser.name
                            for (index in 0 until parser.attributeCount) {
                                if (parser.getAttributeNamespace(index) == CAMERA) {
                                    putUnique(camera, parser.getAttributeName(index), parser.getAttributeValue(index))
                                }
                            }
                            if (path.last() == (CONTAINER to "Directory")) {
                                if (++directoryCount > 1) malformed("Duplicate XMP container directory")
                            }
                            if (path.takeLast(4) == listOf(CONTAINER to "Directory", RDF to "Seq", RDF to "li", CONTAINER to "Item")) {
                                if (items.size >= 256) malformed("Too many XMP container items")
                                item = mutableMapOf()
                                itemDepth = parser.depth
                                for (index in 0 until parser.attributeCount) {
                                    if (parser.getAttributeNamespace(index) == ITEM) {
                                        putUnique(item, parser.getAttributeName(index), parser.getAttributeValue(index))
                                    }
                                }
                            }
                        }
                        XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                            val current = path.lastOrNull()
                            if (!parser.isWhitespace && current != null) {
                                when {
                                    current.first == CAMERA -> putUnique(camera, current.second, parser.text.trim())
                                    current.first == ITEM && parser.depth == itemDepth + 1 ->
                                        item?.let { putUnique(it, current.second, parser.text.trim()) }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (item != null && parser.depth == itemDepth) {
                                items += MotionPhotoItem(
                                    item["Mime"] ?: malformed("XMP item is missing its MIME type"),
                                    item["Semantic"] ?: malformed("XMP item is missing its semantic"),
                                    number(item, "Length") ?: if (items.isEmpty()) 0 else malformed("XMP item is missing its length"),
                                    number(item, "Padding") ?: 0
                                )
                                item = null
                            }
                            path.removeAt(path.lastIndex)
                        }
                    }
                    parser.nextToken()
                }
            } catch (_: XmlPullParserException) {
                malformed("Invalid Motion Photo XMP")
            }
            if (directoryCount != 0 && items.isEmpty()) malformed("Empty or invalid XMP container directory")
            val flag = camera["MotionPhoto"] ?: camera["MicroVideo"]
            val timestampKey = if (camera.containsKey("MotionPhotoPresentationTimestampUs")) {
                "MotionPhotoPresentationTimestampUs"
            } else "MicroVideoPresentationTimestampUs"
            val timestamp = camera[timestampKey]?.let {
                it.toLongOrNull()?.takeIf { value -> value >= -1 } ?: malformed("Invalid cover timestamp")
            }
            return MotionPhotoXmp(
                flag?.let { it == "1" }, if (items.isEmpty()) number(camera, "MicroVideoOffset") else null,
                timestamp?.takeIf { it >= 0 }, items
            )
        }

        private fun number(attributes: Map<String, String>, key: String): Long? = attributes[key]?.let {
            it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: malformed("Invalid XMP $key")
        }

        private fun putUnique(attributes: MutableMap<String, String>, key: String, value: String) {
            val previous = attributes.put(key, value)
            if (previous != null && previous != value) malformed("Conflicting XMP $key")
        }
    }
}
