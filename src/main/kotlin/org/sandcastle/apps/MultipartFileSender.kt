package org.sandcastle.apps

import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class MultipartFileSender {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    private val defaultBufferSize = 20_480
    private val defaultExpiryTime = 604_800_000L
    private val multiPartBoundary = "MULTIPART_BYTERANGES"

    private lateinit var filepath: Path
    private lateinit var request: HttpServletRequest
    private lateinit var response: HttpServletResponse

    companion object {
        fun fromPath(path: Path) = MultipartFileSender().apply { setFilepath(path) }
        fun fromFile(file: File) = MultipartFileSender().apply { setFilepath(file.toPath()) }
        fun fromURIString(uri: String) = MultipartFileSender().apply { setFilepath(Paths.get(uri)) }
    }

    private fun setFilepath(filepath: Path) {
        this.filepath = filepath
    }

    fun with(httpRequest: HttpServletRequest) = apply { request = httpRequest }
    fun with(httpResponse: HttpServletResponse) = apply { response = httpResponse }

    @Throws(Exception::class)
    fun serveResource() {

        if (!Files.exists(filepath)) {
            logger.error("File doesn't exist at URI : {}", filepath.toAbsolutePath().toString())
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }

        val length = Files.size(filepath)
        val fileName = filepath.fileName.toString()
        val lastModifiedObj = Files.getLastModifiedTime(filepath)

        if (fileName.isNullOrEmpty() || lastModifiedObj == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            return
        }
        val lastModified = LocalDateTime.ofInstant(lastModifiedObj.toInstant(), ZoneId.of(ZoneOffset.systemDefault().id)).toEpochSecond(ZoneOffset.UTC)
        var contentType = "video/mp4"

        val ifNoneMatch = request.getHeader("If-None-Match")
        if (ifNoneMatch != null && HttpUtils.matches(ifNoneMatch, fileName)) {
            response.setHeader("ETag", fileName)
            response.sendError(HttpServletResponse.SC_NOT_MODIFIED)
            return
        }

        val ifModifiedSince = request.getDateHeader("If-Modified-Since").toLong()
        if (ifNoneMatch == null && ifModifiedSince != -1L && ifModifiedSince + 1000 > lastModified) {
            response.setHeader("ETag", fileName)
            response.sendError(HttpServletResponse.SC_NOT_MODIFIED)
            return
        }

        val ifMatch = request.getHeader("If-Match")
        if (ifMatch != null && !HttpUtils.matches(ifMatch, fileName)) {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED)
            return
        }

        val ifUnmodifiedSince = request.getDateHeader("If-Unmodified-Since").toLong()
        if (ifUnmodifiedSince != -1L && ifUnmodifiedSince + 1000 <= lastModified) {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED)
            return
        }

        val full = Range(0, length - 1, length)
        val ranges = mutableListOf<Range>()

        val rangeHeader = request.getHeader("Range")
        if (rangeHeader != null) {
            if (!rangeHeader.matches(Regex("^bytes=\\d*-\\d*(,\\d*-\\d*)*$"))) {
                response.setHeader("Content-Range", "bytes */$length")
                response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE)
                return
            }

            val ifRange = request.getHeader("If-Range")
            if (ifRange != null && ifRange != fileName) {
                try {
                    val ifRangeTime = request.getDateHeader("If-Range").toLong()
                    if (ifRangeTime != -1L) {
                        ranges.add(full)
                    }
                } catch (ignored: IllegalArgumentException) {
                    ranges.add(full)
                }
            }

            if (ranges.isEmpty()) {
                rangeHeader.substring(6).split(",").forEach { part ->
                    var start = Range.sublong(part, 0, part.indexOf("-")).toLong()
                    var end = Range.sublong(part, part.indexOf("-") + 1, part.length).toLong()

                    if (start == -1L) {
                        start = length - end
                        end = length - 1
                    } else if (end == -1L || end > length - 1) {
                        end = length - 1
                    }

                    if (start > end) {
                        response.setHeader("Content-Range", "bytes */$length")
                        response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE)
                        return
                    }

                    ranges.add(Range(start, end, length))
                }
            }
        }

        // Prepare and initialize response --------------------------------------------------------
        var disposition = "inline"

        if ( contentType == null) {
            contentType = "application/octet-stream"
        } else if (!contentType.startsWith("image")) {
            val accept = request.getHeader("Accept")
            disposition = if (accept != null && HttpUtils.accepts(accept, contentType)) "inline" else "attachment"
        }
        logger.debug("Content-Type: {}", contentType)

        // Initialize Response
        response.reset()
        response.setBufferSize(defaultBufferSize)
        response.setHeader("Content-Type", contentType)
        response.setHeader("Content-Disposition", "$disposition;filename=\"$fileName\"")
        logger.debug("Content-Disposition : {}", disposition)
        response.setHeader("Accept-Ranges", "bytes")
        response.setHeader("ETag", fileName)
        response.setDateHeader("Last-Modified", lastModified)
        response.setDateHeader("Expires", System.currentTimeMillis() + defaultExpiryTime)

        // Send requested file (part(s)) to client
        BufferedInputStream(Files.newInputStream(filepath)).use { input ->
            response.outputStream.use { output ->
                if (ranges.isEmpty() || ranges[0] === full) {

                    // Return full file.
                    logger.info("Return full file")
                    response.contentType = contentType
                    response.setHeader("Content-Range", "bytes " + full.start + "-" + full.end + "/" + full.total)
                    response.setHeader("Content-Length", full.length.toString())
                    Range.copy(input, output, length, full.start, full.length)
                } else if (ranges.size == 1) {

                    // Return single part of file.
                    val r: Range = ranges[0]
                    logger.info("Return 1 part of file : from ({}) to ({})", r.start, r.end)
                    response.contentType = contentType
                    response.setHeader("Content-Range", "bytes " + r.start + "-" + r.end + "/" + r.total)
                    response.setHeader("Content-Length", r.length.toString())
                    response.status = HttpServletResponse.SC_PARTIAL_CONTENT // 206.

                    // Copy single part range.
                    Range.copy(input, output, length, r.start, r.length)
                } else {

                    // Return multiple parts of file.
                    response.contentType = "multipart/byteranges; boundary=$multiPartBoundary"
                    response.status = HttpServletResponse.SC_PARTIAL_CONTENT // 206.

                    // Cast back to ServletOutputStream to get the easy println methods.
                    val sos: ServletOutputStream = output as ServletOutputStream

                    // Copy multi part range.
                    for (r in ranges) {
                        logger.info("Return multi part of file : from ({}) to ({})", r.start, r.end)
                        // Add multipart boundary and header fields for every range.
                        sos.println()
                        sos.println("--$multiPartBoundary")
                        sos.println("Content-Type: $contentType")
                        sos.println("Content-Range: bytes " + r.start + "-" + r.end + "/" + r.total)

                        // Copy single part range of multi part range.
                        Range.copy(input, output, length, r.start, r.length)
                    }

                    // End with multipart boundary.
                    sos.println()
                    sos.println("--$multiPartBoundary--")
                }
            }
        }
    }
}