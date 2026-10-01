package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class PersonalClassifierTest {
    private fun semantic(label:Int)=FloatArray(384).also {it[label]=1f}
    private fun example(group:String,label:String,text:String)=PersonalExample(group,group,label,text,
        PersonalClassifier.features(text,OcrRegion(text,0.1f,if(label=="name") 0.2f else 0.4f,0.8f,if(label=="name") 0.3f else 0.45f),semantic(if(label=="name") 0 else 1)))
    @Test fun changesWeightsAndGeneralizesToUnseenTextWithRelatedMeaning() {
        val training=(0..5).flatMap {i -> listOf(example("$i","name","Person $i"),example("$i","role","Director $i"))}
        val weights=PersonalClassifier.train(training)
        assertTrue(weights.any {row ->row.any {it!=0f}})
        assertEquals("role",PersonalClassifier.predict(weights,example("other","role","Chief Executive").features).field)
        assertEquals("name",PersonalClassifier.predict(weights,example("other","name","Mira Sen").features).field)
    }
    @Test fun heldOutSplitNeverMixesPeopleFromSamePhysicalCard() {
        val all=(0..8).flatMap {g ->listOf(example("scan-$g","name","Mira"),example("scan-$g","role","Engineer"))}
        val (train,validation)=PersonalClassifier.split(all)
        assertTrue(validation.isNotEmpty())
        assertTrue(train.map {it.group}.toSet().intersect(validation.map {it.group}.toSet()).isEmpty())
    }
    @Test fun fewCorrectionsDoNotPretendThereIsValidationEvidence() {
        assertTrue(PersonalClassifier.split(listOf(example("one","name","Mira"))).second.isEmpty())
        assertFalse(PersonalClassifier.acceptable(PersonalClassifier.empty(),PersonalClassifier.empty(),emptyList()))
    }
    @Test fun candidateThatForgetsOldLabelsIsRejected() {
        val examples=listOf(example("a","name","Mira"),example("b","role","Director"))
        val good=PersonalClassifier.train(examples)
        val bad=PersonalClassifier.train(examples.map {it.copy(field="company")})
        assertFalse(PersonalClassifier.acceptable(good,bad,examples))
        assertTrue(PersonalClassifier.acceptable(PersonalClassifier.empty(),good,examples))
    }
    @Test fun featuresStayFiniteForEmptyTextAndMissingCoordinates() {
        val f=PersonalClassifier.features("")
        assertEquals(PersonalClassifier.DIM,f.size)
        assertTrue(f.all {it.isFinite()})
        assertEquals(0f,f[391],0f)
    }
    @Test(expected=java.util.concurrent.CancellationException::class)
    fun cancelledTrainingNeverReturnsCandidateWeights() {
        PersonalClassifier.train(listOf(example("one","name","Mira")),shouldContinue={false})
    }
    @Test fun invalidLayoutDoesNotPoisonWeightsWithNan() {
        val values=PersonalClassifier.features("Mira",OcrRegion("Mira",Float.NaN,0f,1f,1f))
        assertTrue(values.all {it.isFinite()})
        assertEquals(0f,values[391],0f)
    }
    @Test fun newGroupsDoNotMoveOldScansBetweenTrainingAndValidation() {
        val original = (0..8).map { example("scan-$it", "name", "Person $it") }
        val first = PersonalClassifier.split(original).second.map { it.group }.toSet()
        val extended = PersonalClassifier.split(original + example("a-new-group", "role", "Director")).second.map { it.group }.toSet()
        assertEquals(first, extended.intersect(original.map { it.group }.toSet()))
    }
    @Test fun conflictingLabelsAreExcludedFromTrainingAndValidation() {
        val examples = listOf(example("a", "name", "Jordan"), example("b", "company", "Jordan"), example("c", "role", "Director"))
        assertEquals(listOf("Director"), PersonalClassifier.unambiguous(examples).map { it.text })
    }
    @Test fun confidentContradictedAdapterIsRolledBackInsteadOfContinuingBadHints() {
        val wrong = example("a", "name", "Design Director")
        val weights = PersonalClassifier.train(List(10) { wrong })
        assertTrue(PersonalClassifier.contradicted(weights, listOf(wrong.copy(field="role"))))
        assertFalse(PersonalClassifier.contradicted(PersonalClassifier.empty(), listOf(wrong.copy(field="role"))))
    }
}
