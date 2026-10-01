package com.thotapalli.visidock

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PortableBackupTest {
    private fun fixture(root: java.io.File): ByteArray {
        val manifest = java.io.File(root,"manifest-source").apply {writeText("{}")}
        val photo = java.io.File(root,"photo-source").apply {writeBytes(ByteArray(220_000).also {java.util.Random(42).nextBytes(it)})}
        return ByteArrayOutputStream().also { PortableBackup.write(it, "long password".toCharArray(),
            sequenceOf("manifest.json" to manifest, "photos/card-1/front.jpg" to photo)) }.toByteArray()
    }
    @Test fun encryptedRoundTripRequiresPasswordAndAuthenticatesWholeFile() {
        val root=Files.createTempDirectory("backup-test").toFile()
        try {
            val source=java.io.File(root,"source").apply {writeText("{\"private\":\"contact\"}")}
            val bytes=ByteArrayOutputStream().also {PortableBackup.write(it,"long password".toCharArray(),sequenceOf("manifest.json" to source))}.toByteArray()
            assertFalse(bytes.toString(Charsets.UTF_8).contains("contact"))
            val restored=PortableBackup.read(ByteArrayInputStream(bytes),"long password".toCharArray(),root)
            assertEquals(source.readText(),java.io.File(restored,"manifest.json").readText())
            restored.deleteRecursively()
            assertThrows(java.io.IOException::class.java) {PortableBackup.read(ByteArrayInputStream(bytes),"wrong password".toCharArray(),root)}
            bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            assertThrows(java.io.IOException::class.java) {PortableBackup.read(ByteArrayInputStream(bytes),"long password".toCharArray(),root)}
            assertFalse(root.listFiles().orEmpty().any {it.name.startsWith("restore-")})
        } finally {root.deleteRecursively()}
    }
    @Test fun traversalAndDuplicateEntriesAreRejected() {
        val root=Files.createTempDirectory("backup-test").toFile()
        try {
            val source=java.io.File(root,"source").apply {writeText("{}")}
            assertThrows(IllegalArgumentException::class.java) {PortableBackup.write(ByteArrayOutputStream(),"long password".toCharArray(),sequenceOf("../manifest.json" to source))}
            assertThrows(IllegalArgumentException::class.java) {PortableBackup.write(ByteArrayOutputStream(),"long password".toCharArray(),sequenceOf("manifest.json" to source,"manifest.json" to source))}
        } finally {root.deleteRecursively()}
    }
    @Test fun chunkedReadIsBoundedAndAuthenticatesEveryRecordIncludingTerminator() {
        val root=Files.createTempDirectory("backup-test").toFile()
        try {
            val bytes=fixture(root)
            var largestRead=0
            val input=object:java.io.FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun read(b:ByteArray,off:Int,len:Int):Int {largestRead=maxOf(largestRead,len);return super.read(b,off,len)}
            }
            val restored=PortableBackup.read(input,"long password".toCharArray(),root)
            assertArrayEquals(java.io.File(root,"photo-source").readBytes(),java.io.File(restored,"photos/card-1/front.jpg").readBytes())
            assertTrue("No cipher record should require archive-wide buffering",largestRead<=BackupRecords.CHUNK+16)
            restored.deleteRecursively()
            for (cut in listOf(1,20,bytes.size/2)) assertThrows(java.io.IOException::class.java) {
                PortableBackup.read(ByteArrayInputStream(bytes.copyOf(bytes.size-cut)),"long password".toCharArray(),root)
            }
            assertThrows(java.io.IOException::class.java) {PortableBackup.read(ByteArrayInputStream(bytes+byteArrayOf(1)),"long password".toCharArray(),root)}
            assertFalse(root.listFiles().orEmpty().any {it.name.startsWith("restore-")})
        } finally {root.deleteRecursively()}
    }
    @Test fun reorderedEncryptedChunksCannotProduceAnAcceptedArchive() {
        val root=Files.createTempDirectory("backup-test").toFile()
        try {
            val bytes=fixture(root)
            val start="VISIDOCK-BACKUP-2\n".length+20
            val record=4+BackupRecords.CHUNK+16
            val reordered=bytes.copyOf()
            bytes.copyInto(reordered,start,start+record,start+2*record)
            bytes.copyInto(reordered,start+record,start,start+record)
            assertThrows(java.io.IOException::class.java) {PortableBackup.read(ByteArrayInputStream(reordered),"long password".toCharArray(),root)}
            assertFalse(root.listFiles().orEmpty().any {it.name.startsWith("restore-")})
        } finally {root.deleteRecursively()}
    }
    private fun adversarialArchive(name:String,uncompressedBytes:Long):ByteArray {
        val header="VISIDOCK-BACKUP-2\n".toByteArray()+ByteArray(16)+ByteArray(4)
        val spec=javax.crypto.spec.PBEKeySpec("long password".toCharArray(),ByteArray(16),210_000,256)
        val key=javax.crypto.spec.SecretKeySpec(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")
        val out=ByteArrayOutputStream();out.write(header)
        java.util.zip.ZipOutputStream(AuthenticatedBackupOutput(out,key,header,ByteArray(4))).use {zip->
            zip.putNextEntry(java.util.zip.ZipEntry(name))
            val block=ByteArray(64*1024);var left=uncompressedBytes
            while(left>0) {val n=minOf(left,block.size.toLong()).toInt();zip.write(block,0,n);left-=n}
            zip.closeEntry()
        }
        spec.clearPassword()
        return out.toByteArray()
    }
    @Test fun authenticatedZipTraversalAndDecompressionBombAreStillRejected() {
        val root=Files.createTempDirectory("backup-test").toFile()
        try {
            for(bytes in listOf(adversarialArchive("../escape",1),adversarialArchive("manifest.json",32L*1024*1024+1)))
                assertThrows(java.io.IOException::class.java) {PortableBackup.read(ByteArrayInputStream(bytes),"long password".toCharArray(),root)}
            assertFalse(root.listFiles().orEmpty().any {it.name.startsWith("restore-") || it.name=="escape"})
        } finally {root.deleteRecursively()}
    }
}
