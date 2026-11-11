package ai.capri.host

import org.json.JSONObject
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.io.Writer
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

internal fun extractPdf(content: ByteArray, firstPage: Int, pageLimit: Int): JSONObject {
    val result = try {
        Loader.loadPDF(content).use { pdf ->
            if (!pdf.currentAccessPermission.canExtractContent())
                throw WorkspaceFailure("pdf_extraction_forbidden", "The PDF does not permit text extraction.")
            val pages = pdf.numberOfPages
            if (firstPage > pages) throw WorkspaceFailure("page_out_of_range", "The starting page is outside the PDF.")
            val lastPage = minOf(firstPage.toLong() + pageLimit - 1, pages.toLong()).toInt()
            val extracted = StringBuilder()
            var truncated = false
            val output = object : Writer() {
                override fun write(chars: CharArray, offset: Int, length: Int) {
                    val remaining = 16_000 - extracted.length
                    extracted.append(chars, offset, minOf(remaining, length))
                    if (length > remaining) throw PdfExcerptLimit()
                }
                override fun flush() = Unit
                override fun close() = Unit
            }
            try {
                PDFTextStripper().apply {
                    startPage = firstPage
                    endPage = lastPage
                }.writeText(pdf, output)
            } catch (_: PdfExcerptLimit) {
                truncated = true
            }
            JSONObject().put("format", "pdf").put("content", extracted.toString().trim())
                .put("firstPage", firstPage).put("lastPage", lastPage).put("pageCount", pages)
                .put("hasText", extracted.isNotBlank()).put("truncated", truncated)
                .put("nextPage", if (!truncated && lastPage < pages) lastPage + 1 else JSONObject.NULL)
        }
    } catch (failure: WorkspaceFailure) {
        throw failure
    } catch (_: InvalidPasswordException) {
        throw WorkspaceFailure("pdf_password_required", "Upload an unlocked PDF.")
    } catch (_: IOException) {
        throw WorkspaceFailure("invalid_document", "The file is not a readable PDF.")
    }
    return result
}

private class PdfExcerptLimit : IOException()

internal fun extractDocx(path: Path): JSONObject {
    val limit = 2 * 1024 * 1024
    val document = try {
        ZipFile(path.toFile()).use { archive ->
            val entry = archive.getEntry("word/document.xml")
                ?: throw WorkspaceFailure("invalid_document", "The DOCX main document part is missing.")
            if (entry.size > limit) throw WorkspaceFailure("document_too_large", "The document XML exceeds 2 MiB.")
            archive.getInputStream(entry).use { it.readNBytes(limit + 1) }
        }
    } catch (_: ZipException) {
        throw WorkspaceFailure("invalid_document", "The file is not a readable DOCX archive.")
    }
    if (document.size > limit) throw WorkspaceFailure("document_too_large", "The document XML exceeds 2 MiB.")
    val content = StringBuilder()
    val maxCharacters = 16_000
    val namespaces = setOf("http://schemas.openxmlformats.org/wordprocessingml/2006/main",
        "http://purl.oclc.org/ooxml/wordprocessingml/main")
    val factory = XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }
    var truncated = false
    try {
        val reader = factory.createXMLStreamReader(document.inputStream())
        try {
            while (reader.hasNext()) {
                val event = reader.next()
                if (event == XMLStreamConstants.DTD)
                    throw WorkspaceFailure("invalid_document", "Document XML declarations are unsupported.")
                val next = when {
                    event == XMLStreamConstants.START_ELEMENT && reader.namespaceURI in namespaces &&
                        reader.localName == "t" -> reader.elementText
                    event == XMLStreamConstants.END_ELEMENT && reader.namespaceURI in namespaces &&
                        reader.localName == "p" -> "\n"
                    else -> continue
                }
                val remaining = maxCharacters - content.length
                content.append(next.take(remaining))
                if (next.length > remaining) {
                    truncated = true
                    break
                }
            }
        } finally {
            reader.close()
        }
    } catch (_: XMLStreamException) {
        throw WorkspaceFailure("invalid_document", "The document contains invalid Word XML.")
    }
    return JSONObject().put("format", "docx").put("content", content.toString().trim())
        .put("truncated", truncated)
}
