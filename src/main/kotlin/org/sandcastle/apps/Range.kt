package org.sandcastle.apps

import com.google.common.util.concurrent.RateLimiter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class Range {
    var start: Long = 0
    var end: Long = 0
    var length: Long = 0
    var total: Long = 0

    /**
     * Construct a byte range.
     * @param start Start of the byte range.
     * @param end End of the byte range.
     * @param total Total length of the byte source.
     */
    constructor(start: Long, end: Long, total: Long) {
        this.start = start
        this.end = end
        this.length = end - start + 1
        this.total = total
    }

    companion object {
        fun sublong(value: String, beginIndex: Int, endIndex: Int): Long {
            val substring = value.substring(beginIndex, endIndex)
            return if (substring.isNotEmpty()) java.lang.Long.parseLong(substring) else -1
        }

        @Throws(IOException::class)
        fun copy(input: InputStream, output: OutputStream, inputSize: Long, start: Long, length: Long) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int

            val rateLimiter = RateLimiter.create(5 * 1000 * 1000.0) //5 mbps

            if (inputSize == length) {
                // Write full range.
                while (input.read(buffer).also { read = it } > 0) {
                    rateLimiter.acquire(read) //TODO rate limiter mark
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } else {
                input.skip(start)
                var toRead = length

                while (input.read(buffer).also { read = it } > 0) {
                    if (toRead - read > 0) {
                        rateLimiter.acquire(read) //TODO rate limiter mark
                        output.write(buffer, 0, read)
                        output.flush()
                    } else {
                        rateLimiter.acquire((toRead + read).toInt()) //TODO rate limiter mark
                        output.write(buffer, 0, (toRead + read).toInt())
                        output.flush()
                        break
                    }
                    toRead -= read
                }
            }
        }
    }
}