package app.shaz.tunz
// cuz casting needs a http connection sigh

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class LocalHttpServer (private val basePath: String) {
   private var serverSocket: ServerSocket? = null
   private var running = false

   fun start ()
   {  try {
        val ss = ServerSocket (8765).also { it.reuseAddress = true }
         serverSocket = ss
         running = true
         Thread {
            while (running) {
               try {
                 val client = ss.accept ()
                  Thread { handle (client) }.start ()
               }
               catch (e: Exception) { if (running)  Log.e ("HTTP", "accept", e) }
            }
         }.start ()
      }
      catch (e: Exception) { Log.e ("HTTP", "start", e) }
   }

   fun stop ()
   {  running = false
      serverSocket?.close ()
      serverSocket = null
   }

   private fun handle (sock: Socket)
   { var uri: String? = null
      try {
        val br    = BufferedReader (InputStreamReader (sock.getInputStream ()))
        val lines = mutableListOf<String> ()
        var line  = br.readLine ()
         while (line != null && line.isNotEmpty ()) {
            lines.add (line);  line = br.readLine ()
         }
        val parts   = lines.firstOrNull ()?.split (" ") ?: return
        val rawPath = parts.getOrNull (1) ?: return
         uri = URLDecoder.decode (rawPath.trimStart ('/'), "UTF-8")
         Log.d ("HTTP", "request $uri")
        val file    = File ("$basePath/$uri")
        val out     = sock.getOutputStream ()
         if (! file.exists () || ! file.isFile) {
            out.write ("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                          .toByteArray ())
            return
         }
        val fileLen  = file.length ()
        val rangeHdr = lines.firstOrNull {
                          it.startsWith ("Range:", ignoreCase = true) }
                       ?.substringAfter (":")?.trim ()
         if (rangeHdr != null && rangeHdr.startsWith ("bytes=")) {
           val spec  = rangeHdr.removePrefix ("bytes=")
           val start = spec.substringBefore ("-").toLongOrNull () ?: 0L
           val end   = spec.substringAfter  ("-").toLongOrNull ()
                          ?: (fileLen - 1)
           val len   = end - start + 1
            out.write (("HTTP/1.1 206 Partial Content\r\n" +
               "Content-Type:   audio/mpeg\r\n" +
               "Content-Length: $len\r\n" +
               "Content-Range:  bytes $start-$end/$fileLen\r\n" +
               "Accept-Ranges:  bytes\r\n\r\n").toByteArray ())
            file.inputStream ().use { stream (it, out, start, len) }
         }
         else {
            out.write (("HTTP/1.1 200 OK\r\n" +
               "Content-Type:   audio/mpeg\r\n" +
               "Content-Length: $fileLen\r\n" +
               "Accept-Ranges:  bytes\r\n\r\n").toByteArray ())
            file.inputStream ().use { stream (it, out, 0L, fileLen) }
         }
         out.flush ()
      }
      catch (e: Exception) { Log.e ("HTTP", "handle $uri", e) }
      finally { try { sock.close () } catch (e: Exception) { } }
   }

   private fun stream (inp: InputStream, out: OutputStream,
                       skip: Long, len: Long)
   {  inp.skip (skip)
     val buf  = ByteArray (65536)
      var left = len
      while (left > 0) {
        val n = inp.read (buf, 0, minOf (buf.size.toLong (), left).toInt ())
         if (n == -1)  break
         out.write (buf, 0, n)
         left -= n
      }
   }
}
