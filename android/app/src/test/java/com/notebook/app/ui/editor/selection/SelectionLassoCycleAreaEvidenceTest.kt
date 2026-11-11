package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionLassoCycleAreaEvidence.Result
import com.notebook.app.ui.editor.selection.SelectionLassoCycleAreaEvidence.InvalidReason
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.CancellationException

class SelectionLassoCycleAreaEvidenceTest {
    private fun p(x: Number,y: Number)=DuPoint(x.toDouble(),y.toDouble())
    private val triangle=listOf(p(0,0),p(2,0),p(0,2))
    private val square=listOf(p(0,0),p(2,0),p(2,2),p(0,2))
    private val bowTie=listOf(p(0,0),p(2,2),p(0,2),p(2,0))
    private fun <T> known(r: SelectionPredicateResult<T>): T {
        assertTrue("Expected known, got $r",r is SelectionPredicateResult.Known)
        return (r as SelectionPredicateResult.Known<T>).value
    }
    private fun prepare(input: List<DuPoint>,b: SelectionQueryBudget=SelectionQueryBudget())=
        known(SelectionLassoSplitEvents.prepare(input,b))
    private fun finish(p: Prepared,a: SelectionLassoIncidentAdjacency.Borrowed): SelectionLassoSuccessorCycles.Borrowed {
        val permutation=(SelectionLassoSuccessorPermutation.build(p,a) as SelectionLassoSuccessorPermutation.Result.Ready).value
        return (SelectionLassoSuccessorCycles.build(p,permutation) as SelectionLassoSuccessorCycles.Result.Ready).value
    }
    private fun cycles(p: Prepared): SelectionLassoSuccessorCycles.Borrowed {
        val a=(SelectionLassoAtomicEdges.build(p) as SelectionLassoAtomicEdges.Result.Ready).value
        val g=(SelectionLassoCoincidentGeometry.build(p,a) as SelectionLassoCoincidentGeometry.Result.Ready).value
        val incident=(SelectionLassoIncidentAdjacency.build(p,g) as SelectionLassoIncidentAdjacency.Result.Ready).value
        return finish(p,incident)
    }
    private fun evidence(p: Prepared,c: SelectionLassoSuccessorCycles.Borrowed): SelectionLassoCycleAreaEvidence.Borrowed {
        val r=SelectionLassoCycleAreaEvidence.build(p,c)
        assertTrue("Expected Ready, got $r",r is Result.Ready)
        return (r as Result.Ready).value
    }
    private fun fraction(r: SelectionExactArithmetic.Rational)="${r.numerator}/${r.denominator}"
    private fun values(e: SelectionLassoCycleAreaEvidence.Borrowed)=e.records.map { fraction(it.twiceSignedArea) }
    private fun limits(resource: SelectionQueryResource,n: Long)=SelectionQueryLimits(
        query=SelectionQueryResource.entries.associateWith { if(it==resource)n else it.queryCeiling })
    private fun failure(r: Result): SelectionWorkloadFailure {
        assertTrue(r is Result.Unresolved)
        val c=(r as Result.Unresolved).cause
        assertTrue(c is SelectionPredicateResult.WorkloadExceeded)
        return (c as SelectionPredicateResult.WorkloadExceeded).failure
    }
    private fun check(e: SelectionLassoCycleAreaEvidence.Borrowed) {
        assertEquals(e.walks.size,e.records.size)
        known(e.exact {
            for((i,r) in e.records.withIndex()) {
                assertEquals(i,r.walkId)
                assertEquals(e.walks[i].startHalfEdgeId,r.startHalfEdgeId)
                assertEquals(sign(r.twiceSignedArea),r.sign)
            }
        })
    }
    private fun synthetic(
        pairs: List<Pair<Int,Int>>, budget: SelectionQueryBudget=SelectionQueryBudget(),
        points: SelectionExactArithmetic.()->List<Point>,
    ): Pair<Prepared,SelectionLassoSuccessorCycles.Borrowed> {
        val p=prepare(emptyList(),budget)
        val g=known(p.exact {
            val original=points()
            val sorted=original.sortedWith { a,b -> when(compare(a,b)) {
                Sign.NEGATIVE -> -1; Sign.ZERO -> 0; Sign.POSITIVE -> 1
            } }
            val vertices=sorted.mapIndexed { i,v -> Vertex(i,v) }
            val groups=pairs.map { (a,b) ->
                val ai=sorted.indexOfFirst { it===original[a] }; val bi=sorted.indexOfFirst { it===original[b] }
                val lo=minOf(ai,bi); val hi=maxOf(ai,bi)
                EdgeGroup(lo,hi,sorted[lo],sorted[hi],emptyList())
            }.sortedWith(compareBy<EdgeGroup>{it.startVertexId}.thenBy{it.endVertexId})
            SelectionLassoCoincidentGeometry.Borrowed(p,vertices,groups)
        })
        val a=(SelectionLassoIncidentAdjacency.build(p,g) as SelectionLassoIncidentAdjacency.Result.Ready).value
        return p to finish(p,a)
    }

    @Test fun emptyInputHasEmptySuccessfulEvidence() {
        prepare(emptyList()).use {
            val e=evidence(it,cycles(it)); check(e)
            assertTrue(e.records.isEmpty()); assertTrue(e.walks.isEmpty())
        }
    }

    @Test fun singleEdgeRetracesAndCollinearWalksRetainZeroEvidence() {
        for(input in listOf(listOf(p(0,0),p(2,0)),listOf(p(0,0),p(2,0),p(0,0),p(2,0)),
            listOf(p(0,0),p(1,0),p(2,0)))) {
            prepare(input).use {
                val source=cycles(it); val e=evidence(it,source); check(e)
                assertEquals(source.walks.size,e.records.size)
                assertTrue(e.records.isNotEmpty())
                assertTrue(e.records.all { r -> fraction(r.twiceSignedArea)=="0/1" && r.sign==Sign.ZERO })
            }
        }
    }

    @Test fun triangleAndSquareHaveExactOppositeAlgebraicSigns() {
        for((input,magnitude) in listOf(triangle to 4,square to 8)) {
            prepare(input).use {
                val e=evidence(it,cycles(it)); check(e)
                assertEquals(listOf("-$magnitude/1","$magnitude/1"),values(e))
                assertEquals(listOf(Sign.NEGATIVE,Sign.POSITIVE),e.records.map { r -> r.sign })
            }
        }
    }

    @Test fun concavePolygonProducesExactEvidenceWithoutSimplicityClassification() {
        prepare(listOf(p(0,0),p(4,0),p(2,1),p(4,4),p(0,4))).use {
            val e=evidence(it,cycles(it)); check(e)
            assertEquals(setOf("-24/1","24/1"),values(e).toSet())
        }
    }

    @Test fun zeroOriginalBowTieSumDoesNotEraseNonzeroSuccessorWalkEvidence() {
        prepare(bowTie).use { p ->
            val source=cycles(p)
            val original=known(p.exact {
                var sum=rational(0)
                for(i in bowTie.indices) {
                    val a=point(bowTie[i]); val b=point(bowTie[(i+1)%bowTie.size])
                    sum=add(sum,subtract(multiply(a.x,b.y),multiply(b.x,a.y)))
                }
                sum
            })
            assertEquals("0/1",fraction(original))
            val e=evidence(p,source); check(e)
            assertEquals(listOf("-4/1","2/1","2/1"),values(e))
            assertEquals(3,e.records.size)
        }
    }

    @Test fun rationalThirdIntersectionsHaveExactRationalAreaEvidence() {
        prepare(listOf(p(0,0),p(1,1),p(0,1),p(1,-1))).use {
            val e=evidence(it,cycles(it)); check(e)
            assertEquals(listOf("-4/3","2/3","2/3"),values(e))
            assertTrue(e.records.all { r -> r.twiceSignedArea.denominator==BigInteger.valueOf(3) })
        }
    }

    @Test fun reversedWindingAndRotatedStartPreserveRecordIdsAndExactValues() {
        prepare(bowTie).use {
            val e=evidence(it,cycles(it))
            val expected=e.records
            for(input in listOf(bowTie.reversed(),bowTie.drop(1)+bowTie.take(1))) {
                prepare(input).use { p ->
                    val other=evidence(p,cycles(p)); check(other)
                    assertEquals(expected,other.records)
                }
            }
        }
    }

    @Test fun disconnectedComponentsRetainAllBoundaryWalkEvidence() {
        val (p,c)=synthetic(listOf(0 to 1,1 to 2,2 to 0,3 to 4,4 to 5,5 to 3)) {
            listOf(point(p(0,0)),point(p(2,0)),point(p(0,2)),point(p(5,0)),point(p(7,0)),point(p(5,2)))
        }
        p.use {
            val e=evidence(p,c); check(e)
            assertEquals(listOf("-4/1","4/1","-4/1","4/1"),values(e))
            assertEquals(listOf(0,1,6,7),e.records.map { it.startHalfEdgeId })
        }
    }

    @Test fun extremeBinaryScalesDistinguishExactNonzeroFromZeroWithoutDoubleArea() {
        for(exponent in listOf(-600,600)) {
            val s=Math.scalb(1.0,exponent)
            val (p,c)=synthetic(listOf(0 to 1,1 to 2,2 to 0)) {
                listOf(point(p(0,0)),point(p(s,0)),point(p(0,s)))
            }
            p.use {
                val e=evidence(p,c); check(e)
                assertEquals(listOf(Sign.NEGATIVE,Sign.POSITIVE),e.records.map { r -> r.sign })
                for(r in e.records) {
                    assertEquals(if(exponent>0)BigInteger.ONE.shiftLeft(1200) else BigInteger.ONE,
                        r.twiceSignedArea.numerator.abs())
                    assertEquals(if(exponent>0)BigInteger.ONE else BigInteger.ONE.shiftLeft(1200),
                        r.twiceSignedArea.denominator)
                }
            }
            val (line,lineCycles)=synthetic(listOf(0 to 1)) { listOf(point(p(s,s)),point(p(2*s,3*s))) }
            line.use {
                val e=evidence(line,lineCycles)
                assertEquals(listOf("0/1"),values(e))
                assertEquals(Sign.ZERO,e.records.single().sign)
            }
        }
    }

    @Test fun immutableEvidencePreservesSourceObjectsIdsAndOccurrencesAndQuotas() {
        val b=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EDGES,12))
        prepare(triangle,b).use { p ->
            val c=cycles(p); val walks=c.walks.toList()
            val edges=c.adjacency.edgeGroups.toList()
            val occurrences=edges.flatMap { it.occurrences }
            val links=c.permutation.links.toList()
            val quota=b.used(SelectionQueryResource.ARRANGEMENT_EDGES)
            val faces=b.used(SelectionQueryResource.ARRANGEMENT_FACES)
            val first=evidence(p,c); val second=evidence(p,c)
            assertEquals(first.records,second.records)
            assertSame(c,first.cycles); assertSame(c.walks,first.walks)
            assertEquals(walks,first.walks); assertEquals(links,c.permutation.links)
            for(i in walks.indices)assertSame(c.walks[i],first.walks[i])
            assertEquals(occurrences,c.adjacency.edgeGroups.flatMap { it.occurrences })
            assertEquals(quota,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
            assertEquals(faces,b.used(SelectionQueryResource.ARRANGEMENT_FACES))
            assertThrows(UnsupportedOperationException::class.java){(first.records as MutableList).clear()}
        }
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun closedOwnerInvalidatesEvidenceAndRejectsAnotherBuild() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val c=cycles(p); val e=evidence(p,c)
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        p.close(); p.close()
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertEquals(work,b.used(SelectionQueryResource.WORK_UNITS))
        assertThrows(IllegalStateException::class.java){e.records}
        assertThrows(IllegalStateException::class.java){e.walks}
        assertThrows(IllegalStateException::class.java){e.exact { rational(0) }}
        assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),SelectionLassoCycleAreaEvidence.build(p,c))
    }

    @Test fun closedForeignCycleOwnerIsReportedWithoutArithmetic() {
        val b=SelectionQueryBudget(); val primary=prepare(square,b)
        val other=prepare(square); val c=cycles(other); other.close()
        assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),SelectionLassoCycleAreaEvidence.build(primary,c))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){primary.segments}
    }

    @Test fun foreignCyclesNeverExecuteArmedCancellationOrChangeForeignState() {
        for(input in listOf(emptyList(),square)) for(sticky in listOf(false,true)) {
            val primaryBudget=SelectionQueryBudget(); val primary=prepare(input,primaryBudget)
            var armed=false; var callbacks=0
            val b=SelectionQueryBudget(cancellationCheckpoint={callbacks++;if(armed)throw CancellationException()})
            val foreign=prepare(input,b)
            try {
                val c=cycles(foreign)
                if(sticky)assertNotNull(b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS)+1))
                val counters=SelectionQueryResource.entries.associateWith { b.used(it) }
                val failure=b.failure; val calls=callbacks; val retained=c.walks
                armed=true
                assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),SelectionLassoCycleAreaEvidence.build(primary,c))
                assertEquals(calls,callbacks); assertSame(failure,b.failure); assertSame(retained,c.walks)
                assertEquals(counters,SelectionQueryResource.entries.associateWith { b.used(it) })
                assertThrows(IllegalStateException::class.java){primary.segments}
                assertEquals(0L,primaryBudget.used(SelectionQueryResource.TEMPORARY_BYTES))
                armed=false
                if(!sticky)assertEquals(7,known(foreign.exact { 7 }))
            } finally { primary.close(); foreign.close() }
        }
    }

    @Test fun foreignNestedPermutationAndAdjacencyAreRejectedBeforeArithmetic() {
        for(nestedAdjacency in listOf(false,true)) {
            val primaryBudget=SelectionQueryBudget(); val primary=prepare(square,primaryBudget)
            var armed=false; var callbacks=0
            val b=SelectionQueryBudget(cancellationCheckpoint={callbacks++;if(armed)throw CancellationException()})
            val foreign=prepare(square,b)
            try {
                val c=cycles(foreign)
                val permutation=if(nestedAdjacency)
                    SelectionLassoSuccessorPermutation.Borrowed(primary,c.adjacency,c.permutation.links) else c.permutation
                val forged=SelectionLassoSuccessorCycles.Borrowed(primary,permutation,c.walks,c.halfEdgeToWalk)
                val counters=SelectionQueryResource.entries.associateWith { b.used(it) }; val calls=callbacks
                armed=true
                assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),SelectionLassoCycleAreaEvidence.build(primary,forged))
                assertEquals(calls,callbacks)
                assertEquals(counters,SelectionQueryResource.entries.associateWith { b.used(it) })
                assertEquals(2,c.walks.size)
                assertEquals(0L,primaryBudget.used(SelectionQueryResource.TEMPORARY_BYTES))
            } finally { primary.close(); foreign.close() }
        }
    }

    @Test fun malformedWalksMembershipAndSuccessorsRejectBeforeAreaAccumulation() {
        for(kind in 0..9) {
            val b=SelectionQueryBudget(); val p=prepare(square,b); val c=cycles(p)
            var walks=c.walks
            var membership=c.halfEdgeToWalk
            var permutation=c.permutation
            val first=walks[0]
            when(kind) {
                0 -> walks=listOf(first.copy(id=99))+walks.drop(1)
                1 -> walks=listOf(first.copy(startHalfEdgeId=99))+walks.drop(1)
                2 -> walks=listOf(first.copy(halfEdgeIds=first.halfEdgeIds.dropLast(1)))+walks.drop(1)
                3 -> walks=listOf(first.copy(halfEdgeIds=listOf(first.halfEdgeIds[0])+first.halfEdgeIds))+walks.drop(1)
                4 -> walks=listOf(first.copy(halfEdgeIds=listOf(99)+first.halfEdgeIds.drop(1)))+walks.drop(1)
                5 -> membership=List(membership.size){0}
                6 -> {
                    val rotated=first.halfEdgeIds.drop(1)+first.halfEdgeIds.take(1)
                    walks=listOf(first.copy(startHalfEdgeId=rotated.first(),halfEdgeIds=rotated))+walks.drop(1)
                }
                7 -> walks=listOf(first.copy(halfEdgeIds=emptyList()))+walks.drop(1)
                8 -> permutation=SelectionLassoSuccessorPermutation.Borrowed(p,c.adjacency,
                    listOf(c.permutation.links[0].copy(next=99))+c.permutation.links.drop(1))
                else -> membership=membership.dropLast(1)
            }
            val forged=SelectionLassoSuccessorCycles.Borrowed(p,permutation,walks,membership)
            val r=SelectionLassoCycleAreaEvidence.build(p,forged)
            assertTrue("Malformed case $kind: $r",r is Result.InvalidInput)
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java){c.walks}
        }
    }

    @Test fun invalidGraphEndpointRejectsWithoutIndexError() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val c=cycles(p); val a=c.adjacency
        val grouped=SelectionLassoCoincidentGeometry.Borrowed(p,a.vertices,a.edgeGroups)
        val bad=SelectionLassoIncidentAdjacency.Borrowed(p,grouped,
            listOf(a.halfEdges[0].copy(destination=99))+a.halfEdges.drop(1),a.incidents)
        val permutation=SelectionLassoSuccessorPermutation.Borrowed(p,bad,c.permutation.links)
        val forged=SelectionLassoSuccessorCycles.Borrowed(p,permutation,c.walks,c.halfEdgeToWalk)
        assertEquals(Result.InvalidInput(InvalidReason.INVALID_GRAPH),SelectionLassoCycleAreaEvidence.build(p,forged))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun foreignExactCoordinateContextRejectsWithoutForeignExecution() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val c=cycles(p); val a=c.adjacency
        val other=prepare(emptyList())
        other.use {
            val foreign=known(other.exact { point(rational(0),rational(0)) })
            val vertices=listOf(a.vertices[0].copy(point=foreign))+a.vertices.drop(1)
            val grouped=SelectionLassoCoincidentGeometry.Borrowed(p,vertices,a.edgeGroups)
            val bad=SelectionLassoIncidentAdjacency.Borrowed(p,grouped,a.halfEdges,a.incidents)
            val permutation=SelectionLassoSuccessorPermutation.Borrowed(p,bad,c.permutation.links)
            val forged=SelectionLassoSuccessorCycles.Borrowed(p,permutation,c.walks,c.halfEdgeToWalk)
            assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),SelectionLassoCycleAreaEvidence.build(p,forged))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertTrue(other.segments.isEmpty())
        }
    }

    @Test fun exactOperandExhaustionDoesNotRoundAnAreaOrReturnEmptySuccess() {
        val b=SelectionQueryBudget()
        val (p,c)=synthetic(listOf(0 to 1),b) {
            val one=rational(1); val step=rational(1,1L shl 60)
            var tiny=one
            repeat(35){tiny=multiply(tiny,step)}
            listOf(point(add(one,tiny),add(one,multiply(rational(3),tiny))),
                point(add(one,multiply(rational(2),tiny)),add(one,multiply(rational(5),tiny))))
        }
        val r=SelectionLassoCycleAreaEvidence.build(p,c)
        assertEquals(SelectionBudgetConstraint.EXACT_BITS,failure(r).constraint)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    private fun inAccumulationArithmetic()=Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoCycleAreaEvidence") && it.methodName=="accumulate"
    } && Thread.currentThread().stackTrace.any {
        it.className.endsWith("SelectionExactArithmetic") && it.methodName=="multiply"
    }

    @Test fun workExhaustionDuringAccumulationPublishesNoPartialEvidence() {
        var active=false; var injected=false
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(active && !injected && inAccumulationArithmetic()) {
                injected=true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        val p=prepare(square,b); val c=cycles(p); active=true
        assertEquals(SelectionQueryResource.WORK_UNITS,failure(SelectionLassoCycleAreaEvidence.build(p,c)).resource)
        assertTrue(injected)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){c.walks}
    }

    @Test fun cancellationDuringAccumulationPropagatesAndDisposesOwner() {
        var active=false
        val b=SelectionQueryBudget(cancellationCheckpoint={if(active && inAccumulationArithmetic())throw CancellationException()})
        val p=prepare(square,b); val c=cycles(p); active=true
        assertThrows(CancellationException::class.java){SelectionLassoCycleAreaEvidence.build(p,c)}
        assertNull(b.failure); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){c.walks}
    }

    @Test fun representationExhaustionReleasesAllRetainedEvidence() {
        val probe=SelectionQueryBudget()
        val retained: Long
        prepare(square,probe).use { evidence(it,cycles(it)); retained=probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val b=SelectionQueryBudget(limits(SelectionQueryResource.TEMPORARY_BYTES,retained-1))
        val p=prepare(square,b); val c=cycles(p)
        assertEquals(SelectionQueryResource.TEMPORARY_BYTES,failure(SelectionLassoCycleAreaEvidence.build(p,c)).resource)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun laterFailureInvalidatesEarlierAreaBorrowerWithoutResettingWork() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val c=cycles(p); val e=evidence(p,c)
        val remaining=b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS)
        val sticky=b.charge(SelectionQueryResource.WORK_UNITS,remaining+1)
        assertNotNull(sticky)
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        assertSame(sticky,failure(SelectionLassoCycleAreaEvidence.build(p,c)))
        assertEquals(work,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){e.records}
    }
}
