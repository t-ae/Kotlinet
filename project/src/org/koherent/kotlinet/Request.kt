package org.koherent.kotlinet

import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.*
import kotlin.concurrent.thread

class Request(val method: Method,
              val urlString: String,
              val parameters: Map<String, Any>?,
              val encoding: ParameterEncoding,
              val headers: Map<String, String>?,
              val maxBytesOnMemory: Int) {

    private var completed: Boolean = false

    private var url: URL? = null
    private var urlConnection: HttpURLConnection? = null
    private var bytes: ByteArray? = null
    private var exception: Exception? = null

    private var totalBytesRead: Long = 0L
    private var totalBytesExpectedToRead: Long = -1L

    private var progressHandlers: MutableList<(Long, Long, Long) -> Unit> = ArrayList()
    private var streamHandlers: MutableList<(ByteArray) -> Unit> = ArrayList()
    private var completionHandlers: MutableList<(URL?, HttpURLConnection?, ByteArray?, Exception?) -> Unit> = ArrayList()

    private val out: ByteArrayOutputStream = ByteArrayOutputStream()

    private var canceled = false

    init {
        try {
            val parametersString = when (encoding) {
                ParameterEncoding.URL -> parameters?.entries?.fold("") { result, entry ->
                    result + if (result.isEmpty()) {
                        ""
                    } else {
                        "&"
                    } + URLEncoder.encode(entry.key, Charsets.UTF_8.name()) + "=" + URLEncoder.encode(entry.value.toString(), Charsets.UTF_8.name())
                } ?: ""
            }

            val urlStringWithParameters = when (method) {
                Method.GET, Method.HEAD -> urlString + (if (parametersString.isEmpty()) "" else "?" + parametersString)
                else -> urlString
            }

            val url = URL(urlStringWithParameters)
            this.url = url

            val urlConnection = url.openConnection()

            if (urlConnection !is HttpURLConnection) {
                throw IOException("Unsupported URL connection: " + urlConnection.javaClass.name)
            }
            this.urlConnection = urlConnection

            urlConnection.requestMethod = method.rawValue
            urlConnection.setRequestProperty("Connection", "close")

            headers?.entries?.forEach { urlConnection.setRequestProperty(it.key, it.value) }

            thread {
                try {
                    when (method) {
                        Method.POST -> {
                            urlConnection.doOutput = true
                            BufferedWriter(OutputStreamWriter(urlConnection.outputStream, "UTF-8")).use {
                                it.write(parametersString)
                            }
                        }
                        else -> {
                        }
                    }
                    urlConnection.connect()

                    try {
                        totalBytesExpectedToRead = urlConnection.getHeaderField("Content-Length")?.toLong() ?: -1L
                    } catch(e: NumberFormatException) {
                    }

                    val bufferLength = Math.min(0x10000, if (totalBytesExpectedToRead < 0L || totalBytesExpectedToRead > Int.MAX_VALUE) {
                        Int.MAX_VALUE
                    } else {
                        totalBytesExpectedToRead.toInt()
                    })
                    BufferedInputStream(urlConnection.inputStream, bufferLength).use {
                        val buffer = ByteArray(bufferLength)
                        while (true) {
                            if (canceled) {
                                urlConnection.disconnect()
                            }
                            val length = it.read(buffer)
                            if (length == -1) {
                                break
                            }

                            totalBytesRead += length

                            if (totalBytesRead <= maxBytesOnMemory) {
                                out.write(buffer, 0, length)
                            }

                            val readBytes = buffer.copyOf(length)
//                            val totalBytesRead = this.totalBytesRead // this.totalBytesRead can be changed because of multithreading
                            val totalBytesRead = longArrayOf(this.totalBytesRead) // workaround to avoid "Error:Execution failed for task ':app:dexDebug'."
                            synchronized(this) {
                                try {
//                                    callProgressHandlers(length.toLong(), totalBytesRead)
                                    callProgressHandlers(length.toLong(), totalBytesRead[0]) // workaround to avoid "Error:Execution failed for task ':app:dexDebug'."
                                    callStreamHandlers(readBytes)
                                } catch(e: Exception) {
                                    exception = e
                                }
                            }
                        }
                    }
                    if (totalBytesRead <= maxBytesOnMemory) {
                        bytes = out.toByteArray()
                    }
                } catch(e: Exception) {
                    exception = e
                } finally {
                    complete()
                }
            }
        } catch(e: Exception) {
            exception = e
            complete()
        }
    }

    fun progress(progressHandler: ((Long, Long, Long) -> Unit)?): Request {
        if (progressHandler != null) {
            synchronized(this) {
                if (completed) {
                    callProgressHandler(progressHandler, totalBytesRead, totalBytesRead)
                } else {
                    if (totalBytesRead > 0) {
                        callProgressHandler(progressHandler, totalBytesRead, totalBytesRead)
                    }
                    progressHandlers.add(progressHandler)
                }
            }
        }
        return this
    }

    fun stream(streamHandler: ((ByteArray) -> Unit)?): Request {
        if (streamHandler != null) {
            synchronized(this) {
                if (completed) {
                    val bytes = this.bytes
                    if (bytes == null) {
                        throw IOException("Cannot stream data ($totalBytesRead bytes) which has already streamed exceeding `maxBytesOnMemory` ($maxBytesOnMemory bytes).")
                    }
                    callStreamHandler(streamHandler, bytes)
                } else {
                    if (0 < totalBytesRead && totalBytesRead <= maxBytesOnMemory) {
                        callStreamHandler(streamHandler, out.toByteArray())
                    } else if (totalBytesRead > maxBytesOnMemory) {
                        throw IOException("Cannot stream data ($totalBytesRead bytes) which has already streamed exceeding `maxBytesOnMemory` ($maxBytesOnMemory bytes).")
                    }
                    streamHandlers.add(streamHandler)
                }
            }
        }
        return this
    }

    fun response(completionHandler: (URL?, HttpURLConnection?, ByteArray?, Exception?) -> Unit): Request {
        synchronized(this) {
            if (completed) {
                callCompletionHandler(completionHandler)
            } else {
                completionHandlers.add(completionHandler)
            }
        }
        return this
    }

    private fun callProgressHandler(progressHandler: (Long, Long, Long) -> Unit, bytesRead: Long, totalBytesRead: Long) {
        progressHandler(bytesRead, totalBytesRead, totalBytesExpectedToRead)
    }

    private fun callProgressHandlers(bytesRead: Long, totalBytesRead: Long) {
        progressHandlers.forEach { callProgressHandler(it, bytesRead, totalBytesRead) }
    }

    private fun callStreamHandler(streamHandler: (ByteArray) -> Unit, readBytes: ByteArray) {
        streamHandler(readBytes)
    }

    private fun callStreamHandlers(readBytes: ByteArray) {
        streamHandlers.forEach { callStreamHandler(it, readBytes) }
    }

    private fun callCompletionHandler(completionHandler: (URL?, HttpURLConnection?, ByteArray?, Exception?) -> Unit) {
        completionHandler(url, urlConnection, bytes, exception)
    }

    @Synchronized private fun complete() {
        completionHandlers.forEach { callCompletionHandler(it) }
        progressHandlers.clear()
        streamHandlers.clear()
        completionHandlers.clear()
        completed = true
    }

    fun responseString(charset: Charset? = null, completionHandler: (URL?, HttpURLConnection?, Result<String>) -> Unit): Request {
        return response { url, urlConnection, bytes, exception ->
            val result = bytes?.let { String(it, charset ?: Charsets.UTF_8) }?.let { Result.Success(it) } ?: Result.Failure<String>(bytes, exception!!)
            completionHandler(url, urlConnection, result)
        }
    }

    fun cancel() {
        canceled = true
    }
}