package com.thotapalli.visidock

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual bundled MiniLM inference and real SGD, not mocked sentence vectors. */
class PersonalEmbeddingsTest {
    @Test fun semanticCompanionLearnsFromCorrectedNamesAndJobTitles() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PersonalEmbeddings(context).use {encoder ->
            val names=listOf("Mira Sen","Arjun Mehta","Sarah Thompson","Daniel Wilson","Priya Sharma","David Chen")
            val roles=listOf("Managing Director","Chief Executive Officer","Sales Manager","Software Engineer","Marketing Director","Operations Manager")
            fun example(text:String,label:String,id:String)=PersonalExample(id,id,label,text,
                PersonalClassifier.features(text,embedding=encoder.encode(text)))
            val examples=names.mapIndexed {i,text ->example(text,"name","n$i")}+roles.mapIndexed {i,text ->example(text,"role","r$i")}
            val weights=PersonalClassifier.train(examples)
            assertEquals("name",PersonalClassifier.predict(weights,example("Ananya Rao","name","held-name").features).field)
            assertEquals("role",PersonalClassifier.predict(weights,example("Business Development Manager","role","held-role").features).field)
            assertTrue(PersonalClassifier.loss(weights,examples)<PersonalClassifier.loss(PersonalClassifier.empty(),examples))
        }
    }
}
