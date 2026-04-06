package com.codeodyssey.retrodriveaa

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * WifiTransferServer: Lightweight HTTP Server for DOS Game Uploads
 * 
 * This server allows users to upload DOS games wirelessly to their Android Automotive device.
 * Based on NanoHTTPD concepts but simplified for our specific use case.
 * 
 * Usage:
 * ```
 * val server = WifiTransferServer(context, 8080)
 * server.start()
 * // Server is now running, users can access http://<device-ip>:8080
 * server.stop()
 * ```
 */
class WifiTransferServer(
    private val context: Context,
    private val port: Int = 8080
) {
    companion object {
        private const val TAG = "WifiTransferServer"
        private const val BUFFER_SIZE = 8192
        private const val ACCEPT_TIMEOUT_MS = 1500
        private const val CLIENT_SOCKET_TIMEOUT_MS = 60_000
        private const val MAX_REQUEST_HEADER_BYTES = 16 * 1024
        private const val MAX_UPLOAD_BYTES = 1_024L * 1_024L * 1_024L
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var executorService: ExecutorService = Executors.newCachedThreadPool()
    
    // Directory where DOS games will be stored (matches DOSBox asset location)
    private val gamesDirectory: File by lazy {
        val dir = File(context.getExternalFilesDir(null), "game")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    /**
     * Start the HTTP server
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Server already running")
            return
        }

        if (executorService.isShutdown || executorService.isTerminated) {
            executorService = Executors.newCachedThreadPool()
        }

        try {
            val boundSocket = ServerSocket()
            boundSocket.reuseAddress = true
            boundSocket.bind(InetSocketAddress("0.0.0.0", port))
            boundSocket.soTimeout = ACCEPT_TIMEOUT_MS
            serverSocket = boundSocket
            isRunning = true
            
            Log.i(TAG, "Server started on 0.0.0.0:$port")
            Log.i(TAG, "Games directory: ${gamesDirectory.absolutePath}")
            Log.i(TAG, "Reachable at: ${getReachableIps().joinToString { "http://$it:$port" }}")

            // Accept connections in a background thread
            executorService.execute {
                while (isRunning && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let {
                            executorService.execute { handleClient(it) }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Expected so we can periodically re-check lifecycle state.
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting connection", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            isRunning = false
        }
    }

    /**
     * Stop the HTTP server
     */
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            executorService.shutdownNow()
            // Clear static reference in DOSBoxActivity
            try {
                val dosBoxActivityClass = Class.forName("com.dosbox.emu.DOSBoxActivity")
                val field = dosBoxActivityClass.getField("wifiTransferServerInstance")
                if (field.get(null) == this) {
                    field.set(null, null)
                    Log.i(TAG, "Cleared wifiTransferServerInstance reference in DOSBoxActivity")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear wifiTransferServerInstance: ${e.message}")
            }
            Log.i(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    /**
     * Handle an individual client connection
     */
    private fun handleClient(socket: Socket) {
        val clientAddress = socket.inetAddress.hostAddress
        Log.i(TAG, "=== New client connection from $clientAddress ===")
        
        try {
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.soTimeout = CLIENT_SOCKET_TIMEOUT_MS

            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            
            Log.d(TAG, "Input/Output streams obtained")

            // Read headers manually from raw stream (don't use BufferedReader - it buffers ahead!)
            val headerBytes = ByteArrayOutputStream()
            var prevByte = 0
            var crlfCount = 0
            var headerByteCount = 0
            
            Log.d(TAG, "Starting to read headers...")
            
            // Read until we find \r\n\r\n (end of headers)
            while (true) {
                val b = input.read()
                if (b == -1) {
                    Log.w(TAG, "Connection closed while reading headers (read $headerByteCount bytes)")
                    return
                }
                
                headerBytes.write(b)
                headerByteCount++
                
                // Count CRLF sequences
                if (prevByte == '\r'.code && b == '\n'.code) {
                    crlfCount++
                    if (crlfCount == 2) {
                        Log.d(TAG, "Headers complete ($headerByteCount bytes)")
                        break // Found \r\n\r\n
                    }
                } else if (b != '\r'.code) {
                    crlfCount = 0
                }
                
                prevByte = b
                
                // Safety check
                if (headerByteCount > MAX_REQUEST_HEADER_BYTES) {
                    Log.e(TAG, "Headers too large, aborting")
                    sendResponse(output, 400, "Bad Request", "text/plain", "Headers too large")
                    return
                }
            }

            // Parse headers
            val headerText = headerBytes.toString("UTF-8")
            val lines = headerText.split("\r\n")
            
            Log.d(TAG, "Parsed ${lines.size} header lines")
            
            if (lines.isEmpty()) {
                Log.e(TAG, "No header lines found")
                sendResponse(output, 400, "Bad Request", "text/plain", "Invalid request")
                return
            }

            // Parse request line
            val requestLine = lines[0]
            Log.i(TAG, ">>> $requestLine")

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                Log.e(TAG, "Invalid request line: $requestLine")
                sendResponse(output, 400, "Bad Request", "text/plain", "Invalid request")
                return
            }

            val method = parts[0]
            val uri = parts[1]

            // Parse header lines
            val headers = mutableMapOf<String, String>()
            var contentLength = 0L
            
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) break
                
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim().lowercase()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key] = value
                    
                    if (key == "content-length") {
                        contentLength = value.toLongOrNull() ?: 0L
                        Log.d(TAG, "Content-Length: $contentLength")
                    }
                    if (key == "content-type") {
                        Log.d(TAG, "Content-Type: $value")
                    }
                }
            }

            Log.i(TAG, "Routing: $method $uri")
            
            // Route the request
            when {
                method == "GET" && uri == "/" -> {
                    Log.d(TAG, "Serving home page")
                    handleHomePage(output)
                }
                method == "POST" && uri.startsWith("/upload") -> {
                    Log.i(TAG, ">>> UPLOAD REQUEST: $contentLength bytes")
                    handleUpload(input, output, headers, contentLength)
                }
                method == "GET" && uri == "/health" -> {
                    sendResponse(output, 200, "OK", "text/plain", "ok")
                }
                method == "GET" && uri.startsWith("/download/") -> {
                    Log.d(TAG, "Serving download")
                    handleDownload(output, uri)
                }
                else -> {
                    Log.w(TAG, "404: $method $uri")
                    sendResponse(output, 404, "Not Found", "text/plain", "Page not found")
                }
            }

            // Ensure response is sent before closing
            Log.d(TAG, "Flushing output...")
            output.flush()
            Log.d(TAG, "Output flushed")
            
            try {
                socket.close()
                Log.i(TAG, "=== Connection closed successfully ===")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "!!! ERROR handling client: ${e.message}", e)
            e.printStackTrace()
            try {
                socket.close()
            } catch (closeError: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Send the home page with upload form
     */
    private fun handleHomePage(output: OutputStream) {
        val uploadAllowed = TrialModeConfig.isWifiUploadAllowed(context)
        val uploadSection = if (uploadAllowed) {
            """
                    <form id="uploadForm" method="POST" action="/upload" enctype="multipart/form-data">
                        <input type="file" name="file" id="fileInput" accept=".zip,.ZIP" required><br>
                        <button type="submit" id="uploadBtn">Upload</button>
                    </form>
                    <div class="progress-container" id="progressContainer">
                        <div class="progress-bar" id="progressBar">0%</div>
                    </div>
                    <div id="status"></div>
            """.trimIndent()
        } else {
            """
                    <p style="color:#ffb74d; font-weight:bold;">Trial upload used. Purchase full access to unlock unlimited WiFi uploads.</p>
                    <div id="status"></div>
            """.trimIndent()
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>RetrodriveAA File Transfer</title>
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        max-width: 800px;
                        margin: 50px auto;
                        padding: 20px;
                        background: #1a1a1a;
                        color: #ffffff;
                    }
                    h1 { color: #4CAF50; }
                    .upload-box {
                        border: 2px dashed #4CAF50;
                        padding: 30px;
                        text-align: center;
                        margin: 20px 0;
                        border-radius: 10px;
                    }
                    .progress-container {
                        width: 100%;
                        background: #333;
                        border-radius: 10px;
                        margin: 20px 0;
                        padding: 3px;
                        display: none;
                    }
                    .progress-bar {
                        height: 30px;
                        background: linear-gradient(90deg, #4CAF50, #66bb6a);
                        border-radius: 8px;
                        width: 0%;
                        transition: width 0.3s ease;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: white;
                        font-weight: bold;
                        font-size: 14px;
                    }
                    button {
                        background: #4CAF50;
                        color: white;
                        padding: 12px 30px;
                        border: none;
                        border-radius: 5px;
                        cursor: pointer;
                        font-size: 16px;
                    }
                    button:hover { background: #45a049; }
                    button:disabled {
                        background: #888;
                        cursor: not-allowed;
                    }
                    .file-list {
                        margin-top: 30px;
                        padding: 20px;
                        background: #2a2a2a;
                        border-radius: 10px;
                    }
                    .file-item {
                        padding: 10px;
                        margin: 5px 0;
                        background: #3a3a3a;
                        border-radius: 5px;
                    }
                    input[type="file"] { margin: 20px 0; }
                </style>
            </head>
            <body>
                <h1>RetrodriveAA File Transfer</h1>
                <div class="upload-box">
                    <h2>Upload DOS Games</h2>
                    <p>Select .zip files containing DOS games</p>
                    $uploadSection
                </div>
                <script>
                    // Handle upload
                    if (!document.getElementById('uploadForm')) {
                        // Upload disabled in trial mode
                    } else {
                    document.getElementById('uploadForm').onsubmit = (e) => {
                        e.preventDefault();
                        const status = document.getElementById('status');
                        const fileInput = document.getElementById('fileInput');
                        const uploadBtn = document.getElementById('uploadBtn');
                        const progressContainer = document.getElementById('progressContainer');
                        const progressBar = document.getElementById('progressBar');
                        const file = fileInput.files[0];
                        
                        if (!file) return;
                        
                        // Show progress bar and disable upload button
                        progressContainer.style.display = 'block';
                        progressBar.style.width = '0%';
                        progressBar.textContent = '0%';
                        uploadBtn.disabled = true;
                        status.innerHTML = '<p>Preparing upload...</p>';
                        
                        const formData = new FormData();
                        formData.append('file', file);
                        
                        // Use XMLHttpRequest to track progress
                        const xhr = new XMLHttpRequest();
                        const MAX_RETRIES = 2;
                        let attempts = 0;
                        let completed = false;

                        const sendUpload = () => {
                            attempts++;
                            status.innerHTML = '<p>Uploading (attempt ' + attempts + ')...</p>';
                            xhr.open('POST', '/upload', true);
                            xhr.timeout = 90000;
                            xhr.send(formData);
                        };
                        
                        // Upload progress
                        xhr.upload.addEventListener('progress', (e) => {
                            if (e.lengthComputable) {
                                const percentComplete = Math.round((e.loaded / e.total) * 100);
                                progressBar.style.width = percentComplete + '%';
                                progressBar.textContent = percentComplete + '%';
                                
                                const mbLoaded = (e.loaded / 1048576).toFixed(1);
                                const mbTotal = (e.total / 1048576).toFixed(1);
                                status.innerHTML = '<p>Uploading: ' + mbLoaded + ' MB / ' + mbTotal + ' MB (' + percentComplete + '%)</p>';
                            }
                        });
                        
                        // Upload complete (server processing)
                        xhr.addEventListener('load', () => {
                            completed = true;
                            if (xhr.status === 200) {
                                progressBar.style.width = '100%';
                                progressBar.textContent = '100%';
                                status.innerHTML = '<p style="color: #4CAF50;">Upload successful!</p>';
                                fileInput.value = '';
                                uploadBtn.disabled = false;
                                setTimeout(() => location.reload(), 2000);
                            } else {
                                progressBar.style.background = '#f44336';
                                if (attempts <= MAX_RETRIES) {
                                    status.innerHTML = '<p style="color: #ff9800;">Retrying after server error...</p>';
                                    setTimeout(sendUpload, 700);
                                } else {
                                    status.innerHTML = '<p style="color: #f44336;">✗ Upload failed</p>';
                                    uploadBtn.disabled = false;
                                }
                            }
                        });
                        
                        // Upload error
                        xhr.addEventListener('error', () => {
                            progressBar.style.background = '#f44336';
                            if (!completed && attempts <= MAX_RETRIES) {
                                status.innerHTML = '<p style="color: #ff9800;">Connection interrupted, retrying...</p>';
                                setTimeout(sendUpload, 700);
                            } else {
                                status.innerHTML = '<p style="color: #f44336;">✗ Network error</p>';
                                uploadBtn.disabled = false;
                            }
                        });

                        // Upload timeout
                        xhr.addEventListener('timeout', () => {
                            progressBar.style.background = '#ff9800';
                            if (!completed && attempts <= MAX_RETRIES) {
                                status.innerHTML = '<p style="color: #ff9800;">Upload timed out, retrying...</p>';
                                setTimeout(sendUpload, 700);
                            } else {
                                status.innerHTML = '<p style="color: #f44336;">✗ Upload timed out</p>';
                                uploadBtn.disabled = false;
                            }
                        });
                        
                        // Upload aborted
                        xhr.addEventListener('abort', () => {
                            status.innerHTML = '<p style="color: #ff9800;">Upload cancelled</p>';
                            uploadBtn.disabled = false;
                        });
                        
                        // Send request
                        sendUpload();
                    };
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        sendResponse(output, 200, "OK", "text/html", html)
    }

    /**
     * Handle file upload
     */
    private fun handleUpload(input: InputStream, output: OutputStream, headers: Map<String, String>, contentLength: Long) {
        if (!TrialModeConfig.isWifiUploadAllowed(context)) {
            sendResponse(output, 403, "Forbidden", "text/plain", "Trial upload already used. Purchase full access to upload files.")
            return
        }

        if (contentLength <= 0L) {
            sendResponse(output, 411, "Length Required", "text/plain", "Missing or invalid Content-Length")
            return
        }

        if (contentLength > MAX_UPLOAD_BYTES) {
            sendResponse(output, 413, "Payload Too Large", "text/plain", "Upload too large. Maximum allowed size is 1GB.")
            return
        }

        var tempFile: File? = null
        var uploadCompleted = false

        try {
            Log.d(TAG, "Upload started, content-length: $contentLength")
            
            val contentType = headers["content-type"] ?: ""
            if (!contentType.contains("multipart/form-data")) {
                sendResponse(output, 400, "Bad Request", "text/plain", "Expected multipart/form-data")
                return
            }

            // Extract boundary
            val boundaryStr = contentType.substringAfter("boundary=").trim()
            if (boundaryStr.isEmpty()) {
                sendResponse(output, 400, "Bad Request", "text/plain", "No boundary found")
                return
            }

            Log.d(TAG, "Boundary: $boundaryStr")
            val boundary = "--$boundaryStr"
            val boundaryBytes = boundary.toByteArray()

            // Stream-based approach: don't load entire file into memory!
            // Read until we find the file part
            var filename: String? = null
            val headerBuffer = StringBuilder()
            var state = 0 // 0=looking for boundary, 1=reading headers, 2=reading file data
            var ch: Int
            var lineBuffer = StringBuilder()
            
            Log.d(TAG, "Parsing multipart stream (streaming mode)...")
            
            // First, skip to first boundary and read part headers
            while (input.read().also { ch = it } != -1) {
                if (state == 0) {
                    // Looking for boundary line
                    lineBuffer.append(ch.toChar())
                    if (ch == '\n'.code) {
                        val line = lineBuffer.toString().trim()
                        if (line.startsWith(boundary)) {
                            Log.d(TAG, "Found initial boundary")
                            state = 1
                            lineBuffer = StringBuilder()
                        } else {
                            lineBuffer = StringBuilder()
                        }
                    }
                } else if (state == 1) {
                    // Reading part headers
                    lineBuffer.append(ch.toChar())
                    if (ch == '\n'.code) {
                        val line = lineBuffer.toString().trim()
                        
                        if (line.isEmpty()) {
                            // Empty line = end of headers, start of file data
                            Log.d(TAG, "Headers complete, starting file data")
                            
                            // Extract filename from collected headers
                            val filenameMatch = Regex("filename=\"([^\"]+)\"").find(headerBuffer.toString())
                            filename = filenameMatch?.groupValues?.get(1)
                            
                            if (filename != null) {
                                Log.i(TAG, "Filename: $filename")
                                state = 2
                                break // Exit loop, start streaming file data
                            } else {
                                Log.e(TAG, "No filename found in headers")
                                sendResponse(output, 400, "Bad Request", "text/plain", "No filename in upload")
                                return
                            }
                        } else {
                            headerBuffer.append(line).append("\n")
                            lineBuffer = StringBuilder()
                        }
                    }
                }
            }

            if (filename == null || state != 2) {
                Log.e(TAG, "Failed to parse multipart headers")
                sendResponse(output, 400, "Bad Request", "text/plain", "Invalid multipart data")
                return
            }

            // Now stream the file data directly to a temp file
            Log.i(TAG, "Streaming file to disk: $filename")
            tempFile = File(gamesDirectory, "$filename.uploading")
            val activeTempFile = tempFile
            
            // Buffered streaming for maximum performance
            FileOutputStream(activeTempFile).use { fos ->
                val buffer = ByteArray(65536) // 64KB buffer for speed
                val boundaryWithNewline = "\r\n$boundary".toByteArray()
                val searchBuffer = ByteArrayOutputStream()
                var totalWritten = 0L
                var lastLogTime = System.currentTimeMillis()
                var lastLogBytes = 0L
                
                // Read in chunks for much better performance
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    // Add to search buffer to detect boundary
                    searchBuffer.write(buffer, 0, bytesRead)
                    
                    // Check if we have enough data to search for boundary
                    if (searchBuffer.size() >= boundaryWithNewline.size) {
                        val data = searchBuffer.toByteArray()
                        
                        // Look for boundary in the accumulated data
                        var boundaryPos = -1
                        for (i in 0..data.size - boundaryWithNewline.size) {
                            var match = true
                            for (j in boundaryWithNewline.indices) {
                                if (data[i + j] != boundaryWithNewline[j]) {
                                    match = false
                                    break
                                }
                            }
                            if (match) {
                                boundaryPos = i
                                break
                            }
                        }
                        
                        if (boundaryPos != -1) {
                            // Found boundary - write everything before it
                            fos.write(data, 0, boundaryPos)
                            totalWritten += boundaryPos
                            Log.d(TAG, "Found ending boundary at position $boundaryPos")
                            break
                        } else {
                            // No boundary yet - write all but last few bytes (keep buffer for boundary detection)
                            val safeToWrite = data.size - boundaryWithNewline.size
                            if (safeToWrite > 0) {
                                fos.write(data, 0, safeToWrite)
                                totalWritten += safeToWrite
                                
                                // Keep the last portion for boundary detection in next iteration
                                searchBuffer.reset()
                                searchBuffer.write(data, safeToWrite, data.size - safeToWrite)
                            }
                        }
                    }
                    
                    // Progress logging every 5MB or 1 second
                    val now = System.currentTimeMillis()
                    if (totalWritten - lastLogBytes >= 5242880 || now - lastLogTime >= 1000) {
                        val mbWritten = totalWritten / 1048576
                        val speed = if (now > lastLogTime) {
                            ((totalWritten - lastLogBytes) / 1024.0 / (now - lastLogTime)).toInt()
                        } else 0
                        Log.i(TAG, "Uploaded ${mbWritten}MB (${speed} KB/s)")
                        lastLogTime = now
                        lastLogBytes = totalWritten
                    }
                }
                
                fos.flush()
                Log.i(TAG, "File streamed successfully: $totalWritten bytes")
            }

            // Rename temp file to final name
            val finalFile = File(gamesDirectory, filename)
            if (finalFile.exists()) finalFile.delete()
            if (!activeTempFile.renameTo(finalFile)) {
                throw IOException("Failed to finalize uploaded file")
            }
            
            Log.i(TAG, "Processing uploaded file...")
            
            // Extract if it's a zip
            if (filename.endsWith(".zip", ignoreCase = true)) {
                val gameName = filename.substringBeforeLast(".zip")
                val gameDir = File(gamesDirectory, gameName)
                gameDir.mkdirs()
                
                Log.i(TAG, "Extracting zip to $gameName...")
                extractZip(finalFile, gameDir)
                finalFile.delete()
                Log.i(TAG, "Extraction complete")
            }

            // Consume the trial only after a fully successful upload and processing.
            TrialModeConfig.markTrialWifiUploadSuccess(context)
            uploadCompleted = true

            sendResponse(output, 200, "OK", "text/plain", "File uploaded successfully: $filename")
            Log.i(TAG, "Upload completed successfully")

        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Upload timed out waiting for data", e)
            sendResponse(output, 408, "Request Timeout", "text/plain", "Upload timed out. Please retry.")
            
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OUT OF MEMORY: ${e.message}", e)
            try {
                sendResponse(output, 507, "Insufficient Storage", "text/plain", "File too large: ${e.message}")
            } catch (responseError: Exception) {
                Log.e(TAG, "Failed to send OOM error response", responseError)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}", e)
            e.printStackTrace()
            try {
                sendResponse(output, 500, "Internal Server Error", "text/plain", "Upload failed: ${e.message}")
            } catch (responseError: Exception) {
                Log.e(TAG, "Failed to send error response", responseError)
            }
        } finally {
            if (!uploadCompleted) {
                try {
                    if (tempFile?.exists() == true && !tempFile.delete()) {
                        Log.w(TAG, "Failed to clean up partial upload file: ${tempFile.absolutePath}")
                    }
                } catch (cleanupError: Exception) {
                    Log.w(TAG, "Error during upload cleanup: ${cleanupError.message}")
                }
            }
        }
    }

    /**
     * Extract a zip file to a directory
     */
    private fun extractZip(zipFile: File, targetDir: File) {
        try {
            java.util.zip.ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val file = File(targetDir, entry.name)
                    
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting zip", e)
            throw e
        }
    }

    /**
     * Handle file download
     */
    private fun handleDownload(output: OutputStream, uri: String) {
        val filename = uri.substringAfter("/download/")
        val file = File(gamesDirectory, filename)

        if (!file.exists() || !file.isFile) {
            sendResponse(output, 404, "Not Found", "text/plain", "File not found")
            return
        }

        try {
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: ${file.length()}\r\n" +
                    "Content-Disposition: attachment; filename=\"${file.name}\"\r\n" +
                    "\r\n"

            output.write(headers.toByteArray())
            file.inputStream().use { it.copyTo(output) }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
        }
    }

    /**
     * Send an HTTP response
     */
    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusMessage: String,
        contentType: String,
        content: String
    ) {
        try {
            val bodyBytes = content.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 $statusCode $statusMessage\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"

            Log.d(TAG, "Sending response: $statusCode $statusMessage (${bodyBytes.size} bytes)")
            output.write(response.toByteArray())
            output.write(bodyBytes)
            output.flush()
            Log.d(TAG, "Response sent and flushed")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending response: ${e.message}", e)
            throw e
        }
    }

    /**
     * Get the device's IP address
     * Works for both real devices (WiFi) and emulators
     */
    fun getIpAddress(): String {
        return getReachableIps().firstOrNull() ?: "localhost"
    }

    fun getReachableIps(): List<String> {
        return try {
            val uniqueIps = LinkedHashSet<String>()
            getWifiPrivateIp()?.let { uniqueIps.add(it) }
            uniqueIps.addAll(collectPrivateIpv4Addresses())

            if (uniqueIps.isEmpty()) {
                Log.w(TAG, "No private network interface found - using localhost")
                listOf("localhost")
            } else {
                uniqueIps.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
            listOf("localhost")
        }
    }

    private fun getWifiPrivateIp(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val wifiIp = wifiInfo.ipAddress
            if (wifiIp != 0) {
                val ip = intToIpString(wifiIp)
                if (ip != "0.0.0.0" && isPrivateIp(ip) && isLikelyLanReachableIp(ip)) {
                    Log.i(TAG, "Using WiFi IP: $ip")
                    return ip
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun collectPrivateIpv4Addresses(): List<String> {
        val preferredIps = mutableListOf<String>()
        val fallbackIps = mutableListOf<String>()
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue

            val intfName = networkInterface.name.lowercase()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    val hostAddress = address.hostAddress
                    if (hostAddress != null && !hostAddress.startsWith("127.") && !hostAddress.startsWith("169.254.") && isPrivateIp(hostAddress) && isLikelyLanReachableIp(hostAddress)) {
                        Log.d(TAG, "Found IP: $hostAddress on interface: $intfName")
                        if (isPreferredLanInterface(intfName)) {
                            preferredIps.add(hostAddress)
                        } else {
                            fallbackIps.add(hostAddress)
                        }
                    }
                }
            }
        }
        return (preferredIps + fallbackIps).distinct()
    }

    private fun isPreferredLanInterface(interfaceName: String): Boolean {
        return interfaceName.contains("wlan") || interfaceName.contains("wifi") || interfaceName.contains("eth") || interfaceName.contains("ap")
    }

    fun isLikelyLanReachableIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false

        if (!isPrivateIp(ip)) return false

        if (first == 10 && (second == 0 || second == 1 || second == 2 || second == 3)) {
            return false
        }

        return true
    }

    // Helper to convert int IP to string
    private fun intToIpString(ip: Int): String {
        return listOf(
            ip and 0xFF,
            (ip shr 8) and 0xFF,
            (ip shr 16) and 0xFF,
            (ip shr 24) and 0xFF
        ).joinToString(".")
    }
    
    /**
     * Check if IP address is in a private/local range
     * Private ranges: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
     */
    private fun isPrivateIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        
        try {
            val first = parts[0].toInt()
            val second = parts[1].toInt()
            
            return when (first) {
                10 -> true  // 10.0.0.0/8
                172 -> second in 16..31  // 172.16.0.0/12
                192 -> second == 168  // 192.168.0.0/16
                else -> false
            }
        } catch (e: NumberFormatException) {
            return false
        }
    }

    /**
     * Check if server is running
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Get the port number
     */
    fun getPort(): Int = port
}
