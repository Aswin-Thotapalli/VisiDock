package com.thotapalli.visidock

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Coordinates are normalized to the upright OCR image; side is 0 front / 1 back. */
data class OcrRegion(val text:String, val left:Float, val top:Float, val right:Float, val bottom:Float, val side:Int=0)
data class PersonalExample(val cardId:String,val group:String,val field:String,val text:String,val features:FloatArray)
data class FieldPrediction(val field:String,val confidence:Float)

/** A real trainable multiclass linear model. It never generates contact values. */
object PersonalClassifier {
    val fields=listOf("name","role","company","phone","email","website","address")
    const val DIM=424
    fun features(text:String, region:OcrRegion?=null, embedding:FloatArray=FloatArray(0)):FloatArray {
        val x=FloatArray(DIM); x[0]=1f
        embedding.take(384).forEachIndexed { i,v -> x[1+i]=v }
        val words=text.trim().split(Regex("\\s+")); val letters=text.filter {it.isLetter()}
        x[385]=words.size.coerceAtMost(12)/12f; x[386]=text.length.coerceAtMost(120)/120f
        x[387]=if(letters.isEmpty()) 0f else letters.count {it.isUpperCase()}.toFloat()/letters.length
        x[388]=text.count {it.isDigit()}.toFloat()/text.length.coerceAtLeast(1)
        x[389]=if('@' in text) 1f else 0f; x[390]=if(Regex("(?i)(www\\.|https?://)").containsMatchIn(text)) 1f else 0f
        if(region!=null && listOf(region.left,region.top,region.right,region.bottom).all {it.isFinite()}) {
            x[391]=1f;x[392]=region.left.coerceIn(0f,1f);x[393]=region.top.coerceIn(0f,1f)
            x[394]=(region.right-region.left).coerceIn(0f,1f);x[395]=(region.bottom-region.top).coerceIn(0f,1f);x[396]=region.side.coerceIn(0,1).toFloat()
        }
        // Character patterns supplement contextual sentence meaning; weights, not phrase rules, learn labels.
        val normalized=CorrectionPolicy.normalize(text)
        normalized.windowed(3).forEach { s -> val h=s.hashCode(); x[397+(h and Int.MAX_VALUE)%27]+=if(h<0) -1f else 1f }
        val norm=sqrt(x.drop(397).sumOf {(it*it).toDouble()}).toFloat().coerceAtLeast(1f)
        for(i in 397 until DIM) x[i]/=norm
        return x
    }
    fun empty()=Array(fields.size) {FloatArray(DIM)}
    fun probabilities(weights:Array<FloatArray>,x:FloatArray):FloatArray {
        val logits=FloatArray(fields.size) { k -> x.indices.sumOf {(weights[k][it]*x[it]).toDouble()}.toFloat() }
        val maximum=logits.maxOrNull()?:0f
        val values=logits.map {exp((it-maximum).toDouble()).toFloat()}; val sum=values.sum()
        return values.map {it/sum}.toFloatArray()
    }
    fun predict(weights:Array<FloatArray>,x:FloatArray):FieldPrediction {
        val p=probabilities(weights,x); val best=p.indices.maxBy {p[it]};return FieldPrediction(fields[best],p[best])
    }
    fun train(examples:List<PersonalExample>, epochs:Int=100, shouldContinue:()->Boolean = {true}):Array<FloatArray> {
        val w=empty()
        repeat(epochs) { epoch ->
            if(!shouldContinue()) throw java.util.concurrent.CancellationException("Personal training interrupted")
            val rate=0.22f/(1f+epoch*0.03f)
            examples.forEach { e -> val y=fields.indexOf(e.field);if(y>=0) {
                val p=probabilities(w,e.features)
                for(k in fields.indices) for(j in 0 until DIM) w[k][j]-=rate*((p[k]-(if(k==y) 1f else 0f))*e.features[j]+0.0005f*w[k][j])
            } }
        };return w
    }
    fun loss(w:Array<FloatArray>,examples:List<PersonalExample>)=examples.map {e -> -ln(probabilities(w,e.features)[fields.indexOf(e.field)].toDouble().coerceAtLeast(1e-9))}.average()
    fun accuracy(w:Array<FloatArray>,examples:List<PersonalExample>)=examples.count {predict(w,it.features).field==it.field}.toDouble()/examples.size.coerceAtLeast(1)
    /** Hold out entire physical scans, never another person from the same scan. */
    fun split(examples:List<PersonalExample>):Pair<List<PersonalExample>,List<PersonalExample>> {
        val groups=examples.map {it.group}.distinct().sorted()
        if(groups.size<6) return examples to emptyList()
        // Hashing keeps a scan in the same partition when more corrections arrive later.
        val holdout=groups.filter { Math.floorMod(it.hashCode(), 3) == 0 }.toSet()
        return examples.filter {it.group !in holdout} to examples.filter {it.group in holdout}
    }
    fun unambiguous(examples: List<PersonalExample>): List<PersonalExample> {
        val disputed = examples.groupBy { CorrectionPolicy.normalize(it.text) }
            .filterValues { values -> values.map { it.field }.distinct().size > 1 }.keys
        return examples.filter { it.field in fields && it.features.size == DIM && it.features.all(Float::isFinite) &&
            CorrectionPolicy.normalize(it.text) !in disputed }
    }
    /** New explicit corrections can disprove an active adapter before its next scheduled training run. */
    fun contradicted(weights: Array<FloatArray>, corrections: List<PersonalExample>): Boolean = corrections.any {
        val prediction = predict(weights, it.features)
        prediction.confidence >= .85f && prediction.field != it.field
    }
    fun acceptable(old:Array<FloatArray>, candidate:Array<FloatArray>, validation:List<PersonalExample>):Boolean =
        validation.isNotEmpty() && accuracy(candidate,validation)>=accuracy(old,validation) && loss(candidate,validation)+0.005<loss(old,validation) &&
            validation.groupBy {it.field}.values.all {accuracy(candidate,it)>=accuracy(old,it)}
}
