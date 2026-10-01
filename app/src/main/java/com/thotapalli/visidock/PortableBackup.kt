package com.thotapalli.visidock

import java.io.*
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable password encryption, independent of device/account keys. No password is persisted. */
object PortableBackup {
    private val magic="VISIDOCK-BACKUP-2\n".toByteArray(Charsets.US_ASCII)
    private const val iterations=210_000
    private const val maxEntries=20_001
    private const val maxEntry=32L*1024*1024
    private const val maxTotal=2L*1024*1024*1024
    private fun key(password:CharArray,salt:ByteArray):SecretKeySpec {
        require(password.size>=10) {"Use a backup password of at least 10 characters."}
        val spec=PBEKeySpec(password,salt,iterations,256)
        val encoded=try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded} finally {spec.clearPassword()}
        return try {SecretKeySpec(encoded,"AES")} finally {encoded.fill(0)}
    }
    private fun validName(name:String)=name=="manifest.json" || Regex("photos/[a-zA-Z0-9_-]{1,100}/(front|back|original|back-original)\\.jpg").matches(name)
    fun write(output:OutputStream,password:CharArray,entries:Sequence<Pair<String,File>>) {
        val random=SecureRandom();val salt=ByteArray(16).also(random::nextBytes);val prefix=ByteArray(4).also(random::nextBytes)
        val key = key(password, salt)
        val header = magic+salt+prefix
        output.write(header)
        ZipOutputStream(AuthenticatedBackupOutput(output,key,header,prefix)).use {zip->
            val seen=mutableSetOf<String>();var total=0L
            entries.forEach {(name,file)->
                require(validName(name) && seen.add(name) && seen.size<=maxEntries) {"Invalid backup entry."}
                require(file.length()<=maxEntry && total+file.length()<=maxTotal) {"Backup exceeds the supported size."}
                zip.putNextEntry(ZipEntry(name))
                var count = 0L
                file.inputStream().use {source ->
                    val buffer = ByteArray(64*1024)
                    while (true) {
                        val n = source.read(buffer); if (n < 0) break
                        count += n; total += n
                        require(count <= maxEntry && total <= maxTotal) { "Backup exceeds the supported size." }
                        zip.write(buffer, 0, n)
                    }
                };zip.closeEntry()
            }
            require("manifest.json" in seen) {"Backup manifest is missing."}
        }
    }
    /** Extracts only into a fresh staging directory. Nothing is imported until GCM authenticates. */
    fun read(input:InputStream,password:CharArray,parent:File):File {
        parent.mkdirs();val stage=java.nio.file.Files.createTempDirectory(parent.toPath(),"restore-").toFile()
        try {
            val header=DataInputStream(input)
            val actual=ByteArray(magic.size).also(header::readFully)
            require(actual.contentEquals(magic)) {"This is not a supported VisiDock backup."}
            val salt=ByteArray(16).also(header::readFully);val prefix=ByteArray(4).also(header::readFully)
            val decrypt=AuthenticatedBackupInput(header,key(password,salt),magic+salt+prefix,prefix)
            // ZIP may stop before its underlying stream reaches the GCM tag. Drain after ZIP EOF.
            val nonClosing=object:FilterInputStream(decrypt) {override fun close() {}}
            val seen=mutableSetOf<String>();var total=0L
            ZipInputStream(nonClosing).use {zip->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(!entry.isDirectory && validName(entry.name) && seen.add(entry.name) && seen.size<=maxEntries) {"Invalid backup contents."}
                    val file=File(stage,entry.name);file.parentFile?.mkdirs();var count=0L
                    file.outputStream().use {out->
                        val buffer=ByteArray(64*1024)
                        while(true) {val n=zip.read(buffer);if(n<0) break
                            count+=n;total+=n;require(count<=maxEntry && total<=maxTotal) {"Backup exceeds the supported size."};out.write(buffer,0,n)
                        }
                    }
                    zip.closeEntry()
                }
            }
            decrypt.use {stream->val buffer=ByteArray(8192);while(stream.read(buffer)!=-1) {}}
            require("manifest.json" in seen) {"Backup manifest is missing."}
            return stage
        } catch(e:Exception) {stage.deleteRecursively();throw IOException("Backup could not be opened. Check the password and file integrity.",e)}
    }
}
