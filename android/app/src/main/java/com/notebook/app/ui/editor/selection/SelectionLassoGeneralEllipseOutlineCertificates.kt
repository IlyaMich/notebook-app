package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.ui.editor.selection.SelectionLassoFilledRegionAdmission as Filled
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign

/**
 * Bounded, exact certificates for the true Euclidean-radius stroke around a NONCIRCULAR
 * ellipse centerline. The stroke is the distance-r neighborhood of the analytic ellipse,
 * not the difference of two concentric ellipses and not a polygon approximation.
 *
 * Four monotone rational quarter-circle parameterizations enclose the entire local ellipse
 * centerline with exact rectangles. For every authenticated odd fill edge, either an exact
 * rational centerline sample lies strictly within the stroke radius of the edge (proven
 * overlap), or each enclosing arc box is strictly farther than the stroke radius (proven
 * separation). Unresolved interval cells include tangency and near-threshold cases.
 *
 * If every active fill boundary is certified separated, one exact ellipse centerline point
 * classifies the connected stroke region by parity. If that point is odd, positive overlap
 * is already certified regardless of unresolved boundary intervals. Otherwise any remaining
 * uncertainty returns typed Indeterminate; no rounded, sampled or tolerance-based MISS/HIT.
 *
 * Exact axial tangencies receive a separate half-plane certificate: if the entire filled
 * lasso lies beyond one extreme support line of the local stroke, and an active fill edge
 * contains the unique extremal stroke point, only boundary contact is possible. This does
 * not guess tangency for an arbitrary oblique line or an unresolved subdivision cell.
 * A subsequent narrow certificate also recognizes selected rational, NONAXIAL supporting
 * normals. Each chosen normal is proved to be a unit vector, parallel to the exact ellipse
 * normal at an exact rational centerline point. Only source-wide half-plane separation and
 * exact active-edge containment of the resulting stroke support point authorize contact.
 * Further derived-edge normal candidates can be certified when two square roots have
 * independently verified exact dyadic representations. Uncertified cases remain unresolved;
 * there is no polygonal outline approximation.
 *
 * Budgeted subdivision is intentionally bounded. Pure geometry only; no page clipping,
 * renderer certification, visible-element HIT, or finite DU tolerance policy.
 */
internal object SelectionLassoGeneralEllipseOutlineCertificates {
    enum class Relation { INTERIOR_OVERLAP, BOUNDARY_ONLY_CONTACT, DISJOINT }
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, UNAUTHENTICATED_REGION, SOURCE_ASSOCIATION,
        INVALID_ELLIPSE, INVALID_AFFINE, FACE_MAPPING, GROUP_ASSOCIATION, COUNT_OVERFLOW,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }

    open class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: Filled.Borrowed,
        private val sourceEllipse: SelectionPaintGeometry.Primitive.Ellipse,
        private val transform: CanonicalElementAffine,
        private val evidence: Relation,
    ) {
        val region: Filled.Borrowed get() { owner.segments; return source }
        val ellipse: SelectionPaintGeometry.Primitive.Ellipse get() { owner.segments; return sourceEllipse }
        val affine: CanonicalElementAffine get() { owner.segments; return transform }
        val relation: Relation get() { owner.segments; return evidence }
        internal fun isOwnedBy(prepared: Prepared) = owner === prepared
        internal fun isAuthenticatedFor(prepared: Prepared) = owner === prepared && this is Completed
    }
    private class Completed(
        owner: Prepared, source: Filled.Borrowed, ellipse: SelectionPaintGeometry.Primitive.Ellipse,
        transform: CanonicalElementAffine, evidence: Relation,
    ) : Borrowed(owner, source, ellipse, transform, evidence)

    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(ok: Boolean, reason: InvalidReason) { if (!ok) throw Invalid(reason) }
    private fun <T> known(outcome: SelectionPredicateResult<T>): T = when (outcome) {
        is SelectionPredicateResult.Known -> outcome.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(outcome)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(outcome)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.INVALID_ELLIPSE)
    }

    fun intersect(
        prepared: Prepared, region: Filled.Borrowed,
        ellipse: SelectionPaintGeometry.Primitive.Ellipse, affine: CanonicalElementAffine,
    ): Result {
        var success = false
        try {
            val parity: Parity.Borrowed
            val faces: Faces.Borrowed
            val approach: SelectionLassoContactApproachEvidence.Borrowed
            val components: SelectionLassoGraphComponents.Borrowed
            val cycles: SelectionLassoSuccessorCycles.Borrowed
            val adjacency: SelectionLassoIncidentAdjacency.Borrowed
            try {
                prepared.segments; region.oddFaceIds
                parity = region.parity
                parity.faceParities; parity.edgeGroupToggles; parity.oddFaceIds
                faces = parity.faces
                faces.faces; faces.counts; faces.walkToFace
                approach = faces.approach
                components = approach.components
                cycles = components.cycles
                adjacency = cycles.adjacency
                adjacency.edgeGroups; adjacency.vertices; adjacency.halfEdges
                cycles.halfEdgeToWalk
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            val rays = approach.contacts
            val admission = components.admission
            verify(region.isOwnedBy(prepared) && parity.isOwnedBy(prepared) &&
                faces.isOwnedBy(prepared) && approach.isOwnedBy(prepared) &&
                rays.isOwnedBy(prepared) && rays.exterior.isOwnedBy(prepared) &&
                components.isOwnedBy(prepared) && admission.isOwnedBy(prepared) &&
                cycles.isOwnedBy(prepared) && cycles.permutation.isOwnedBy(prepared) &&
                adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            verify(region.isAuthenticatedFor(prepared) && parity.isAuthenticatedFor(prepared) &&
                faces.isAuthenticatedFor(prepared) && approach.isAuthenticatedFor(prepared) &&
                rays.isAuthenticatedFor(prepared) && admission.isAuthenticatedFor(prepared),
                InvalidReason.UNAUTHENTICATED_REGION)
            verify(region.parity === parity && parity.faces === faces &&
                approach.components === components && rays.components === components &&
                admission.cycles === cycles && cycles.adjacency === adjacency,
                InvalidReason.SOURCE_ASSOCIATION)
            val frame = ellipse.frame
            verify(frame.x.isFinite() && frame.y.isFinite() && frame.width.isFinite() &&
                frame.height.isFinite() && frame.width > 0.0 && frame.height > 0.0 &&
                ellipse.outlineRadius.isFinite() && ellipse.outlineRadius > 0.0,
                InvalidReason.INVALID_ELLIPSE)
            val coefficients = affine.coefficients()
            verify(coefficients.size == 6 && coefficients.all(Double::isFinite),
                InvalidReason.INVALID_AFFINE)
            // The circular special case already has a complete exact classifier.
            // This checkpoint provides partial certificates for noncircular ellipses only.
            verify(frame.width != frame.height, InvalidReason.INVALID_ELLIPSE)
            val outcome = known(prepared.exact {
                classify(prepared, region, parity, faces, cycles, adjacency, ellipse, affine, coefficients)
            })
            success = true
            return Result.Ready(outcome)
        } catch (invalid: Invalid) {
            return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) {
            return Result.Unresolved(unresolved.outcome)
        } catch (_: ArithmeticException) {
            return Result.InvalidInput(InvalidReason.COUNT_OVERFLOW)
        } finally { if (!success) prepared.close() }
    }

    private fun SelectionExactArithmetic.classify(
        owner: Prepared, region: Filled.Borrowed, parity: Parity.Borrowed,
        faces: Faces.Borrowed, cycles: SelectionLassoSuccessorCycles.Borrowed,
        adjacency: SelectionLassoIncidentAdjacency.Borrowed,
        ellipse: SelectionPaintGeometry.Primitive.Ellipse, affine: CanonicalElementAffine, c: List<Double>,
    ): Borrowed {
        chargeIncidentLassoWork()
        val groups = adjacency.edgeGroups
        val vertices = adjacency.vertices
        val halfEdges = adjacency.halfEdges
        val toWalk = cycles.halfEdgeToWalk
        val toFace = faces.walkToFace
        val faceRecords = faces.faces
        val faceParity = parity.faceParities
        val toggles = parity.edgeGroupToggles
        val counts = faces.counts
        verify(groups.size == counts.edgeGroupCount && vertices.size == counts.vertexCount &&
            faces.faceCount == faceRecords.size && faces.unboundedFaceId == 0 &&
            faceRecords.size == faceParity.size && toFace.size == counts.walkCount &&
            toWalk.size.toLong() == groups.size.toLong() * 2L && halfEdges.size == toWalk.size &&
            parity.verifiedConstraintCount == groups.size && toggles.size == groups.size &&
            region.oddFaceIds.isNotEmpty() && region.oddFaceIds == parity.oddFaceIds,
            InvalidReason.FACE_MAPPING)
        var nextOdd = 0
        for (i in faceParity.indices) {
            chargeIncidentLassoWork(2)
            val f = faceRecords[i]; val p = faceParity[i]
            verify(f.id == i && f.isUnbounded == (i == 0) && p.faceId == i &&
                (i != 0 || !p.isOdd), InvalidReason.FACE_MAPPING)
            if (p.isOdd) {
                verify(nextOdd < region.oddFaceIds.size && region.oddFaceIds[nextOdd++] == i,
                    InvalidReason.FACE_MAPPING)
            }
        }
        verify(nextOdd == region.oddFaceIds.size, InvalidReason.FACE_MAPPING)

        // Exact local center/radii; no rounded Double additions or affine mapping.
        retainRecord(1024)
        val frame = ellipse.frame
        val x0 = fromDouble(frame.x); val y0 = fromDouble(frame.y)
        val half = rational(1, 2)
        val rx = multiply(fromDouble(frame.width), half)
        val ry = multiply(fromDouble(frame.height), half)
        val center = point(add(x0, rx), add(y0, ry))
        val stroke = fromDouble(ellipse.outlineRadius)
        val radiusSquared = multiply(stroke, stroke)
        val zero = rational(0); val one = rational(1)
        // Unique local support points of the true distance-r stroke, in axis order:
        // right, left, bottom, top. Their coordinates are exact rational sums.
        val right = add(add(center.x, rx), stroke)
        val left = subtract(subtract(center.x, rx), stroke)
        val bottom = add(add(center.y, ry), stroke)
        val top = subtract(subtract(center.y, ry), stroke)
        retainRecord(384)
        val extreme = listOf(point(right, center.y), point(left, center.y),
            point(center.x, bottom), point(center.x, top))
        val allBeyond = BooleanArray(4) { true }
        val touchesExtreme = BooleanArray(4)
        fun beyond(p: Point, axis: Int): Boolean = when (axis) {
            0 -> compare(p.x, right) != Sign.NEGATIVE
            1 -> compare(p.x, left) != Sign.POSITIVE
            2 -> compare(p.y, bottom) != Sign.NEGATIVE
            3 -> compare(p.y, top) != Sign.POSITIVE
            else -> error("Axis invariant")
        }
        val aa = fromDouble(c[0]); val bb = fromDouble(c[1]); val cc = fromDouble(c[2])
        val dd = fromDouble(c[3]); val tx = fromDouble(c[4]); val ty = fromDouble(c[5])
        val determinant = subtract(multiply(aa, dd), multiply(bb, cc))
        verify(sign(determinant) != Sign.ZERO, InvalidReason.INVALID_AFFINE)
        fun local(pagePoint: Point): Point {
            chargeIncidentLassoWork(5)
            val x = subtract(pagePoint.x, tx); val y = subtract(pagePoint.y, ty)
            return point(divide(subtract(multiply(dd, x), multiply(cc, y)), determinant),
                divide(subtract(multiply(aa, y), multiply(bb, x)), determinant))
        }
        // Preserve exact local vertices for candidate lines derived from the authenticated
        // source. This is retained and charged in the one shared arithmetic arena.
        retainRecord(192L + vertices.size.toLong() * 16L)
        val localVertices = ArrayList<Point>(vertices.size)
        // A whole source made of straight segments is contained in the convex hull of
        // its vertices. If ALL vertices lie beyond an extreme stroke support line,
        // there cannot be any interior overlap. This prepass avoids expensive arc
        // subdivision in precisely the hard equality cases it can certify.
        for (i in vertices.indices) {
            chargeIncidentLassoWork(4)
            val vertex = vertices[i]
            verify(vertex.id == i && vertex.point.x.context === this &&
                vertex.point.y.context === this, InvalidReason.GROUP_ASSOCIATION)
            val q = local(vertex.point)
            localVertices.add(q)
            for (axis in 0 until 4) {
                chargeIncidentLassoWork()
                if (allBeyond[axis] && !beyond(q, axis)) allBeyond[axis] = false
            }
        }
        val hasAxialSupport = allBeyond.any { it }
        val centerlineWitness = point(add(center.x, rx), center.y)
        val witness = point(add(add(multiply(aa, centerlineWitness.x), multiply(cc, centerlineWitness.y)), tx),
            add(add(multiply(bb, centerlineWitness.x), multiply(dd, centerlineWitness.y)), ty))
        var witnessOdd = false
        var observed = 0L
        var previousStart = -1; var previousEnd = -1
        for (i in groups.indices) {
            chargeIncidentLassoWork(9)
            val group = groups[i]
            verify(group.startVertexId in vertices.indices && group.endVertexId in vertices.indices &&
                group.startVertexId < group.endVertexId && group.occurrences.isNotEmpty() &&
                (group.startVertexId > previousStart ||
                    group.startVertexId == previousStart && group.endVertexId > previousEnd),
                InvalidReason.GROUP_ASSOCIATION)
            previousStart = group.startVertexId; previousEnd = group.endVertexId
            val va = vertices[group.startVertexId]; val vb = vertices[group.endVertexId]
            verify(va.id == group.startVertexId && vb.id == group.endVertexId &&
                va.point.x.context === this && va.point.y.context === this &&
                vb.point.x.context === this && vb.point.y.context === this &&
                group.start.x.context === this && group.start.y.context === this &&
                group.end.x.context === this && group.end.y.context === this &&
                compare(group.start, va.point) == Sign.ZERO &&
                compare(group.end, vb.point) == Sign.ZERO &&
                compare(va.point, vb.point) == Sign.NEGATIVE &&
                toggles[i] == (group.occurrences.size % 2 == 1),
                InvalidReason.GROUP_ASSOCIATION)
            observed = Math.addExact(observed, group.occurrences.size.toLong())
            val forward = halfEdges[2 * i]; val backward = halfEdges[2 * i + 1]
            verify(forward.id == 2 * i && backward.id == 2 * i + 1 &&
                forward.edgeGroupIndex == i && backward.edgeGroupIndex == i &&
                forward.twin == 2 * i + 1 && backward.twin == 2 * i &&
                forward.origin == group.startVertexId && forward.destination == group.endVertexId &&
                backward.origin == group.endVertexId && backward.destination == group.startVertexId &&
                forward.canonicalForward && !backward.canonicalForward,
                InvalidReason.GROUP_ASSOCIATION)
            val wa = toWalk[2 * i]; val wb = toWalk[2 * i + 1]
            verify(wa in toFace.indices && wb in toFace.indices, InvalidReason.FACE_MAPPING)
            val fa = toFace[wa]; val fb = toFace[wb]
            verify(fa in faceParity.indices && fb in faceParity.indices &&
                (faceParity[fa].isOdd xor faceParity[fb].isOdd) == toggles[i],
                InvalidReason.FACE_MAPPING)
            if (!toggles[i]) continue
            // If the global support-line prepass succeeded, only the exact contact
            // point can meet the ellipse stroke. No arc subdivision is needed.
            if (hasAxialSupport) {
                val localA = local(group.start)
                val localB = local(group.end)
                for (axis in 0 until 4) {
                    chargeIncidentLassoWork(2)
                    if (allBeyond[axis] && !touchesExtreme[axis] &&
                        onSegment(extreme[axis], localA, localB)) touchesExtreme[axis] = true
                }
            }
            // Do not subdivide here. This first pass validates EVERY source association
            // and establishes point parity before trying any expensive curve cells.
            // Half-open exact ray from the centerline point; on-boundary is already
            // covered by strict-distance overlap because stroke radius is positive.
            val ay = compare(group.start.y, witness.y)
            val by = compare(group.end.y, witness.y)
            val crossing = (ay != Sign.POSITIVE && by == Sign.POSITIVE) ||
                (by != Sign.POSITIVE && ay == Sign.POSITIVE)
            if (crossing) {
                val side = sign(cross(vector(group.end, group.start),
                    vector(witness, group.start)))
                val upward = compare(group.start.y, group.end.y) == Sign.NEGATIVE
                if ((upward && side == Sign.POSITIVE) || (!upward && side == Sign.NEGATIVE))
                    witnessOdd = !witnessOdd
            }
        }
        verify(observed == parity.verifiedOccurrenceCount, InvalidReason.SOURCE_ASSOCIATION)
        // The odd filled set is contained in the convex hull of its source segments.
        // Its open interior therefore cannot enter the opposite side of a support line.
        // At an ellipse stroke's axial extreme that support line touches the stroke at
        // precisely one point. A point on an active fill edge certifies pure contact.
        val axialContact = (0 until 4).any { allBeyond[it] && touchesExtreme[it] }
        // Attempt the cheap, exact global oblique support certificate AFTER checking
        // all source associations, but BEFORE costly rational arc subdivision. At exact
        // tangency subdivision cannot prove strict separation and otherwise exhausts the
        // owner's temporary-byte budget before this proof can run. Do not enlarge quotas.
        val oblique = if (!hasAxialSupport && !witnessOdd) certifyRationalObliqueSupport(
            vertices, groups, toggles, ::local,
            center, rx, ry, stroke, zero, one) else null
        // Generalize beyond the finite rational-normal table: candidate directions come
        // from actual active source edges. All support equations and contact checks are
        // verified exactly; floating-point is ONLY an untrusted square-root candidate.
        val sourceNormal = if (!hasAxialSupport && !witnessOdd && oblique == null)
            certifySourceNormalSupport(groups, toggles, localVertices,
                center, rx, ry, stroke, zero) else null

        // Only unresolved geometry needs the bounded arc search. All graph associations
        // were checked in the first pass, so this second pass can stop on an exact
        // strict-overlap witness without weakening mutation or source-ownership checks.
        var boundaryOverlap = false
        var certifiedAllSeparated = true
        if (!hasAxialSupport && !witnessOdd && oblique == null && sourceNormal == null) {
            for (i in groups.indices) {
                if (!toggles[i]) continue
                chargeIncidentLassoWork(2)
                val group = groups[i]
                when (certifyEdge(local(group.start), local(group.end), center,
                    rx, ry, radiusSquared, zero, one)) {
                    EdgeCertificate.OVERLAP -> { boundaryOverlap = true; break }
                    EdgeCertificate.SEPARATED -> Unit
                    EdgeCertificate.UNRESOLVED -> certifiedAllSeparated = false
                }
            }
        }
        val relation = when {
            hasAxialSupport -> if (axialContact) Relation.BOUNDARY_ONLY_CONTACT else Relation.DISJOINT
            witnessOdd || boundaryOverlap -> Relation.INTERIOR_OVERLAP
            oblique != null -> oblique
            sourceNormal != null -> sourceNormal
            certifiedAllSeparated -> Relation.DISJOINT
            else -> throw Unresolved(SelectionPredicateResult.Indeterminate(
                SelectionNumericalReason.UNCERTIFIED_COMPARISON))
        }
        retainRecord(256)
        chargeIncidentLassoWork() // final cancellation and resource checkpoint
        return Completed(owner, region, ellipse, affine, relation)
    }

    /**
     * Bounded exact support-point witnesses for selected rational oblique normals.
     * For a point E on the ellipse and unit outward normal u, E+r*u is the UNIQUE
     * stroke extreme in direction u (support of an ellipse Minkowski-summed with a disk).
     * An authenticated lasso contained in dot(u, X-(E+r*u)) >= 0 can meet the stroke
     * only at that point. Active fill-edge containment proves boundary-only contact;
     * otherwise the filled region and stroke are disjoint.
     *
     * Rational quarter-ellipse samples and Pythagorean unit normals are finite proof
     * CANDIDATES, never a sampled geometric approximation or authority for a miss.
     */
    private fun SelectionExactArithmetic.certifyRationalObliqueSupport(
        vertices: List<SelectionLassoCoincidentGeometry.Vertex>,
        groups: List<SelectionLassoCoincidentGeometry.EdgeGroup>,
        toggles: List<Boolean>,
        local: (Point) -> Point,
        center: Point, rx: Rational, ry: Rational, stroke: Rational,
        zero: Rational, one: Rational,
    ): Relation? {
        chargeIncidentLassoWork()
        verify(groups.size == toggles.size && vertices.isNotEmpty(), InvalidReason.GROUP_ASSOCIATION)
        retainRecord(512)
        val two = rational(2)
        val rxSquared = multiply(rx, rx); val rySquared = multiply(ry, ry)
        // All these binary-free ratios are exact rationals; every base direction has norm 1.
        // The finite table is a certification opportunity, NOT an exhaustive angular sweep.
        val bases = listOf(
            point(rational(3, 5), rational(4, 5)),
            point(rational(4, 5), rational(3, 5)),
            point(rational(5, 13), rational(12, 13)),
            point(rational(12, 13), rational(5, 13)),
        )
        val parameters = listOf(rational(1, 5), rational(1, 4),
            rational(1, 3), rational(1, 2), rational(3, 5))
        for (quadrant in 0 until 4) {
            chargeIncidentLassoWork()
            val negativeX = quadrant == 1 || quadrant == 2
            val negativeY = quadrant == 2 || quadrant == 3
            val units = bases.map { base ->
                chargeIncidentLassoWork(2)
                point(if (negativeX) subtract(zero, base.x) else base.x,
                    if (negativeY) subtract(zero, base.y) else base.y)
            }
            for (t in parameters) {
                chargeIncidentLassoWork(2)
                val e = ellipsePoint(quadrant, t, center, rx, ry, one, two)
                // (E-C)/radii^2 is exactly normal to the implicit ellipse.
                val normal = point(divide(subtract(e.x, center.x), rxSquared),
                    divide(subtract(e.y, center.y), rySquared))
                for (u in units) {
                    chargeIncidentLassoWork(3)
                    // Parallel, positively aligned, and unit length; all exact.
                    if (sign(cross(normal, u)) != Sign.ZERO ||
                        sign(dot(normal, u)) != Sign.POSITIVE) continue
                    verify(compare(squared(u), one) == Sign.ZERO, InvalidReason.GROUP_ASSOCIATION)
                    val support = point(add(e.x, multiply(stroke, u.x)),
                        add(e.y, multiply(stroke, u.y)))
                    var beyond = true
                    for (v in vertices) {
                        chargeIncidentLassoWork(2)
                        if (sign(dot(vector(local(v.point), support), u)) == Sign.NEGATIVE) {
                            beyond = false
                            break
                        }
                    }
                    if (!beyond) continue
                    var activeContact = false
                    for (i in groups.indices) {
                        chargeIncidentLassoWork()
                        if (toggles[i] && onSegment(support,
                            local(groups[i].start), local(groups[i].end))) {
                            activeContact = true
                            break
                        }
                    }
                    return if (activeContact) Relation.BOUNDARY_ONLY_CONTACT else Relation.DISJOINT
                }
            }
        }
        return null
    }

    /**
     * A square-root guess is NEVER a predicate: all proposed dyadic roots must square
     * to the original exact rational. This can miss non-dyadic rational roots, in which
     * case this optional certificate returns null and the existing search is used.
     * No floating calculation can authorize INTERIOR_OVERLAP or DISJOINT.
     */
    private fun SelectionExactArithmetic.verifiedDyadicRootOrNull(
        square: Rational,
    ): Rational? {
        chargeIncidentLassoWork(3)
        if (sign(square) != Sign.POSITIVE) return null
        val approximate = square.numerator.toDouble() / square.denominator.toDouble()
        if (!approximate.isFinite() || approximate <= 0.0) return null
        val candidate = kotlin.math.sqrt(approximate)
        if (!candidate.isFinite() || candidate <= 0.0) return null
        val exact = fromDouble(candidate)
        return if (compare(multiply(exact, exact), square) == Sign.ZERO) exact else null
    }

    /**
     * An active source edge supplies its own possible stroke support normal, rather than
     * requiring membership in a table of angles. First prove that the entire source lies
     * in one closed half-plane of that edge. Only then attempt the exact ellipse + disk
     * support equation in that direction:
     *   E = C + (rx^2 * nx, ry^2 * ny) / sqrt((rx*nx)^2 + (ry*ny)^2)
     *   P = E + r * n / sqrt(nx^2 + ny^2).
     * The two square roots are admitted ONLY when independently verified exact.
     * All source vertices lying beyond n dot (X-P) >= 0 proves no interior overlap;
     * active boundary containment of P distinguishes contact from disjointness.
     * Bounded candidate count preserves budget for the fallback subdivision.
     */
    private fun SelectionExactArithmetic.certifySourceNormalSupport(
        groups: List<SelectionLassoCoincidentGeometry.EdgeGroup>,
        toggles: List<Boolean>, vertices: List<Point>,
        center: Point, rx: Rational, ry: Rational, stroke: Rational,
        zero: Rational,
    ): Relation? {
        chargeIncidentLassoWork()
        verify(vertices.isNotEmpty() && toggles.size == groups.size,
            InvalidReason.GROUP_ASSOCIATION)
        val rxSquared = multiply(rx, rx)
        val rySquared = multiply(ry, ry)
        var considered = 0
        for (i in groups.indices) {
            chargeIncidentLassoWork()
            if (!toggles[i]) continue
            if (++considered > MAX_SOURCE_NORMAL_CANDIDATES) break
            val group = groups[i]
            val a = vertices[group.startVertexId]
            val b = vertices[group.endVertexId]
            val delta = vector(b, a)
            val candidate = point(subtract(zero, delta.y), delta.x)
            for (direction in 0 until 2) {
                chargeIncidentLassoWork(3)
                val n = if (direction == 0) candidate else point(
                    subtract(zero, candidate.x), subtract(zero, candidate.y))
                // Both the local edge and the source are exact straight-line geometry;
                // checking all vertices suffices for entire-source half-plane containment.
                var beyondSourceEdge = true
                for (v in vertices) {
                    chargeIncidentLassoWork()
                    if (sign(dot(vector(v, a), n)) == Sign.NEGATIVE) {
                        beyondSourceEdge = false
                        break
                    }
                }
                if (!beyondSourceEdge || sign(dot(vector(a, center), n)) != Sign.POSITIVE)
                    continue
                val nxrx = multiply(n.x, rx)
                val nyry = multiply(n.y, ry)
                val ellipseNormSquared = add(multiply(nxrx, nxrx), multiply(nyry, nyry))
                val lineNormSquared = squared(n)
                val ellipseNorm = verifiedDyadicRootOrNull(ellipseNormSquared) ?: continue
                val lineNorm = verifiedDyadicRootOrNull(lineNormSquared) ?: continue
                val ellipsePoint = point(
                    add(center.x, divide(multiply(rxSquared, n.x), ellipseNorm)),
                    add(center.y, divide(multiply(rySquared, n.y), ellipseNorm)))
                val support = point(
                    add(ellipsePoint.x, divide(multiply(stroke, n.x), lineNorm)),
                    add(ellipsePoint.y, divide(multiply(stroke, n.y), lineNorm)))
                // The candidate source edge must be the exact support line, not a
                // parallel edge at a merely close, rounded, or unrelated offset.
                if (sign(dot(vector(a, support), n)) != Sign.ZERO) continue
                // A support line meets a strictly convex ellipse + disk in exactly
                // one point. Check EVERY active group in case the point lies at a
                // split junction on a different collinear source fragment.
                val contact = groups.indices.any { j ->
                    chargeIncidentLassoWork()
                    val g = groups[j]
                    toggles[j] && onSegment(support,
                        vertices[g.startVertexId], vertices[g.endVertexId])
                }
                return if (contact) Relation.BOUNDARY_ONLY_CONTACT else Relation.DISJOINT
            }
        }
        return null
    }

    /** Exact closed-segment containment, with no Double endpoints or tolerance. */
    private fun SelectionExactArithmetic.onSegment(p: Point, a: Point, b: Point): Boolean {
        chargeIncidentLassoWork(3)
        if ((compare(p.x, a.x) == Sign.NEGATIVE && compare(p.x, b.x) == Sign.NEGATIVE) ||
            (compare(p.x, a.x) == Sign.POSITIVE && compare(p.x, b.x) == Sign.POSITIVE) ||
            (compare(p.y, a.y) == Sign.NEGATIVE && compare(p.y, b.y) == Sign.NEGATIVE) ||
            (compare(p.y, a.y) == Sign.POSITIVE && compare(p.y, b.y) == Sign.POSITIVE)) return false
        return sign(cross(vector(b, a), vector(p, a))) == Sign.ZERO
    }

    private enum class EdgeCertificate { OVERLAP, SEPARATED, UNRESOLVED }
    private data class ArcCell(
        val quadrant: Int, val lo: Rational, val hi: Rational, val depth: Int,
    )

    /** Rational parameterization of the four ellipse quadrants, no trigonometric rounding. */
    private fun SelectionExactArithmetic.ellipsePoint(
        quadrant: Int, t: Rational, center: Point, rx: Rational, ry: Rational,
        one: Rational, two: Rational,
    ): Point {
        chargeIncidentLassoWork(3)
        val tt = multiply(t, t)
        val den = add(one, tt)
        val cosine = divide(subtract(one, tt), den)
        val sine = divide(multiply(two, t), den)
        return when (quadrant) {
            0 -> point(add(center.x, multiply(rx, cosine)), add(center.y, multiply(ry, sine)))
            1 -> point(subtract(center.x, multiply(rx, sine)), add(center.y, multiply(ry, cosine)))
            2 -> point(subtract(center.x, multiply(rx, cosine)), subtract(center.y, multiply(ry, sine)))
            3 -> point(add(center.x, multiply(rx, sine)), subtract(center.y, multiply(ry, cosine)))
            else -> error("Internal quadrant invariant")
        }
    }

    /** Either one exact distance witness proves strict overlap, or all exact arc boxes prove
     * strict separation; a finite unresolved subdivision must NEVER become a disjoint claim. */
    private fun SelectionExactArithmetic.certifyEdge(
        a: Point, b: Point, center: Point, rx: Rational, ry: Rational,
        rSquared: Rational, zero: Rational, one: Rational,
    ): EdgeCertificate {
        val two = rational(2)
        val half = rational(1, 2)
        val stack = java.util.ArrayDeque<ArcCell>()
        retainRecord(1024)
        for (q in 0 until 4) stack.addLast(ArcCell(q, zero, one, 0))
        var visited = 0
        var inconclusive = false
        while (stack.isNotEmpty()) {
            chargeIncidentLassoWork(4)
            if (++visited > MAX_ARC_CELLS) return EdgeCertificate.UNRESOLVED
            val cell = stack.removeLast()
            val p = ellipsePoint(cell.quadrant, cell.lo, center, rx, ry, one, two)
            val q = ellipsePoint(cell.quadrant, cell.hi, center, rx, ry, one, two)
            if (compare(pointSegmentSquared(p, a, b, zero), rSquared) == Sign.NEGATIVE ||
                compare(pointSegmentSquared(q, a, b, zero), rSquared) == Sign.NEGATIVE)
                return EdgeCertificate.OVERLAP
            // On each quarter, BOTH x and y are monotone in t. The two rational
            // endpoints therefore define an exact enclosing local rectangle.
            val minX = if (compare(p.x, q.x) == Sign.NEGATIVE) p.x else q.x
            val maxX = if (compare(p.x, q.x) == Sign.POSITIVE) p.x else q.x
            val minY = if (compare(p.y, q.y) == Sign.NEGATIVE) p.y else q.y
            val maxY = if (compare(p.y, q.y) == Sign.POSITIVE) p.y else q.y
            val distanceLower = segmentBoxDistanceSquared(a, b, minX, maxX, minY, maxY, zero, one)
            if (compare(distanceLower, rSquared) == Sign.POSITIVE) continue
            if (cell.depth == MAX_ARC_DEPTH) {
                inconclusive = true
                continue
            }
            val mid = multiply(add(cell.lo, cell.hi), half)
            retainRecord(128) // bounded stack/output accounting, independent of JVM heap
            stack.addLast(ArcCell(cell.quadrant, cell.lo, mid, cell.depth + 1))
            stack.addLast(ArcCell(cell.quadrant, mid, cell.hi, cell.depth + 1))
        }
        return if (inconclusive) EdgeCertificate.UNRESOLVED else EdgeCertificate.SEPARATED
    }

    private fun SelectionExactArithmetic.squared(v: Point): Rational =
        add(multiply(v.x, v.x), multiply(v.y, v.y))
    private fun SelectionExactArithmetic.dot(a: Point, b: Point): Rational =
        add(multiply(a.x, b.x), multiply(a.y, b.y))
    private fun SelectionExactArithmetic.pointSegmentSquared(
        p: Point, a: Point, b: Point, zero: Rational,
    ): Rational {
        chargeIncidentLassoWork(4)
        val ab = vector(b, a); val ap = vector(p, a)
        val length = squared(ab)
        if (sign(length) == Sign.ZERO) return squared(ap)
        val projection = dot(ap, ab)
        if (compare(projection, zero) != Sign.POSITIVE) return squared(ap)
        if (compare(projection, length) != Sign.NEGATIVE) return squared(vector(p, b))
        val area = cross(ab, ap)
        return divide(multiply(area, area), length)
    }
    private fun SelectionExactArithmetic.segmentDistanceSquared(
        a: Point, b: Point, c: Point, d: Point, zero: Rational, one: Rational,
    ): Rational {
        chargeIncidentLassoWork(5)
        val ab = vector(b, a); val cd = vector(d, c); val ac = vector(c, a)
        val denom = cross(ab, cd)
        if (sign(denom) != Sign.ZERO) {
            val t = divide(cross(ac, cd), denom)
            val u = divide(cross(ac, ab), denom)
            if (compare(t, zero) != Sign.NEGATIVE && compare(t, one) != Sign.POSITIVE &&
                compare(u, zero) != Sign.NEGATIVE && compare(u, one) != Sign.POSITIVE) return zero
        }
        var best = pointSegmentSquared(a, c, d, zero)
        for (other in listOf(pointSegmentSquared(b, c, d, zero),
            pointSegmentSquared(c, a, b, zero), pointSegmentSquared(d, a, b, zero))) {
            chargeIncidentLassoWork()
            if (compare(other, best) == Sign.NEGATIVE) best = other
        }
        return best
    }
    /** Exact minimum squared distance from one closed segment to an enclosing axis-aligned box. */
    private fun SelectionExactArithmetic.segmentBoxDistanceSquared(
        a: Point, b: Point, minX: Rational, maxX: Rational, minY: Rational,
        maxY: Rational, zero: Rational, one: Rational,
    ): Rational {
        chargeIncidentLassoWork(5)
        fun inside(p: Point): Boolean =
            compare(p.x, minX) != Sign.NEGATIVE && compare(p.x, maxX) != Sign.POSITIVE &&
                compare(p.y, minY) != Sign.NEGATIVE && compare(p.y, maxY) != Sign.POSITIVE
        if (inside(a) || inside(b)) return zero
        val corners = listOf(point(minX, minY), point(maxX, minY),
            point(maxX, maxY), point(minX, maxY))
        var best = segmentDistanceSquared(a, b, corners[0], corners[1], zero, one)
        for (i in 1 until 4) {
            chargeIncidentLassoWork()
            val next = segmentDistanceSquared(a, b, corners[i], corners[(i + 1) % 4], zero, one)
            if (compare(next, best) == Sign.NEGATIVE) best = next
        }
        return best
    }

    private const val MAX_SOURCE_NORMAL_CANDIDATES = 16
    private const val MAX_ARC_DEPTH = 7
    private const val MAX_ARC_CELLS = 160
}
