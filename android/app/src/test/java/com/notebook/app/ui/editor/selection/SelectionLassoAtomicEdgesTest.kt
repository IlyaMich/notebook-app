package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionLassoAtomicEdges.Result
import com.notebook.app.ui.editor.selection.SelectionLassoAtomicEdges.InvalidReason
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Segment
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Event
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Vertex
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoAtomicEdgesTest {
    private fun p(x: Number, y: Number) = DuPoint(x.toDouble(),y.toDouble())
    private val triangle=listOf(p(0,0),p(2,0),p(0,2))
    private val square=listOf(p(0,0),p(2,0),p(2,2),p(0,2))
    private fun <T> known(result: SelectionPredicateResult<T>) = (result as SelectionPredicateResult.Known<T>).value
    private fun prepare(input: List<DuPoint>, b: SelectionQueryBudget = SelectionQueryBudget()) =
        known(SelectionLassoSplitEvents.prepare(input,b))
    private fun build(prepared: Prepared): SelectionLassoAtomicEdges.Borrowed {
        val result=SelectionLassoAtomicEdges.build(prepared)
        assertTrue("Expected Ready, got $result",result is Result.Ready)
        return (result as Result.Ready).value
    }
    private fun limits(resource: SelectionQueryResource, maximum: Long) = SelectionQueryLimits(
        query=SelectionQueryResource.entries.associateWith { if(it==resource) maximum else it.queryCeiling })
    private fun fraction(r: SelectionExactArithmetic.Rational) = "${r.numerator}/${r.denominator}"
    private fun point(p: SelectionExactArithmetic.Point) = "${fraction(p.x)},${fraction(p.y)}"
    private fun signature(b: SelectionLassoAtomicEdges.Borrowed) = b.fragments.map {
        "${it.sourceIndex}:${it.suppliedStartIndex}>${it.suppliedEndIndex}:${it.ordinal}:" +
            "${point(it.start)}>${point(it.end)}:${fraction(it.startParameter)}>${fraction(it.endParameter)}"
    }
    private fun failure(result: Result): SelectionWorkloadFailure {
        assertTrue(result is Result.Unresolved)
        val cause=(result as Result.Unresolved).cause
        assertTrue(cause is SelectionPredicateResult.WorkloadExceeded)
        return (cause as SelectionPredicateResult.WorkloadExceeded).failure
    }

    @Test fun triangleAndSquareKeepOneDirectedOccurrencePerSource() {
        for(input in listOf(triangle,square)) prepare(input).use { prepared ->
            val b=build(prepared)
            assertEquals(input.indices.toList(),b.fragments.map { it.sourceIndex })
            assertEquals(List(input.size) { 0 },b.fragments.map { it.ordinal })
            for ((index,f) in b.fragments.withIndex()) {
                assertEquals(index,f.suppliedStartIndex)
                assertEquals((index+1)%input.size,f.suppliedEndIndex)
                assertSame(prepared.segments[index].events[0].point,f.start)
                assertSame(prepared.segments[index].events[1].point,f.end)
                assertEquals("0/1",fraction(f.startParameter))
                assertEquals("1/1",fraction(f.endParameter))
            }
        }
    }

    @Test fun bowTieSplitsBothCrossingSourcesAtExactThird() {
        prepare(listOf(p(0,0),p(1,1),p(0,1),p(1,-1))).use {
            val f=build(it).fragments
            assertEquals(6,f.size)
            for(source in listOf(0,2)) {
                val split=f.filter { e -> e.sourceIndex==source }
                assertEquals(listOf(0,1),split.map { e -> e.ordinal })
                assertEquals("1/3,1/3",point(split[0].end))
                assertSame(split[0].end,split[1].start)
                assertEquals("1/3",fraction(split[0].endParameter))
                assertEquals("1/3",fraction(split[1].startParameter))
            }
        }
    }

    @Test fun concaveSourceAssociationIsUnchanged() {
        val input=listOf(p(0,0),p(4,0),p(2,1),p(4,4),p(0,4))
        prepare(input).use {
            val f=build(it).fragments
            assertEquals(5,f.size)
            assertEquals(input.indices.toList(),f.map { e -> e.sourceIndex })
            assertEquals(input.indices.toList(),f.map { e -> e.suppliedStartIndex })
        }
    }

    @Test fun multipleCrossingsOnOneSourceHaveOrderedContiguousParameters() {
        prepare(listOf(p(0,0),p(6,0),p(1,-1),p(1,1),p(3,-1),p(3,1),p(5,-1),p(5,1))).use {
            val b=build(it)
            val f=b.fragments.filter { e -> e.sourceIndex==0 }
            assertTrue(f.size>=4)
            assertEquals(f.indices.toList(),f.map { e -> e.ordinal })
            known(b.exact {
                for(e in f) assertEquals(Sign.NEGATIVE,compare(e.startParameter,e.endParameter))
                for((a,c) in f.zipWithNext()) {
                    assertEquals(Sign.ZERO,compare(a.endParameter,c.startParameter))
                    assertEquals(Sign.ZERO,compare(a.end,c.start))
                }
            })
        }
    }

    @Test fun normalizationPreservesOriginalSuppliedIndicesAndRepeatedVertices() {
        val input=listOf(p(0,0),p(0,0),p(2,0),p(0,0),p(0,2),p(0,0))
        prepare(input).use {
            val f=build(it).fragments
            assertEquals(listOf(0,2,3,4),f.map { e -> e.suppliedStartIndex })
            assertEquals(listOf(2,3,4,0),f.map { e -> e.suppliedEndIndex })
            assertEquals(input,it.suppliedVertices)
        }
    }

    @Test fun partialOverlapsRemainSeparateAndReverseParametersIncrease() {
        prepare(listOf(p(0,0),p(4,0),p(1,0),p(3,0),p(3,2))).use {
            val f=build(it).fragments
            val first=f.filter { e -> e.sourceIndex==0 }
            assertEquals(listOf("0/1","1/4","3/4"),first.map { e -> fraction(e.startParameter) })
            assertEquals(listOf("1/4","3/4","1/1"),first.map { e -> fraction(e.endParameter) })
            val reverse=f.filter { e -> e.sourceIndex==1 }
            assertEquals(listOf("4/1,0/1","3/1,0/1"),reverse.map { e -> point(e.start) })
            assertEquals(listOf("0/1","1/3"),reverse.map { e -> fraction(e.startParameter) })
            assertEquals(listOf("1/3","1/1"),reverse.map { e -> fraction(e.endParameter) })
            assertEquals(setOf(0,2),f.filter { e ->
                point(e.start)=="1/1,0/1" && point(e.end)=="3/1,0/1"
            }.map { e -> e.sourceIndex }.toSet())
        }
    }

    @Test fun completeRetracesAndDuplicateTraversalsRetainMultiplicity() {
        prepare(listOf(p(0,0),p(2,0),p(0,0),p(2,0))).use {
            val f=build(it).fragments
            assertEquals(4,f.size)
            assertEquals(listOf(0,1,2,3),f.map { e -> e.sourceIndex })
            assertEquals(point(f[0].start),point(f[2].start))
            assertEquals(point(f[0].end),point(f[2].end))
            assertEquals(point(f[0].start),point(f[1].end))
            assertEquals(point(f[0].end),point(f[1].start))
        }
    }

    @Test fun nearbyRationalCrossingsDoNotBecomeZeroLengthFragments() {
        val n=Math.scalb(1.0,50)
        prepare(listOf(p(0,0),p(2,0),p(1,-1),p(2,n),p(1,-1),p(2,n+1))).use {
            val b=build(it)
            val tiny=b.fragments.single { e -> e.sourceIndex==0 &&
                e.start.x.numerator>e.start.x.denominator && e.end.x.numerator>e.end.x.denominator &&
                e.end.x.numerator<e.end.x.denominator.shiftLeft(1) }
            assertNotEquals(tiny.start.x,tiny.end.x)
            // Their separation is below a Double ulp at 1; exact construction has no conversion.
            known(b.exact {
                assertEquals(Sign.NEGATIVE,compare(tiny.start,tiny.end))
                assertEquals(Sign.NEGATIVE,compare(tiny.startParameter,tiny.endParameter))
            })
        }
    }

    @Test fun emptySinglePointAndCollapsedPreparationsDoNotEstablishRegionValidity() {
        for(input in listOf(emptyList(),listOf(p(0,0)),List(5) { p(0,0) })) {
            prepare(input).use { assertTrue(build(it).fragments.isEmpty()) }
        }
        prepare(listOf(p(0,0),p(1,0),p(2,0))).use {
            assertTrue(build(it).fragments.isNotEmpty())
        }
    }

    @Test fun deterministicOrderAndDefensiveImmutabilityPreservePreparedSource() {
        val signatures=mutableListOf<List<String>>()
        val work=mutableListOf<Long>()
        val input=listOf(p(0,0),p(2,2),p(0,2),p(2,0)).toMutableList()
        val original=input.toList()
        repeat(3) {
            val budget=SelectionQueryBudget()
            prepare(input,budget).use { p ->
                val source=p.segments.toList()
                val eventLists=p.segments.map { it.events.toList() }
                val b=build(p)
                signatures.add(signature(b)); work.add(budget.used(SelectionQueryResource.WORK_UNITS))
                assertEquals(source,p.segments)
                assertEquals(eventLists,p.segments.map { it.events })
                assertEquals(original,input)
                assertThrows(UnsupportedOperationException::class.java) { (b.fragments as MutableList).clear() }
                assertEquals(b.fragments.map { it.sourceIndex to it.ordinal }.sortedWith(
                    compareBy<Pair<Int,Int>> { it.first }.thenBy { it.second }),
                    b.fragments.map { it.sourceIndex to it.ordinal })
            }
        }
        assertEquals(1,signatures.distinct().size)
        assertEquals(1,work.distinct().size)
    }

    @Test fun edgeQuotaBelowAtAndAboveBoundary() {
        for((maximum,success) in listOf(2L to false,3L to true,4L to true)) {
            val budget=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EDGES,maximum))
            val prepared=prepare(triangle,budget)
            val result=SelectionLassoAtomicEdges.build(prepared)
            assertEquals(success,result is Result.Ready)
            if (!success) {
                assertEquals(SelectionQueryResource.ARRANGEMENT_EDGES,failure(result).resource)
                assertThrows(IllegalStateException::class.java) { prepared.segments }
            }
            assertEquals(minOf(maximum,3),budget.used(SelectionQueryResource.ARRANGEMENT_EDGES))
            prepared.close()
            assertEquals(0L,budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun originalBudgetIsSharedAcrossPreparationAndRepeatedBuilds() {
        val budget=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EDGES,6))
        val p=prepare(triangle,budget)
        val workBefore=budget.used(SelectionQueryResource.WORK_UNITS)
        val memoryBefore=budget.used(SelectionQueryResource.TEMPORARY_BYTES)
        val eventsBefore=budget.used(SelectionQueryResource.ARRANGEMENT_EVENTS)
        val first=build(p)
        assertEquals(3L,budget.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertTrue(budget.used(SelectionQueryResource.WORK_UNITS)>workBefore)
        assertTrue(budget.used(SelectionQueryResource.TEMPORARY_BYTES)>memoryBefore)
        build(p)
        assertEquals(6L,budget.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(eventsBefore,budget.used(SelectionQueryResource.ARRANGEMENT_EVENTS))
        failure(SelectionLassoAtomicEdges.build(p))
        assertThrows(IllegalStateException::class.java) { first.fragments }
        assertEquals(0L,budget.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun workExhaustionDuringFragmentAttemptReturnsNoPartialCollection() {
        var inject=false
        var done=false
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(inject && !done && b.used(SelectionQueryResource.ARRANGEMENT_EDGES)==2L) {
                done=true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        val p=prepare(square,b)
        inject=true
        assertEquals(SelectionQueryResource.WORK_UNITS,failure(SelectionLassoAtomicEdges.build(p)).resource)
        assertTrue(done)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java) { p.segments }
    }

    @Test fun representationExhaustionUsesOriginalQueryCeiling() {
        val probe=SelectionQueryBudget()
        val work: Long
        prepare(square,probe).use {
            build(it)
            work=probe.used(SelectionQueryResource.TEMPORARY_BYTES)
        }
        val b=SelectionQueryBudget(limits(SelectionQueryResource.TEMPORARY_BYTES,work-1))
        val p=prepare(square,b)
        assertEquals(SelectionQueryResource.TEMPORARY_BYTES,
            failure(SelectionLassoAtomicEdges.build(p)).resource)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java) { p.segments }
    }

    @Test fun cancellationDuringConstructionClosesOwnerAndAllBorrowers() {
        var cancel=false
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(cancel && b.used(SelectionQueryResource.ARRANGEMENT_EDGES)==2L) throw CancellationException()
        })
        val p=prepare(square,b)
        cancel=true
        assertThrows(CancellationException::class.java) { SelectionLassoAtomicEdges.build(p) }
        assertEquals(2L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertNull(b.failure)
        assertThrows(IllegalStateException::class.java) { p.segments }
    }

    @Test fun borrowersHaveNoSecondOwnerAndRejectUseAfterPreparedClose() {
        val b=SelectionQueryBudget()
        val p=prepare(square,b)
        val edges=build(p)
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        p.close(); p.close()
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertEquals(work,b.used(SelectionQueryResource.WORK_UNITS))
        assertThrows(IllegalStateException::class.java) { edges.fragments }
        assertThrows(IllegalStateException::class.java) { edges.exact { rational(0) } }
        assertThrows(IllegalStateException::class.java) { SelectionLassoAtomicEdges.build(p) }
    }

    /** Synthetic sole-owner fixture permits invariant fault injection without changing Prepared. */
    private fun malformed(
        b: SelectionQueryBudget,
        change: SelectionExactArithmetic.(List<Segment>) -> List<Segment>,
    ): Prepared {
        val a=SelectionExactArithmetic(b)
        val pair=known(a.attempt {
            val vertices=triangle.mapIndexed { i,p -> Vertex(i,point(p)) }
            val zero=rational(0); val one=rational(1)
            val segments=vertices.indices.map { i ->
                val start=vertices[i]; val end=vertices[(i+1)%vertices.size]
                Segment(i,start,end,listOf(Event(start.point,zero),Event(end.point,one)))
            }
            vertices to change(segments)
        })
        return Prepared(a,triangle,pair.first,pair.second)
    }

    @Test fun inconsistentParametersAndPositionsRejectExplicitlyAndDisposeOwner() {
        val cases=listOf<Pair<InvalidReason,SelectionExactArithmetic.(List<Segment>)->List<Segment>>>(
            InvalidReason.PARAMETER_ORDER to { s ->
                listOf(s[0].copy(events=listOf(s[0].events[0],s[0].events[0],s[0].events[1])))+s.drop(1)
            },
            InvalidReason.PARAMETER_ORDER to { s ->
                listOf(s[0].copy(events=listOf(s[0].events[0],
                    Event(point(rational(3,2),rational(0)),rational(3,4)),
                    Event(point(rational(1,2),rational(0)),rational(1,4)),s[0].events[1])))+s.drop(1)
            },
            InvalidReason.COINCIDENT_ENDPOINTS to { s ->
                listOf(s[0].copy(events=listOf(s[0].events[0],
                    Event(s[0].start.point,rational(1,2)),s[0].events[1])))+s.drop(1)
            },
            InvalidReason.EVENT_POSITION to { s ->
                listOf(s[0].copy(events=listOf(s[0].events[0],
                    Event(point(rational(1),rational(1)),rational(1,2)),s[0].events[1])))+s.drop(1)
            },
            InvalidReason.INCOMPLETE_EVENTS to { s -> listOf(s[0].copy(events=emptyList()))+s.drop(1) },
            InvalidReason.SOURCE_ENDPOINTS to { s ->
                listOf(s[0].copy(events=listOf(s[0].events[1],s[0].events[0])))+s.drop(1)
            },
            InvalidReason.SOURCE_ASSOCIATION to { s ->
                listOf(s[0].copy(start=s[0].start.copy(suppliedIndex=99)))+s.drop(1)
            },
            InvalidReason.SOURCE_ORDER to { s -> listOf(s[1],s[0],s[2]) },
            InvalidReason.SOURCE_ASSOCIATION to { s -> s.dropLast(1) },
        )
        for((reason,change) in cases) {
            val b=SelectionQueryBudget()
            val p=malformed(b,change)
            val r=SelectionLassoAtomicEdges.build(p)
            assertTrue("Expected $reason, got $r",r is Result.InvalidPrepared)
            assertEquals(reason,(r as Result.InvalidPrepared).reason)
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java) { p.segments }
        }
    }
}
