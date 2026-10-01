package com.thotapalli.visidock

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.client.result.AddressBookParsedResult
import com.google.zxing.client.result.URIParsedResult
import com.google.zxing.client.result.ResultParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

sealed interface QrPayload {
    data class Contact(val card:Card):QrPayload
    data class Link(val url:String):QrPayload
    data class Text(val text:String):QrPayload
}
object QrContacts {
    /** Pure interpretation only. A decoded URL is never opened automatically. */
    fun parse(text:String):QrPayload {
        require(text.length<=16000) {"This QR contains too much data to preview."}
        // ZXing does not recognize vCard property groups (for example item1.TEL).
        // Unfold before stripping only the group prefix; never alter field values.
        val parserText=if(text.startsWith("BEGIN:VCARD",ignoreCase=true)) text
            .replace(Regex("\\r?\\n[ \\t]"),"")
            .replace(Regex("(?m)^([A-Za-z0-9-]+)[.](?=[A-Za-z][A-Za-z0-9-]*(?:;|:))"),"") else text
        val parsed=ResultParser.parseResult(Result(parserText,null,null,BarcodeFormat.QR_CODE))
        if(parsed is AddressBookParsedResult) {
            val phones=parsed.phoneNumbers.orEmpty().mapIndexed {index,number->PhoneNumber(number,parsed.phoneTypes?.getOrNull(index).orEmpty())}
            val emails=parsed.emails.orEmpty().filter {ContactChannels.emails(it).contains(it)}
            val websites=parsed.getURLs().orEmpty().filter(::safeUrl)
            return QrPayload.Contact(Card(name=parsed.names?.firstOrNull().orEmpty(),role=parsed.title.orEmpty(),company=parsed.org.orEmpty(),
                phone=phones.firstOrNull()?.number.orEmpty(),phones=phones,email=emails.firstOrNull().orEmpty(),emails=emails,
                website=websites.firstOrNull().orEmpty(),websites=websites,address=parsed.addresses.orEmpty().joinToString("\n"),rawText=text))
        }
        if(parsed is URIParsedResult && safeUrl(parsed.uri)) return QrPayload.Link(parsed.uri)
        return QrPayload.Text(text)
    }
    private fun safeUrl(value:String)=runCatching {val uri=URI(value);uri.scheme?.lowercase() in listOf("https","http") && !uri.host.isNullOrBlank() && uri.userInfo==null}.getOrDefault(false)
    suspend fun decode(file:File):List<QrPayload> =withContext(Dispatchers.Default) {
        val bitmap=ImageCropper.decode(file,2400)
        try {
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val source=RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
            val hints=mapOf(DecodeHintType.TRY_HARDER to true,DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
            val results=runCatching {QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(source)),hints).toList()}
                .getOrElse {runCatching {listOf(MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)),hints))}.getOrDefault(emptyList())}
            results.map {parse(it.text)}.distinct()
        } finally {bitmap.recycle()}
    }
    fun encode(card:Card,fields:ShareFields=ShareFields(),size:Int=768):Bitmap {
        val text=ContactExport.vcard(card,fields)
        require(text.toByteArray(Charsets.UTF_8).size<=2200) {"Too much information for a readable QR. Share fewer fields or use a vCard file."}
        val extent=size.coerceIn(256,1536)
        val matrix=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,extent,extent,mapOf(EncodeHintType.CHARACTER_SET to "UTF-8",EncodeHintType.MARGIN to 4))
        val ink=0xFF071D49.toInt()
        return Bitmap.createBitmap(extent,extent,Bitmap.Config.ARGB_8888).apply {
            val pixels=IntArray(extent*extent) {index->if(matrix[index%extent,index/extent]) ink else Color.WHITE}
            setPixels(pixels,0,extent,0,0,extent,extent)
        }
    }
}
